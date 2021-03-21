#!groovy

node("executor") {
    checkout scm

    def commitHash  = sh(returnStdout: true, script: 'git rev-parse HEAD | cut -c-7').trim()
    def imageTag = "${env.BUILD_NUMBER}-${commitHash}"

    def sbt = "sbt -Dsbt.log.noformat=true"
    def pennsieveNexusCreds = usernamePassword(
        credentialsId: "pennsieve-nexus-ci-login",
        usernameVariable: "PENNSIEVE_NEXUS_USER",
        passwordVariable: "PENNSIEVE_NEXUS_PW"
    )

    stage("Build") {
        withCredentials([pennsieveNexusCreds]) {
            sh "$sbt clean compile"
        }
    }

    stage("Test") {
        withCredentials([pennsieveNexusCreds]) {
            try {
                sh "$sbt coverageOn test"
            } finally {
                junit '**/target/test-reports/*.xml'
            }
        }
    }

    stage("Coverage") {
        withCredentials([pennsieveNexusCreds]) {
            sh "$sbt coverageReport"
        }
    }

    if (["master"].contains(env.BRANCH_NAME)) {
        stage("Docker") {
            withCredentials([pennsieveNexusCreds]) {
                sh "$sbt notifications-service/docker"
            }

            sh "docker tag pennsieve/notifications-service:latest pennsieve/notifications-service:$imageTag"
            sh "docker push pennsieve/notifications-service:latest"
            sh "docker push pennsieve/notifications-service:$imageTag"
        }

        stage("Deploy") {
            build job: "service-deploy/pennsieve-non-prod/us-east-1/dev-vpc-use1/dev/notifications-service",
            parameters: [
                string(name: 'IMAGE_TAG', value: imageTag),
                string(name: 'TERRAFORM_ACTION', value: 'apply')
            ]
        }
    }
}
