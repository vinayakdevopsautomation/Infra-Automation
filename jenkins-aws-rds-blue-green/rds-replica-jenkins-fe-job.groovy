//RDS Replica creation
 
pipelineJob('aws/XXXX/xxx-services-xx-xx-xxx-rds-replica-deploy') {

  description('RDS Replica deploy pipeline job')

  logRotator(10, 10)

  parameters {

  //  choiceParam('gitUrl', [gitDeployRepo], '')

  //  stringParam('region', 'eu-west-1', 'AWs region')

    choiceParam('awsAccountId', ['Select','xxxxxx','xxxxxx'], 'NonProd Central:xxxxx, NonProd ClientFunc:xxxxxx')

  //  stringParam('teamname', 'xxx-services-xxx', 'AWs Team Name')

  //  stringParam('teamdeployername', 'xx-xx-services-xxx-role', 'AWs deployer Name')

    stringParam('rdsname', '', 'Mandatory: Existing RDS')

    choiceParam('performBlueGreenDeployment', ['false', 'true'], 'This will only perform blue green deployment using below values.')

  //  stringParam('parametergroupname', '', 'parametergroupname for RDS')

    stringParam('versionUpgrade', '', 'Optional: Enter RDS version or-else it will replicate from the existing RDS')

    stringParam('storageUpdate', '',  'Optional: Enter storage size (GB) or-else it will replicate from the existing RDS')

    stringParam('instanceResize', '', 'Optional: Enter instance class or-else it will replicate from the existing RDS')

    stringParam('storageType', '',  'Optional: Enter storage type or-else it will replicate from the existing RDS (e.g., gp2, gp3)')

    choiceParam('performSwitchover', ['false', 'true'], 'Perform switchover once deployment is in AVAILABLE state. If the switchover is not completed, the newly created RDS instance — including replication and updated configuration — will be deleted, with no impact to your existing RDS<br> If the switchover is completed, the current (older) RDS instance will be decommissioned, and the upgraded RDS instance will remain as the new primary')

    choiceParam('PerformCleanupAfterSwitchOver', ['false', 'true'], 'To perform cleanup of old blue environment after switchover completed. This will delete the blue green deployment and old database instance.')

    choiceParam('PerformCleanupBeforeSwitchover', ['false', 'true'], 'To perform cleanup of green environment before switchover is done. This will delete the blue green deployment and new/green database instance.')

  }

  definition {

    cps {

      script(readFileFromWorkspace('pipelines/aws/xxxxx/xx-services-xxx/xxxx/xxxx/deploy/rds_replica_deploy.groovy'))

      sandbox()

    }

  }

}
 
