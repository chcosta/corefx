@Library(['dotnet-ci']) _

build(params, env)

def build(params, env)
{
    library "buildtools@${params.BuildToolsSharedLibraryBranchOrCommit}"

    def helixEndpointCredential = 'helixStagingEndpointWithEntity'
    def sender
    def workItemId
    officialBuildStep {
        sender = createSenderMap(helixEndpointCredential)
        sendEvent('WorkItemQueued', 
                    [WorkItemFriendlyName: "Orchestration"],
                    sender)
    }
    try {

        // Incoming parameters.  Access with "params.<param name>".
        // Note that the parameters will be set as env variables so we cannot use names that conflict
        // with the engineering system parameter names.
        // CGroup - Build configuration.
        // TestOuter - If true, runs outerloop, if false runs just innerloop

        def submittedHelixJson = null
        def dockerImageName = 'microsoft/dotnet-buildtools-prereqs:rhel7_prereqs_2'
        if(params.AGroup == 'arm') {
        dockerImageName = 'microsoft/dotnet-buildtools-prereqs:ubuntu-14.04-cross-0cd4667-20172211042239'
        }

        def envVars = []

        if(params.AGroup == 'arm') {
            envVars = ["ROOTFS_DIR=/crossrootfs/arm"]
        }

        withEnv(envVars) {
            simpleDockerNode(dockerImageName, 'latest', '-e ROOTFS_DIR') {
                stage ('Checkout source') {
                    checkoutRepo()
                }
                officialBuildStep {
                    sendEvent('JobStarted', 
                                [Creator: env.USERNAME, 
                                QueueId: "Build", 
                                Source: "official/corefx/jenkins/${getBranch()}/", 
                                JobType: "build/product/", 
                                Build: params.OfficialBuildId.replace('-', '.'),
                                Properties: [OperatingSystem: "Linux",
                                            Platform: params.AGroup,
                                            ConfigurationGroup: params.CGroup,
                                            SubType: params.TGroup]],
                                sender)
                    sendEvent('WorkItemStarted', 
                                [WorkItemFriendlyName: "Orchestration"],
                                sender)
                    sendEvent('VsoBuildInformation', 
                                [BuildNumber: params.OfficialBuildId.replace('-', '.'), Uri: env.BUILD_URL, LogUri: env.BUILD_URL],
                                sender)
                    sendEvent('ExternalLink', 
                                [Description: "Jenkins Information", Uri: env.BUILD_URL],
                                sender)
                    sendEvent('ChildJobCreated', 
                                [ChildId: sender.correlationId],
                                createSenderMap(sender.credentialsIdentifier, params.CorrelationId))
                }
                def logFolder = getLogFolder()

                stage ('Initialize tools') {
                    // Init tools
                    sh './init-tools.sh'
                }
                stage ('Generate version assets') {
                    def buildArgs = ''
                    officialBuildStep {
                        buildArgs += "-OfficialBuildId=${params.OfficialBuildId}"
                    }
                    buildArgs += " -- /t:GenerateVersionSourceFile /p:GenerateVersionSourceFile=true"
                    
                    // Generate the version assets.  Do we need to even do this for non-official builds?
                    sh "./build-managed.sh ${buildArgs}"
                }
                stage ('Sync') {
                    sh "./sync.sh -p -- /p:ArchGroup=${params.AGroup}"
                }
                stage ('Build Product') {
                    def buildArgs = "-buildArch=${params.AGroup} -${params.CGroup}"
                    if(params.CGroup == 'Release') {
                        buildArgs += ' -stripSymbols'
                    }
                    officialBuildStep {
                        buildArgs += " -OfficialBuildId=${params.OfficialBuildId}"
                    }

                    sh "./build.sh ${buildArgs}"
                }
                stage ('Build Tests') {
                    def additionalArgs = ''
                    if (params.TestOuter) {
                        additionalArgs += '-Outerloop'
                    }
                    sh "./build-tests.sh -buildArch=${params.AGroup} -${params.CGroup} -SkipTests ${additionalArgs} -- /p:ArchiveTests=true /p:EnableDumpling=true"
                }
                stage ('Submit To Helix For Testing') {
                    // Bind the credentials
                    withCredentials([string(credentialsId: 'CloudDropAccessToken', variable: 'CloudDropAccessToken'),
                                    string(credentialsId: 'OutputCloudResultsAccessToken', variable: 'OutputCloudResultsAccessToken')]) {
                        // Ask the CI SDK for a Helix source that makes sense.  This ensures that this pipeline works for both PR and non-PR cases
                        def helixSource = getHelixSource()
                        // Ask the CI SDK for a Build that makes sense.  We currently use the hash for the build
                        def helixBuild = getCommit()
                        // Get the user that should be associated with the submission
                        def helixCreator = getUser()
                        // Target queues
                        def targetHelixQueues = []
                        
                        // TODO: Figure out targetQueues for other architectures
                        if(params.AGroup == 'x64') {
                            targetHelixQueues = ['Centos.73.Amd64.Open',
                                                'RedHat.73.Amd64.Open',
                                                'Debian.87.Amd64.Open',
                                                'Ubuntu.1404.Amd64.Open',
                                                'Ubuntu.1604.Amd64.Open',
                                                'suse.422.amd64.Open',
                                                'fedora.25.amd64.Open',]
                            if (params.TestOuter) {
                                targetHelixQueues += ['Debian.90.Amd64.Open',
                                                    'Fedora.26.Amd64.Open',
                                                    'SLES.12.Amd64.Open',
                                                    'Ubuntu.1704.Amd64.Open',]
                            }

                            sh "./Tools/msbuild.sh src/upload-tests.proj /p:ArchGroup=${params.AGroup} /p:ConfigurationGroup=${params.CGroup} /p:TestProduct=corefx /p:TimeoutInSeconds=1200 /p:TargetOS=Linux /p:HelixJobType=test/functional/cli/ /p:HelixSource=${helixSource} /p:BuildMoniker=${helixBuild} /p:HelixCreator=${helixCreator} /p:CloudDropAccountName=dotnetbuilddrops /p:CloudResultsAccountName=dotnetjobresults /p:CloudDropAccessToken=\$CloudDropAccessToken /p:CloudResultsAccessToken=\$OutputCloudResultsAccessToken /p:HelixApiEndpoint=https://helix.dot.net/api/2017-04-14/jobs /p:TargetQueues=${targetHelixQueues.join('+')} /p:HelixLogFolder=${WORKSPACE}/${logFolder}/ /p:HelixCorrelationInfoFileName=SubmittedHelixRuns.txt"

                            submittedHelixJson = readJSON file: "${logFolder}/SubmittedHelixRuns.txt"
                        }
                    }
                }
                officialBuildStep {
                    stage('Push packages to Azure') {
                        withCredentials([string(credentialsId: 'CloudDropAccessToken', variable: 'CloudDropAccessToken')]) {
                            def containerName = getOfficialBuildAzureContainerName('corefx', params.OfficialBuildId)
                            sh "./publish-packages.sh -AzureAccount=dotnetbuilddrops -AzureToken=\$CloudDropAccessToken -Container=${containerName} -- /p:OverwriteOnPublish=false"
                        }
                    }
                }            
            }
        }
        officialBuildStep(false) {
            stage ('Execute Tests') {
                if(params.AGroup == 'x64') {
                    def contextBase
                    if (params.TestOuter) {
                        contextBase = "Linux x64 Tests w/outer - ${params.CGroup}"
                    }
                    else {
                        contextBase = "Linux x64 Tests - ${params.CGroup}"
                    }
                    waitForHelixRuns(submittedHelixJson, contextBase)
                }
            }
        }
        officialBuildStep {
            sendEvent('WorkItemFinished',
                      [ExitCode: '0'],
                      sender)
        }
    }
    catch(Exception e) {
        officialBuildStep {
            sendEvent('VsoBuildWarningsAndErrors',
                      [WarningCount: '0', ErrorCount: '1'],
                      sender)
        }
        officialBuildStep {
            sendEvent('WorkItemFinished',
                      [ExitCode: '1'],
                      sender)
        }
        throw e
    }
}
