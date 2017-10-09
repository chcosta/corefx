@Library(['dotnet-ci']) _

build(params, env)

def build(params, env)
{
    library "buildtools@${params.BuildToolsSharedLibraryBranchOrCommit}"
    
    def helixEndpointCredential = 'helixStagingEndpointWithEntity'
    def sender
    officialBuildStep {
        sender = createSenderMap(helixEndpointCredential)
        sendEvent('WorkItemQueued', 
                    [WorkItemFriendlyName: "Orchestration"],
                    sender)
    }
    try {
        // ToDo: unify feed sources - https://github.com/dotnet/core-eng/issues/1858
        def pipelineBuildToolsVersion = '2.0.0-servicing-02104-06'
        def pipelineBuildToolsSource = 'https://dotnet.myget.org/F/dotnet-buildtools/api/v3/index.json'
        def pipelineBuildToolsCloudTestVersion = '1.0.0-prerelease-02001-01'
        def pipelineOptionalToolsSource = 'https://dotnet.myget.org/F/dotnet-core-dev-eng/api/v3/index.json'
        simpleNode('Windows_NT','latest') {
            echo "Official build number: ${params.OfficialBuildId}"
            stage ('Checkout source') {
                checkout scm
            }      
            officialBuildStep {
                sendEvent('JobStarted', 
                            [Creator: env.USERNAME, 
                            QueueId: "Build", 
                            Source: "official/corefx/jenkins/${getBranch()}/", 
                            JobType: "build/publish/", 
                            Build: params.OfficialBuildId.replace('-', '.'),
                            Properties: [ConfigurationGroup: 'Publish',
                                        TaskName: 'Package Publish']],
                            sender)      
                sendEvent('WorkItemStarted', 
                            [WorkItemFriendlyName: "Orchestration"],
                            sender)
                sendEvent('VsoBuildInformation', 
                            [BuildNumber: params.OfficialBuildId, Uri: env.BUILD_URL, LogUri: env.BUILD_URL],
                            sender)
                sendEvent('ExternalLink', 
                            [Uri: env.BUILD_URL],
                            sender)
                sendEvent('ChildJobCreated', 
                            [ChildId: sender.correlationId],
                            createSenderMap(sender.credentialsIdentifier, params.CorrelationId))
            }

            officialBuildStep {
                println ("CorrelationId: ${params.CorrelationId}")

                def downloadedArtifactsDirectory = "${WORKSPACE}\\AzurePackages"
                def packagesDirectory = "${WORKSPACE}\\packages"
                def nuGetConfigsRootFolder = "${WORKSPACE}\\ciNuGet"

                stage ('Download packages from Azure') {
                    withCredentials([string(credentialsId: 'CloudDropAccessToken', variable: 'CloudDropAccessToken')]) {
                        def containerName = getOfficialBuildAzureContainerName('corefx', params.OfficialBuildId)
                        retry (3) {
                            withPackage("Microsoft.DotNet.Build.CloudTest",
                                        pipelineBuildToolsCloudTestVersion, 
                                        pipelineBuildToolsSource, 
                                        packagesDirectory) {
                                    def buildToolsRoot = "${packagesDirectory}\\Microsoft.DotNet.Build.CloudTest.${pipelineBuildToolsCloudTestVersion}\\build"
                                runDotNet("msbuild ${buildToolsRoot}\\SyncCloudContent.targets /t:DownloadBlobsFromAzureTargets /p:CloudDropAccountName=dotnetbuilddrops /p:CloudDropAccessToken=%CloudDropAccessToken% /p:DownloadDirectory=${downloadedArtifactsDirectory} /p:ContainerName=${containerName}")
                            }
                        }
                    }
                }
                def symbolPackageDirectory="${downloadedArtifactsDirectory}\\Release\\symbols"
                def indexedSymbolPackagesDirectory = "${WORKSPACE}\\IndexedSymbolPackages"
                stage ('Inject signed symbols catalogs') {
                    retry (3) {
                        withPackage("Microsoft.DotNet.Build.Tasks.Symbols",
                                    pipelineBuildToolsVersion,
                                    pipelineBuildToolsSource,
                                    packagesDirectory) {
                            def makeCatVersion = '1.0.0-prerelease-000001'
                            withPackage('Microsoft.DotNet.BuildTools.Jenkins.MakeCat', 
                                        makeCatVersion, 
                                        [[name: 'vsts', source: 'https://devdiv.pkgs.visualstudio.com/_packaging/dotnet-core-internal-tooling/nuget/v2', username: 'jenkins', credentialsId: 'chcosta-vsts-packages']],
                                        packagesDirectory) {
                                withEnv( ["PATH=${env.PATH};${packagesDirectory}\\Microsoft.DotNet.BuildTools.Jenkins.MakeCat.${makeCatVersion}\\lib\\net46"] ) {
                                    def buildToolsRoot = "${packagesDirectory}\\Microsoft.DotNet.Build.Tasks.Symbols.${pipelineBuildToolsVersion}"
                                    runDotNet("msbuild ${buildToolsRoot}\\build\\Symbols.targets /t:InjectSignedSymbolCatalogIntoSymbolPackages /p:BuildToolsTaskDir=${buildToolsRoot}\\lib\\netstandard1.5\\ /p:SymbolPackagesToPublishGlob=${symbolPackageDirectory}\\*.nupkg /p:SymbolCatalogCertificateId=400")
                                }
                            }
                        }
                    }
                }
                stage ('Index symbol packages') {
                    retry (3) {
                        def embedIndexVersion = "1.0.0-prerelease-000032"
                        withPackage("EmbedIndex", 
                                    embedIndexVersion, 
                                    [[name: 'vsts', source: 'https://devdiv.pkgs.visualstudio.com/_packaging/dotnet-core-internal-tooling/nuget/v2', username: 'jenkins', credentialsId: 'chcosta-vsts-packages']],
                                    packagesDirectory) {
                            def embedIndexPath = "${packagesDirectory}\\EmbedIndex.${embedIndexVersion}\\tools\\EmbedIndex.dll"
                            runDotNet("${embedIndexPath} ${symbolPackageDirectory} ${indexedSymbolPackagesDirectory}")
                        }
                    }
                }
                stage ('Push packages to MyGet') {
                    withPackage("Microsoft.DotNet.Build.Tasks.PublishProduct",
                                pipelineBuildToolsVersion,
                                pipelineBuildToolsSource,
                                packagesDirectory) {
                        withCredentials([string(credentialsId: 'chcosta-myget-apikey', variable: 'MyGetPublishApiKey')]) {
                            def buildToolsRoot = "${packagesDirectory}\\Microsoft.DotNet.Build.Tasks.PublishProduct.${pipelineBuildToolsVersion}"
                            def nuGetPath = getNuGet()
                            runDotNet("msbuild ${buildToolsRoot}\\build\\PublishProduct.targets /t:NuGetPush /p:BuildToolsTaskDir=${buildToolsRoot}\\lib\\netstandard1.5\\ /p:NuGetExePath=${nuGetPath} /p:NuGetApiKey=%MyGetPublishApiKey% /p:NuGetSource=https://dotnet.myget.org/F/dotnet-core-dev-eng/api/v2/package /p:PackagesGlob=${downloadedArtifactsDirectory}\\Release\\*.nupkg")
                        }
                    }
                }
                stage ('Push symbols packages to MyGet') {
                    withCredentials([string(credentialsId: 'chcosta-myget-apikey', variable: 'MyGetPublishApiKey')]) {
                        echo "Not yet implemented"
                    }
                }
                stage ('Update versions repository') {
                    withPackage("Microsoft.DotNet.Build.Tasks.VersionTools",
                                pipelineBuildToolsVersion,
                                pipelineBuildToolsSource,
                                packagesDirectory) {                
                        withCredentials([string(credentialsId: 'AccessToken-dotnet-build-bot-public-repo', variable: 'VersionsRepoApiKey')]) {
                            def buildToolsRoot = "${packagesDirectory}\\Microsoft.DotNet.Build.Tasks.VersionTools.${pipelineBuildToolsVersion}"
                            runDotNet("${buildToolsRoot}\\build\\VersionTools.targets /t:UpdatePublishedVersions /p:BuildToolsTaskDir=${buildToolsRoot}\\lib\\netstandard1.5\\ /p:GitHubUser=dotnet-build-bot /p:GitHubEmail=dotnet-build-bot@microsoft.com /p:GitHubAuthToken=%VersionsRepoApiKey% /p:VersionsRepoOwner=dotnet /p:VersionsRepo=versions /p:VersionsRepoPath=build-info/dotnet/corefx/jenkins/master /p:ShippedNuGetPackageGlobPath=${downloadedArtifactsDirectory}\\Release\\*.nupkg")
                        }
                    }
                }
            }
        }
        officialBuildStep {
            sendEvent('VsoBuildWarningsAndErrors',
                      [WarningCount: '0', ErrorCount: '0'],
                      sender)
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
