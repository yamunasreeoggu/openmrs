pipeline {
    agent any

    environment {
        OPENMRS_REPO = 'https://github.com/yamunasreeoggu/openmrs.git'
        EC2_INSTANCE = '172.31.55.187'
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: "master", url: "${OPENMRS_REPO}"
            }
        }

        stage('Build') {
            steps {
                //sh "mvn clean package"
                echo "build"
            }
        }

        stage('Deploy') {
            steps {
                sshagent(['SSH_KEY']) {
                  sh "scp  -o StrictHostKeyChecking=no /var/lib/jenkins/workspace/openmrs/webapp/target/openmrs.war ec2-user@${EC2_INSTANCE}:/opt/apache-tomcat-8.5.100/webapps/"
                }
            }
        }
    }
}


