/*
 * Copyright (c) 2023, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.text.SimpleDateFormat

def fossaProjectName = ""
def revisionTag = ""
def latestSuccessfulISBuildNumber = ""

pipeline {
    agent {
        label 'PRODUCT_ECS'
    }

    parameters {
        string(
                name: 'product_job_name',
                defaultValue: 'products/product-is',
                description: 'Name of the IS Jenkins job'
        )
        string(
                name: 'last_successful_build_number',
                defaultValue: '',
                description: 'Last Successful Build Number of the IS Jenkins job'
        )
    }

    stages {
        stage('Clean Directory') {
            steps {
                // Clean the directory
                logInfo("Job is starting... Cleaning the workspace directories!")
                sh 'rm -rf *'
            }
        }
        stage('Get Latest IS Build') {
            steps {
                script {
                    latestSuccessfulISBuildNumber = params.last_successful_build_number

                    // Download the latest successful IS build
                    getLatestISBuild()

                    // Find IS zip
                    def getProductNameCommand = '''
                        find . -type f | grep -v 'src' | xargs -I {} basename {} | sed 's/\\.zip$//'
                    '''
                    def productName = sh(returnStdout: true, script: getProductNameCommand).trim()

                    // Initialize variables
                    def productNameSplit = productName.split("-")
                    // Get the project name for FOSSA (Ex: wso2is-7.0.0)
                    fossaProjectName = productNameSplit[0..1].join("-")
                    // Get the tag name for the FOSSA project (7.0.0-m2-SNAPSHOT-<is-build-number>)
                    revisionTag = productNameSplit[1..-1].join("-") + "-" + latestSuccessfulISBuildNumber

                    // Unzip IS pack
                    unzipISPack(productName, fossaProjectName)
                }
            }
        }
        stage('Run FOSSA Scan') {
            steps {
                withCredentials([string(credentialsId: 'FOSSA_API_KEY', variable: 'fossaAPIKey')]) {
                    // Run FOSSA scan
                    runFOSSAScan(fossaAPIKey, fossaProjectName, revisionTag)
                }
            }
        }
        stage('Get FOSSA Scan Results') {
            steps {
                withCredentials([string(credentialsId: 'FOSSA_API_KEY', variable: 'fossaAPIKey')]) {
                    // Wait for FOSSA scan to complete and get results
                    getFOSSAScanResults(fossaAPIKey, fossaProjectName, revisionTag)
                }
            }
        }
    }

    post {
        success {
            logInfo("FOSSA Scan was a SUCCESS for ${params.product_job_name} build: ${latestSuccessfulISBuildNumber}")
        }
        failure {
            logError("FOSSA Scan was a FAILURE for ${params.product_job_name} build: ${latestSuccessfulISBuildNumber}")
        }
        always {
            // Clean the directory
            logInfo("Job is completed... Deleting the workspace directories!")
            sh 'rm -rf *'
        }
    }
}

def getLatestISBuild() {

    copyArtifacts(projectName: params.product_job_name, selector: lastSuccessful(), filter: '**/wso2is-*.zip');
    logInfo("Copied the latest successful build pack for job: ${params.product_job_name}.")

    sh """
        cp modules/distribution/target/*.zip . 
        rm -rf modules org.wso2.is
    """
}

def unzipISPack(productName, fossaProjectName) {

    /*
        Unzip the IS pack and move it to a new folder with FOSSA project name
        (This done because 'fossa analyze' uses the folder name as the project name)
    */
    sh """
        unzip -q ${productName}.zip
        mv ${productName} ${fossaProjectName}
    """

    logInfo("Unzipped the ${params.product_job_name} pack.")
}

def runFOSSAScan(fossaAPIKey, fossaProjectName, revisionTag) {

    logInfo("Starting FOSSA scan on IS pack.")
    sh """
        curl -H 'Cache-Control: no-cache' https://raw.githubusercontent.com/fossas/fossa-cli/master/install-latest.sh | bash
        export FOSSA_API_KEY=${fossaAPIKey}
        cd ${fossaProjectName}
        fossa analyze --unpack-archives -T 'Products' -b 'pre-releases-jenkins' -r '${revisionTag}'
    """
    logInfo("Completed FOSSA scan on ${params.product_job_name} pack.")
}

def getFOSSAScanResults(fossaAPIKey, fossaProjectName, revisionTag) {

    // Sleep for 20 minutes (20 * 60 seconds) [Waiting for scan to complete]
    logInfo("Waiting for FOSSA scan results.")
    sleep(1200)
    // Timeout fossa test after 20 minutes
    sh """
        fossa test --timeout 1200 --fossa-api-key '${fossaAPIKey}' --project '${fossaProjectName}' -r '${revisionTag}' --format text-pretty
    """
    logInfo("FOSSA scan was SUCCESSFUL.")
}

def logInfo(message) {

    echo "[${getUTCTimeStamp()})][INFO]: ${message}"
}

def logError(message) {

    echo "[${getUTCTimeStamp()})][ERROR]: ${message}"
}

static def getUTCTimeStamp() {

    def currentDate = new Date()

    def utcTimeZone = TimeZone.getTimeZone("UTC")
    def dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
    dateFormat.setTimeZone(utcTimeZone)

    return dateFormat.format(currentDate)
}
