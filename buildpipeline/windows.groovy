@Library(['dotnet-ci']) _

build(params, env)

def build(params, env)
{
    library "buildtools@${params.BuildToolsSharedLibraryBranchOrCommit}"

    def helixEndpointCredential = 'helixStagingEndpointWithEntity'
    def packagesDirectory
    def sender
    // ToDo: retrieve MicroBuild version from keyvault?
    def microBuildPluginVersion = "1.0.399"
    def microBuildPluginDirectory = "${packagesDirectory}\\MicroBuild.Plugins.Signing.${microBuildPluginVersion}"
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
        // TGroup - The target framework to build.
        // CGroup - Build configuration.
        // TestOuter - If true, runs outerloop, if false runs just innerloop
        def submittedHelixJson = null
        def submitToHelix = ((params.TGroup == 'netcoreapp' || params.TGroup == 'uap'))

        println ("Official Build Id: ${OfficialBuildId}")

        simpleNode('Windows_NT','latest') {
            stage ('Checkout source') {
                checkoutRepo()
            }
            officialBuildStep {
                sendEvent('JobStarted', 
                            [Creator: 'chcosta', 
                            QueueId: "Build", 
                            Source: "official/corefx/jenkins/${getBranch()}/", 
                            JobType: "build/product/", 
                            Build: params.OfficialBuildId.replace('-', '.'),
                            Properties: [OperatingSystem: "Windows",
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
            def framework = ''
            packagesDirectory = "${WORKSPACE}\\packages"
            if (params.TGroup == 'all') {
                framework = '-allConfigurations'
            }
            else {
                framework = "-framework:${params.TGroup}"
            }
            def buildTests = ((params.TGroup != 'all'))

            officialBuildStep {
                withPackage("MicroBuild.Plugins.Signing",
                            microBuildPluginVersion,
                            [[name: 'MicroBuildPlugin', source: 'https://devdiv.pkgs.visualstudio.com/DefaultCollection/_packaging/MicroBuildToolset/nuget/v2', username: 'jenkins', credentialsId: 'chcosta-vsts-packages']],
                            packagesDirectory) {}
            }
            stage ('Initialize tools') {
                // Init tools
                bat '.\\init-tools.cmd'
            }
            stage ('Generate Version Assets') {
                def buildArgs = '-GenerateVersion'
                officialBuildStep {
                    buildArgs += " -OfficialBuildId=${params.OfficialBuildId}"
                }
                bat ".\\build-managed.cmd ${buildArgs}"
            }
            stage ('Sync') {
                bat ".\\sync.cmd -p -- /p:ArchGroup=${params.AGroup} /p:RuntimeOS=win10 /p:EnableProfileGuidedOptimization=true"
            }
            stage ('Build Product') {
                def buildArgs = '' 
                officialBuildStep {
                    buildArgs = " -OfficialBuildId=${params.OfficialBuildId}"
                }
                buildArgs = buildArgs + " ${framework} -buildArch=${params.AGroup} -${params.CGroup} -- /p:RuntimeOS=win10"

                withEnv( ["MicroBuildPluginDirectory=${microBuildPluginDirectory}"] ) {
                    bat ".\\build.cmd ${buildArgs}"
                }
            }
            if (buildTests) {
                stage ('Build Tests') {
                    def buildArgs = "${framework} -buildArch=${params.AGroup} -${params.CGroup}"
                    def archiveTests = 'false'
                    if (params.TestOuter) {
                        buildArgs += ' -Outerloop'
                    }
                    if (submitToHelix) {
                        archiveTests = 'true'
                    }
                    if (submitToHelix || params.TGroup == 'uapaot') {
                        buildArgs += ' -SkipTests'
                    }
                    else {
                        officialBuildStep {
                            buildArgs += ' -SkipTests'
                        }
                    }
                    buildArgs += " -- /p:RuntimeOS=win10 /p:ArchiveTests=${archiveTests}"
                    bat ".\\build-tests.cmd ${buildArgs}"
                }
            }
            if (submitToHelix) {
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
                        if (params.TGroup == 'netcoreapp')
                        {
                            targetHelixQueues = ['Windows.10.Amd64.Open',
                                                'Windows.7.Amd64.Open',
                                                'Windows.81.Amd64.Open']
                            if (params.AGroup == 'x64') {
                                targetHelixQueues += ['Windows.10.Nano.Amd64.Open']
                            }
                        } else if (params.TGroup == 'uap') {
                            targetHelixQueues = ['Windows.10.Amd64.ClientRS2.Open']
                        }

                        bat "\"%VS140COMNTOOLS%\\VsDevCmd.bat\" && msbuild src\\upload-tests.proj /p:TargetGroup=${params.TGroup} /p:ArchGroup=${params.AGroup} /p:ConfigurationGroup=${params.CGroup} /p:TestProduct=corefx /p:TimeoutInSeconds=1200 /p:TargetOS=Windows_NT /p:HelixJobType=test/functional/cli/ /p:HelixSource=${helixSource} /p:BuildMoniker=${helixBuild} /p:HelixCreator=${helixCreator} /p:CloudDropAccountName=dotnetbuilddrops /p:CloudResultsAccountName=dotnetjobresults /p:CloudDropAccessToken=%CloudDropAccessToken% /p:CloudResultsAccessToken=%OutputCloudResultsAccessToken% /p:HelixApiEndpoint=https://helix.dot.net/api/2017-04-14/jobs /p:TargetQueues=\"${targetHelixQueues.join(',')}\" /p:HelixLogFolder= /p:HelixLogFolder=${WORKSPACE}\\${logFolder}\\ /p:HelixCorrelationInfoFileName=SubmittedHelixRuns.txt"

                        submittedHelixJson = readJSON file: "${logFolder}\\SubmittedHelixRuns.txt"
                    }
                }
            }
            officialBuildStep {
                stage('Push packages to Azure') {
                    withCredentials([string(credentialsId: 'CloudDropAccessToken', variable: 'CloudDropAccessToken')]) {
                        def containerName = getOfficialBuildAzureContainerName('corefx', params.OfficialBuildId)
                        println("containerName: ${containerName}")
                        bat "publish-packages.cmd -AzureAccount=dotnetbuilddrops -AzureToken=%CloudDropAccessToken% -Container=${containerName} -- /p:OverwriteOnPublish=false"
                    }
                }

                stage('Index symbol sources') {
                    def symbolAppVersion = "15.120.26810-rc4088158" 
                    withPackage("Symbol.App",
                                symbolAppVersion,
                                [[name: 'Artifact@Release', source: 'https://1essharedassets.pkgs.visualstudio.com/_packaging/Artifact@Release/nuget/v2', username: 'jenkins', credentialsId: 'chcosta-1esshared-packages']],
                                packagesDirectory) {
                        def symbolClientPath = "${WORKSPACE}\\packages\\symbol.app.${symbolAppVersion}\\lib\\net45\\Symbol.exe"
                        withCredentials([string(credentialsId: 'chcostaSymbolsAccessToken', variable: 'SymbolsAccessToken')]) {
                            def symbolServiceUri = 'https://devdiv.artifacts.visualstudio.com/defaultcollection'
                            def requestName = "DevDivjenkins/${params.OfficialBuildId}/${env.JOB_NAME}/${env.BUILD_NUMBER}"
                            def sourcePath = "${WORKSPACE}\\bin\\Windows_NT.${params.AGroup}.${params.CGroup}"
                            def expirationInDays = ''
                            def args = "publish --service '${symbolServiceUri}' --name '${requestName}' --directory '${sourcePath}' --patAuth '%SymbolsAccessToken%'"
                            if(expirationInDays != '') {
                            args += " --expirationInDays '${expirationInDays}'"
                            }
                            bat "${symbolClientPath} ${args}"
                        }
                    }
                }
            }
        }
        officialBuildStep(false) {
            stage ('Execute Tests') {
                def contextBase
                if (params.TestOuter) {
                    contextBase = "Win tests w/outer - ${params.TGroup} ${params.AGroup} ${params.CGroup}"
                }
                else {
                    contextBase = "Win tests - ${params.TGroup} ${params.AGroup} ${params.CGroup}"
                }
                waitForHelixRuns(submittedHelixJson, contextBase)
            }
        }
        officialBuildStep {
            sendEvent('WorkItemFinished',
                      [ExitCode: '0'],
                      sender)
        }
    }
    catch (Exception e) {
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