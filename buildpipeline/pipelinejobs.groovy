def BuildToolsSharedLibraryBranchOrCommit = 'jenkins'

// This file is used both for job creation, and to define the official build pipeline.
// If 'executePipeline' is true, then we are in the official build pipeline context, 
// if 'executePipeline' is false, then we are in the job generation context.  We used
// the same file so that we can utilize the same configurations to define generation 
// build pipelines
def executePipeline = false
try {
    executePipeline = env.OfficialBuild == 'true'
} catch(Exception ex) {}

// If we're running in the context of a Pipeline, then we're not expecting these classes to load
def JobReportLibrary
def Utilities
def centos6Pipeline
def linPipeline
def osxPipeline
def winPipeline
def publishPipeline
def officialBuildPipeline
def project
def Pipeline
def buildNumber
def sender
def dotnetciLib
def pipelineMap = [:]

// PR jobs occur on external servers, official builds occur on the
// internal server.  If we're on an internal server, then we don't want
// to schedule jobs for every PR / push / etc... like we would for
// external server jobs.
def isInternalServer = false


// The input branch name (e.g. master)
def branch = BranchName

// Job generation variables
if(!executePipeline) {
    PipelineLibrary = org.dotnet.ci.pipelines.Pipeline
    JobReportLibrary = jobs.generation.JobReport
    Utilities = jobs.generation.Utilities

    // Official builds don't define "VersionControlLocation", but "generator" jobs do
    isInternalServer = (VersionControlLocation == 'VSTS')

    // The input project name (e.g. dotnet/corefx)
    project = QualifiedRepoName
    
    // **************************
    // Define innerloop testing. Any configuration in ForPR will run for every PR but all other configurations
    // will have a trigger that can be
    // **************************

    centos6Pipeline = Pipeline.createPipeline(this, 'buildpipeline/centos.6.groovy')
    linPipeline = PipelineLibrary.createPipeline(this, 'buildpipeline/linux.groovy')
    osxPipeline = PipelineLibrary.createPipeline(this, 'buildpipeline/osx.groovy')
    winPipeline = PipelineLibrary.createPipeline(this, 'buildpipeline/windows.groovy')
    publishPipeline = PipelineLibrary.createPipeline(this, 'buildpipeline/publish.groovy')
    officialBuildPipeline = PipelineLibrary.createPipeline(this, 'buildpipeline/pipelinejobs.groovy')
}
// Build pipeline variables
else {
    dotnetciLib = library 'dotnet-ci'
    PipelineLibrary = dotnetciLib.org.dotnet.ci.pipelines.Pipeline
    buildNumber = getBuildNumber()
    println ("Official Build Number: ${buildNumber}")

    // We have to be able to serialize objects used in steps, so we pass around the map defining the HelixEventSender rather than the object itself
    sender = createSenderMap('helixStagingEndpointWithEntity')
}

def allArchGroups = ['x64', 'x86', 'arm', 'arm64']

def configurations = [
    ['TGroup':"netcoreapp", 'Pipeline':linPipeline, 'Name':'Linux' ,'ForPR':"Release-x64", 'Arch':['x64', 'arm'], 'PipelineArch':['x64', 'arm']],
    ['TGroup':"netcoreapp", 'Pipeline':centos6Pipeline, 'Name':'CentOS.6' ,'ForPR':"", 'Arch':['x64']],
    ['TGroup':"netcoreapp", 'Pipeline':osxPipeline, 'Name':'OSX', 'ForPR':"Debug-x64", 'Arch':['x64'], 'PipelineArch': ['x64']],
    ['TGroup':"netcoreapp", 'Pipeline':winPipeline, 'Name':'Windows' , 'ForPR':"Debug-x64|Release-x86", 'PipelineArch': ['arm', 'x64', 'x86']],
    ['TGroup':"netfx",      'Pipeline':winPipeline, 'Name':'NETFX', 'ForPR':"Release-x86", 'PipelineArch': ['arm', 'x64', 'x86']],
    ['TGroup':"uap",        'Pipeline':winPipeline, 'Name':'UWP CoreCLR', 'ForPR':"Debug-x64", 'PipelineArch': ['arm', 'x64', 'x86']],
    ['TGroup':"uapaot",     'Pipeline':winPipeline, 'Name':'UWP NETNative', 'ForPR':"Release-x86", 'PipelineArch': ['arm', 'x64', 'x86']],
    ['TGroup':"all",        'Pipeline':winPipeline, 'Name':'Packaging All Configurations', 'ForPR':"Debug-x64", 'PipelineArch': ['x64']]
]

configurations.each { config ->
    ['Debug', 'Release'].each { configurationGroup ->
        (config.Arch ?: allArchGroups).each { archGroup ->
            def triggerName = "${config.Name} ${archGroup} ${configurationGroup} Build"
            def pipeline = config.Pipeline
            def params = ['TGroup':config.TGroup,
                        'CGroup':configurationGroup,
                        'AGroup':archGroup,
                        'TestOuter': false,
                        'OfficialBuildId': 'none',
                        'CorrelationId': 'none',
                        'BuildToolsSharedLibraryBranchOrCommit': BuildToolsSharedLibraryBranchOrCommit
                        ]
            def baseJobName = "windows"
            if(config.Name == "Linux") {
                baseJobName = "linux"
            }
            else if(config.Name == "OSX") {
                baseJobName = "osx"
            }
                        
            def jobName = "${baseJobName}-${config.TGroup}_${configurationGroup}_${archGroup}"
            
            // Use the configurations to add triggers during job generation
            if(!executePipeline) {
                // Add default PR triggers for particular configurations but manual triggers for all
                if (config.ForPR.contains("${configurationGroup}-${archGroup}") && !isInternalServer) {
                    pipeline.triggerPipelineOnEveryPR(triggerName, params, jobName)
                }
                else if(!isInternalServer) {
                    pipeline.triggerPipelineOnGithubPRComment(triggerName, params, jobName)
                }

                // Add trigger for all configurations to run on merge
                pipeline.triggerPipelineManually(params, jobName)

                // Add optional PR trigger for Outerloop test runs
                params.TestOuter = true
                if(!isInternalServer) {                
                    pipeline.triggerPipelineOnGithubPRComment("Outerloop ${triggerName}", params, jobName)
                }
            } else {
                // Use the configurations to define the builds which are part of our build Pipeline 
                if(config.PipelineArch.contains(archGroup)) {
                    pipelineMap.put(jobName, { build job: jobName, 
                                               parameters:[[$class: 'StringParameterValue', name: 'OfficialBuildId', value: buildNumber], 
                                                           [$class: 'StringParameterValue', name: 'CorrelationId', value: sender.correlationId]]})
                }
            }
}}}

def publishJobName = "publish"
// Job generation
if(!executePipeline) {
    // Generate publish job
    def publishParams = ['OfficialBuildId': 'none', 'CorrelationId': 'none', 'BuildToolsSharedLibraryBranchOrCommit': BuildToolsSharedLibraryBranchOrCommit]
    if(!isInternalServer) {
        publishPipeline.triggerPipelineOnGithubPRComment('Publish', publishParams, publishJobName)
    }
    publishPipeline.triggerPipelineManually(publishParams, publishJobName)

    // Generate official build job
    def officialBuildParams = ['OfficialBuild': 'true', 'BranchName': branch, 'BuildToolsSharedLibraryBranchOrCommit': 'jenkins']
    officialBuildPipeline.triggerPipelineManually(officialBuildParams, 'OfficialBuild')

    // Generate job report    
    JobReportLibrary.Report.generateJobReport(out)
    
    if(!isInternalServer) {
        // Make the call to generate the help job
        Utilities.createHelperJob(this, project, branch,
            "Welcome to the ${project} Repository",  // This is prepended to the help message
            "Have a nice day!")  // This is appended to the help message.  You might put known issues here.
    }
// Build Pipeline
} else {
    // Official build Pipeline
    node {
        // ToDo: make formattedBuildNumber a utility rather than calculating it everywhere
        def formattedBuildNumber = buildNumber.replace('-', '.')
        stage ('Send JobStarted telemetry') {
            sendEvent('WorkItemQueued', 
                      [WorkItemFriendlyName: "Orchestrator"],
                      sender)
            sendEvent('JobStarted', 
                      [Creator: "chcosta", QueueId: "Build", Source: "official/corefx/jenkins/${BranchName}/", JobType: "build/orchestration/", Build: formattedBuildNumber],
                      sender)
            sendEvent('WorkItemStarted',
                      [WorkItemFriendlyName: "Orchestrator"],
                      sender)
        }
        try {
            stage('Build') {
                parallel (pipelineMap)
            }
            stage('Publish') {
                build job: publishJobName, parameters:[[$class: 'StringParameterValue', name: 'OfficialBuildId', value: buildNumber], 
                                                       [$class: 'StringParameterValue', name: 'CorrelationId', value: sender.correlationId]] 
            }
            sendEvent('WorkItemFinished', 
                      [ExitCode: '0'],
                      sender)
        }
        catch (Exception e) {
            sendEvent('WorkItemFinished', 
                      [ExitCode: '1'],
                      sender)
            throw e

        }
        finally {
        }
    }
}
