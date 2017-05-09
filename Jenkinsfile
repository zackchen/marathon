#!/usr/bin/env groovy

node('JenkinsMarathonCI-Debian8') {
    try {
        stage("Checkout and Provision") {
            checkout scm
            gitCommit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            shortCommit = gitCommit.take(8)
            currentBuild.displayName = "#${env.BUILD_NUMBER}: ${shortCommit}"

            sh "bin/kill-stale-test-processes"
            sh "sudo apt-get -y clean"
            sh "sudo apt-get -y update"
            sh "sudo apt-get install -y --force-yes --no-install-recommends curl"
            sh """if grep -q MesosDebian \$WORKSPACE/project/Dependencies.scala; then
                     MESOS_VERSION=\$(sed -n 's/^.*MesosDebian = "\\(.*\\)"/\\1/p' <\$WORKSPACE/project/Dependencies.scala)
                   else
                     MESOS_VERSION=\$(sed -n 's/^.*mesos=\\(.*\\)&&.*/\\1/p' <\$WORKSPACE/Dockerfile)
                   fi
                   sudo apt-get install -y --force-yes --no-install-recommends mesos=\$MESOS_VERSION
               """
        }
        stage("Compile and Test") {
          try {
            withEnv(['RUN_DOCKER_INTEGRATION_TESTS=true', 'RUN_MESOS_INTEGRATION_TESTS=true']) {
              sh """sudo -E sbt -Dsbt.log.format=false clean \
                    coverage test coverageReport \
                    integration:test mesos-simulation/integration:test \
                    scapegoat doc \
              """
            }
          } finally {
            archiveArtifacts artifacts: 'target/**/scapegoat-report/scapegoat.html', allowEmptyArchive: true
            junit allowEmptyResults: true, testResults: 'target/test-reports/**/*.xml'
            junit allowEmptyResults: true, testResults: 'target/test-reports/*integration/**/*.xml'
            archiveArtifacts artifacts: 'target/**/coverage-report/cobertura.xml, target/**/scoverage-report/**', allowEmptyArchive: true
          }
        }
        stage("Assemble and Archive Binaries") {
            sh "sudo -E sbt assembly"
            archiveArtifacts artifacts: 'target/**/classes/**', allowEmptyArchive: true
        }
    } catch (Exception err) {
        currentBuild.result = 'FAILURE'
        if( env.BRANCH_NAME.startsWith("releases/") || env.BRANCH_NAME == "master" ) {
          slackSend(
            message: "(;¬_¬) @marathon-oncall branch `${env.BRANCH_NAME}` failed in build `${env.BUILD_NUMBER}`. (<${env.BUILD_URL}|Open>)",
            color: "danger",
            channel: "#marathon-dev",
            tokenCredentialId: "f430eaac-958a-44cb-802a-6a943323a6a8")
        }
    }
}
