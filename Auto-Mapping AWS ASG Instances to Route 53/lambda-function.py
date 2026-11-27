import json
import boto3
import logging

# Set up logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Constants (Update these to match your environment)
S3_BUCKET = "your-bucket-name"
S3_KEY = "asg-cname-map.json"
ROUTE53_HOSTED_ZONE_ID = "YOUR_HOSTED_ZONE_ID" # Hosted Zone for your CNAME domain

# Initialize AWS SDK clients
s3_client = boto3.client('s3')
ec2_client = boto3.client('ec2')
route53_client = boto3.client('route53')

def lambda_handler(event, context):
    logger.info("Received event: %s", json.dumps(event))
    
    try:
        # 1. Parse trigger event for launch variables
        detail = event.get('detail', {})
        asg_name = detail.get('AutoScalingGroupName')
        instance_id = detail.get('EC2InstanceId')
        
        if not asg_name or not instance_id:
            logger.error("Event is missing AutoScalingGroupName or EC2InstanceId.")
            return {"status": "error", "message": "Missing event variables."}
            
        logger.info(f"Triggered by ASG '{asg_name}' launch for instance '{instance_id}'")

        # 2. Fetch ASG to CNAME JSON mapping from S3
        try:
            s3_response = s3_client.get_object(Bucket=S3_BUCKET, Key=S3_KEY)
            mapping_data = json.loads(s3_response['Body'].read().decode('utf-8'))
        except Exception as e:
            logger.error(f"Error reading mapping from S3 (s3://{S3_BUCKET}/{S3_KEY}): {str(e)}")
            return {"status": "error", "message": "S3 fetch failed."}

        # Validate that the launching ASG is in our CNAME map
        cname = mapping_data.get(asg_name)
        if not cname:
            logger.info(f"No CNAME mapping exists for ASG '{asg_name}'. Skipping.")
            return {"status": "skipped", "message": "ASG name is not in the S3 configuration."}
            
        logger.info(f"Matched CNAME mapping: '{asg_name}' -> '{cname}'")

        # 3. Retrieve the public (or private) IP of the new instance
        try:
            ec2_response = ec2_client.describe_instances(InstanceIds=[instance_id])
            reservations = ec2_response.get('Reservations', [])
            if not reservations or not reservations[0].get('Instances'):
                raise ValueError("Instance details could not be found.")
                
            instance = reservations[0]['Instances'][0]
            
            # Use Public IP; Swap `PublicIpAddress` to `PrivateIpAddress` if processing internal jobs.
            ip_address = instance.get('PublicIpAddress') or instance.get('PrivateIpAddress')
            
            if not ip_address:
                logger.error(f"Could not fetch an IP address for instance {instance_id}.")
                return {"status": "error", "message": "No IP address found."}
                
            logger.info(f"Instance '{instance_id}' IP is: {ip_address}")
            
        except Exception as e:
            logger.error(f"Error fetching EC2 IP address: {str(e)}")
            return {"status": "error", "message": "EC2 IP fetch failed."}

        # 4. Perform DNS mapping update in AWS Route 53
        try:
            # We must resolve CNAME update to point target server IP address.
            # Pointing CNAME via A Record (or updating mapping logic)
            response = route53_client.change_resource_record_sets(
                HostedZoneId=ROUTE53_HOSTED_ZONE_ID,
                ChangeBatch={
                    'Comment': f'Auto-remapping {cname} to new ASG Instance {instance_id} ({ip_address})',
                    'Changes': [
                        {
                            'Action': 'UPSERT',
                            'ResourceRecordSet': {
                                'Name': cname,
                                'Type': 'A', # Uses record update to instance target destination
                                'TTL': 60,   # Shorter TTL helps in dynamic scaling environments
                                'ResourceRecords': [
                                    {
                                        'Value': ip_address
                                    }
                                ]
                            }
                        }
                    ]
                }
            )
            
            change_id = response['ChangeInfo']['Id']
            logger.info(f"DNS Update Successful. Change ID: {change_id}")
            
        except Exception as e:
            logger.error(f"Failed to update DNS route in Route 53: {str(e)}")
            return {"status": "error", "message": "Route 53 Update Failed."}

        return {
            "status": "success",
            "message": f"Successfully mapped '{cname}' to '{ip_address}' from ASG '{asg_name}'"
        }
        
    except Exception as e:
        logger.error(f"General processing fail: {str(e)}")
        return {"status": "error", "message": "Execution encountered an unexpected error."}
