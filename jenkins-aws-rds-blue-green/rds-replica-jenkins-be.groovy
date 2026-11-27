	/* pipeline Jenkins frontend

* Deploy an application to a single environment

*

* Jenkins Environment Variables:

*   AWS_MANAGEMENT_PROXY

*   AWS_DEFAULT_REGION

*

* Jenkins Job Parameters:

*   gitUrl

*   gitBranch

*   terraformKey

*   terraformApplyPlan

*   terraformaccounttype

*   environment

*   location

*/

import groovy.json.JsonSlurper
 
def appRoleCredentialId="vault-approle-xxxx-services-xxxx"

def vaultAddr="https://vault.xxxxx.xxxxxx.com"

def awsEngineMountPath="aws-secret/xxx-services-xxx"

def awsEngineRoleName="xxx-services-xxx-terraform-aws-role"

env.region="eu-west-1"

env.teamname="xxx-services-xxx"

env.teamdeployername="xxx-xx-services-xxx-role"
 
 
def assumeSecretEngineRole(appRoleCredentialId, vaultAddr, awsEngineMountPath, awsEngineRoleName) {

  withCredentials([[$class: 'VaultTokenCredentialBinding', credentialsId: appRoleCredentialId, vaultAddr: vaultAddr]]) {

    def vaultSecretEngineResponse = sh(script: "curl -s -H 'X-Vault-Token: ${VAULT_TOKEN}' ${vaultAddr}/v1/${awsEngineMountPath}/creds/${awsEngineRoleName}", returnStdout: true).trim()

    def vaultSecretEngineJson = new JsonSlurper().parseText(vaultSecretEngineResponse).data

    awsAccessKeyId = vaultSecretEngineJson.access_key

    awsSecretAccessKey = vaultSecretEngineJson.secret_key

    awsSessionToken = vaultSecretEngineJson.security_token

  }

}
 
node('deploy-client') {

    def terraformdir = "RDS BlueGreen Deployment"
 
    env.http_proxy = env.AWS_MANAGEMENT_PROXY

    env.https_proxy = env.AWS_MANAGEMENT_PROXY

    env.HTTP_PROXY = "http://management-proxy.xxxx.aws.xxx.com:80"

    env.HTTPS_PROXY = "http://management-proxy.xxxx.xxxxx.com:80"
 
    if (appRoleCredentialId != '') {

        stage('Get AWS Credentials') {

            assumeSecretEngineRole(appRoleCredentialId, vaultAddr, awsEngineMountPath, awsEngineRoleName)

            env.AWS_ACCESS_KEY_ID = awsAccessKeyId

            env.AWS_SECRET_ACCESS_KEY = awsSecretAccessKey

            env.AWS_SESSION_TOKEN = awsSessionToken

        }

    }
 
    dir(terraformdir) {
 
        stage('Assume AWS Role for RDS Deployment') {

        script {

            def roleArn = "arn:aws:iam::${awsAccountId}:role/${teamname}/${teamdeployername}" 

            def sessionName = "jenkins-session"

            def credsJson = sh(

                script: """aws sts assume-role \

                    --role-arn ${roleArn} \

                    --role-session-name ${sessionName} \

                    --output json""",

                returnStdout: true

            ).trim()

            def creds = new groovy.json.JsonSlurper().parseText(credsJson)

            env.AWS_ACCESS_KEY_ID = creds.Credentials.AccessKeyId

            env.AWS_SECRET_ACCESS_KEY = creds.Credentials.SecretAccessKey

            env.AWS_SESSION_TOKEN = creds.Credentials.SessionToken

        }

        }
 
	    stage('Lookup RDS ARN and parameter group') {

    script {

        def dbName = "${rdsname}" // Replace with your actual DB name

        def region = "${region}"
 
        def sourceDbArn = sh(

            script: "aws2 rds describe-db-instances --region ${region} --query \"DBInstances[?DBInstanceIdentifier=='${dbName}'].DBInstanceArn\" --output text",

            returnStdout: true

        ).trim()
 
        if (!sourceDbArn || sourceDbArn == "None") {

            error "Failed to resolve RDS ARN for DB name: ${dbName}. Please check if the DB exists and permissions are correct."

        }
 
        echo "Resolved RDS ARN: ${sourceDbArn}"
 
        // You can now use sourceDbArn in the next stage

        env.SOURCE_DB_ARN = sourceDbArn
 
        // Step 2: Check versionUpgrade

        if (!versionUpgrade) {

            echo "No versionUpgrade provided. Skipping parameter group creation."

            return

        }

 
        // Step 3: Extract version info

        def versionParts = versionUpgrade.tokenize('.')

        def shortVersion = versionParts[0..1].join('.')  // e.g., 16.2

        def majorVersion = versionParts[0]               // e.g., 16

        def newParamGroupName = "${dbName}-v${majorVersion}"

        def engineFamily = "postgres${majorVersion}"
 
        // Step: Check if new parameter group already exists

        def existingGroupCheck = sh(

            script: "aws2 rds describe-db-parameter-groups --region ${region} --query \"DBParameterGroups[?DBParameterGroupName=='${newParamGroupName}'].DBParameterGroupName\" --output text",

            returnStdout: true

        ).trim()
 
        if (existingGroupCheck && existingGroupCheck == newParamGroupName) {

        echo "Parameter group ${newParamGroupName} already exists. Reusing it."

        env.NEW_PARAM_GROUP = newParamGroupName

        return

        }
 
        // Step 4: Find existing parameter group to copy from

//       def existingParamGroup = sh(

//           script: "aws2 rds describe-db-parameter-groups --region ${region} --query \"DBParameterGroups[?starts_with(DBParameterGroupName, '${dbName}')].DBParameterGroupName\" --output text | head -n 1",

  //          returnStdout: true

  //      ).trim()
 
  //      if (!existingParamGroup) {

  //          error "No existing parameter group found for prefix: ${dbName}"

  //      }
 
  //      echo "Copying parameters from: ${existingParamGroup}"
 
        // Step 5: Create new parameter group

        sh """

            aws2 rds create-db-parameter-group \

              --region ${region} \

              --db-parameter-group-name ${newParamGroupName} \

              --db-parameter-group-family ${engineFamily} \

              --description "Paramter group for ${dbName} for version ${versionUpgrade}"

        """

        sh """

        aws rds modify-db-parameter-group \

        --db-parameter-group-name ${newParamGroupName} \

        --parameters "ParameterName=rds.logical_replication,ParameterValue=1,ApplyMethod=pending-reboot" \

        --region ${region}

        """
 
 
//        // Step 6: Export parameters from old group

//        sh """

//           aws2 rds describe-db-parameters \

//             --region ${region} \

//             --db-parameter-group-name ${existingParamGroup} \

//             --query 'Parameters[?IsModifiable==`true`]' \

//             --output json > old-params.json

//       """
 
        // Step 7: Apply parameters in batches of 20

       // def applyParamsScript = """

//#!/bin/bash

//jq -c '.[]' old-params.json | split -l 20 - params_chunk_
 
//for file in params_chunk_*; do

//  jq -s '.' "\$file" > temp_params.json

//  aws2 rds modify-db-parameter-group \\

//    --region ${region} \\

//    --db-parameter-group-name ${newParamGroupName} \\

//    --parameters file://temp_params.json

//done

//"""
 
//        writeFile file: 'apply_params.sh', text: applyParamsScript

//        sh 'chmod +x apply_params.sh && ./apply_params.sh'
 
        // Step 8: Export new parameter group name

        env.NEW_PARAM_GROUP = newParamGroupName

        echo "New parameter group created and parameters copied: ${env.NEW_PARAM_GROUP}"

    }

}
 
if (params.performBlueGreenDeployment?.toBoolean()) {

    stage('RDS Blue/Green Deployment') {

        script {

            def deploymentName = "${rdsname}-bluegreen-deployment"

            def sourceDbArn = "${env.SOURCE_DB_ARN}" // Replace with actual ARN

            def region = "${region}"
 
            // Collect optional parameters

            def optionalParams = [

                engineVersion: versionUpgrade,

                instanceClass: instanceResize,

                storageType: storageType,

                storageSize: storageUpdate,

                parametergroupname: env.NEW_PARAM_GROUP

            ]
 
            // Build the command dynamically

            def cmd = [

                "aws2 rds create-blue-green-deployment",

                "--blue-green-deployment-name ${deploymentName}",

                "--source ${sourceDbArn}",

                "--region ${region}"

            ]
 
            // Add only non-null, non-empty parameters

            if (optionalParams.engineVersion) {

                cmd << "--target-engine-version ${optionalParams.engineVersion}"

            }

            if (optionalParams.parametergroupname) {

                cmd << "--target-db-parameter-group-name ${optionalParams.parametergroupname}"

            }

            if (optionalParams.instanceClass) {

                cmd << "--target-db-instance-class ${optionalParams.instanceClass}"

            }

            if (optionalParams.storageType) {

                cmd << "--target-storage-type ${optionalParams.storageType}"

            }

            if (optionalParams.storageSize) {

                cmd << "--target-allocated-storage ${optionalParams.storageSize}"

            }
 
            // Join command parts into a single string

            def finalCmd = cmd.join(' \\\n')
 
            sh "aws2 --version"

            sh """

                ${finalCmd}

            """

        }

    }

} else {

    stage('Skip Blue/Green Deployment') {

        echo "Blue/Green deployment will not happen."

    }

}
 
if (params.performSwitchover?.toBoolean()) {

        stage('Switchover to Green Environment') {

            script {

                    def region = "${region}"

                    def dbName = "${rdsname}"

                    def deploymentName = sh(

                        script: "aws2 rds describe-blue-green-deployments --region ${region} --query \"BlueGreenDeployments[?Source=='arn:aws:rds:${region}:${awsAccountId}:db:${dbName}'].BlueGreenDeploymentIdentifier\" --output text",

                        returnStdout: true

                    ).trim()

                    echo "Switchover will be performed for deployment: ${deploymentName}"

                    sh """ aws2 rds switchover-blue-green-deployment --blue-green-deployment-identifier ${deploymentName} --switchover-timeout 300 --region ${region} """

					}

        }

} else {

            stage('Skip swithover DNS') {

                echo "RDS DNS switchover will not happen."

            }

        }

 
if (params.PerformCleanupAfterSwitchOver?.toBoolean()) {

        stage('Cleanup of old blue Environment') {

            script {

                    def region = "${region}"

                    def dbName = "${rdsname}"

                    def oldblueinstance = sh(

                        script: "aws2 rds describe-blue-green-deployments --region ${region} --query \"BlueGreenDeployments[?Target=='arn:aws:rds:${region}:${awsAccountId}:db:${dbName}'].Source\" --output json",

                        returnStdout: true

                    ).trim()

                    echo " old instance form json ${oldblueinstance} "

                    def olddbInstanceName = oldblueinstance.replaceAll(/[\[\]"]/, '').trim()

                    def dbInstanceName = olddbInstanceName.tokenize(':')[-1]

                    echo "old blue DB Instance Name: ${dbInstanceName}"

                    sh """ aws2 rds modify-db-instance --db-instance-identifier ${dbInstanceName} --no-deletion-protection --region ${region} --apply-immediately """

					sh """ sleep 60 """

					sh """ aws2 rds delete-db-instance --db-instance-identifier ${dbInstanceName} --skip-final-snapshot --region ${region} """

					sh """ sleep 120 """

                    def deploymentName = sh(

                        script: "aws2 rds describe-blue-green-deployments --region ${region} --query \"BlueGreenDeployments[?Target=='arn:aws:rds:${region}:${awsAccountId}:db:${dbName}'].BlueGreenDeploymentIdentifier\" --output text",

                        returnStdout: true

                    ).trim()

                    echo "${deploymentName} will be deleted "

					//delete blue green deployment

                    sh """ aws2 rds delete-blue-green-deployment --blue-green-deployment-identifier ${deploymentName} --region ${region} """

					}

        }

} else {

            stage('Skip Cleanup of RDS') {

                echo "Cleanup will done after RDS switchover is completed"

            }

        }
 
if (params.PerformCleanupBeforeSwitchover?.toBoolean()) {

        stage('Cleanup of old blue Environment') {

            script {

                    def region = "${region}"

                    def dbName = "${rdsname}"

                    def oldblueinstance = sh(

                        script: "aws2 rds describe-blue-green-deployments --region ${region} --query \"BlueGreenDeployments[?Source=='arn:aws:rds:${region}:${awsAccountId}:db:${dbName}'].Target\" --output json",

                        returnStdout: true

                    ).trim()

                    echo " old instance form json ${oldblueinstance} "

                    def olddbInstanceName = oldblueinstance.replaceAll(/[\[\]"]/, '').trim()

                    def dbInstanceName = olddbInstanceName.tokenize(':')[-1]

                    echo "old blue DB Instance Name: ${dbInstanceName}"

                    sh """ aws2 rds modify-db-instance --db-instance-identifier ${dbInstanceName} --no-deletion-protection --region ${region} --apply-immediately """

					sh """ sleep 60 """

					sh """ aws2 rds delete-db-instance --db-instance-identifier ${dbInstanceName} --skip-final-snapshot --region ${region} """

					sh """ sleep 120 """

                    def deploymentName = sh(

                        script: "aws2 rds describe-blue-green-deployments --region ${region} --query \"BlueGreenDeployments[?Source=='arn:aws:rds:${region}:${awsAccountId}:db:${dbName}'].BlueGreenDeploymentIdentifier\" --output text",

                        returnStdout: true

                    ).trim()

                    echo "${deploymentName} will be deleted "

					//delete blue green deployment

                    sh """ aws2 rds delete-blue-green-deployment --blue-green-deployment-identifier ${deploymentName} --region ${region} """

					}

        }

} else {

            stage('Skip Cleanup of RDS') {

                echo "Skipping Termination of new Green environment"

            }

        }

    }

}
