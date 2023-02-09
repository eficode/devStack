@GrabResolver(name = 'github', root = 'https://github.com/eficode/DevStack/raw/packages/repository/')
@Grab(group = 'com.eficode', module = 'devstack', version = '2.2.0-SNAPSHOT-groovy-3.0.14', classifier = "standalone")
@Grab(group='org.slf4j', module='slf4j-simple', version='1.7.36', scope='test')
@GrabConfig(systemClassLoader=true, initContextClassLoader=true)



import com.eficode.devstack.deployment.impl.JsmAndBitbucketH2Deployment

String dockerRemoteHost = "https://docker.domain.se:2376"
String dockerCertPath = "~/.docker/"

//Input licences
String jsmLicense = """LICENSE GOES HERE"""
String scriptRunnerLicense = """LICENSE GOES HERE"""
String bbLicense = """LICENSE GOES HERE"""

//Set application base urls
String jiraBaseUrl = "http://jira.localhost:8080"
String bbBaseUrl = "http://bitbucket.localhost:7990"

//Using local Docker engine
//Make sure these DNS names resolves to 127.0.0.1
JsmAndBitbucketH2Deployment jsmAndBb = new JsmAndBitbucketH2Deployment(jiraBaseUrl, bbBaseUrl)
//Using remote Docker Engine
//Make sure these DNS resolves
//JsmAndBitbucketH2Deployment jsmAndBb = new JsmAndBitbucketH2Deployment("http://jira.domain.se:8080", "http://bitbucket.domain.se:7990", dockerRemoteHost, dockerCertPath)


//Set JSM and Bitbucket license that should be used
jsmAndBb.setJiraLicense(jsmLicense)
jsmAndBb.setBitbucketLicense(bbLicense)

//Set the max ram per application, make sure your Docker engine is setup to support this
jsmAndBb.bitbucketContainer.setJvmMaxRam(4096)
jsmAndBb.jsmContainer.setJvmMaxRam(4096)

//Install JIRA App.
//ScriptRunner is needed for setting up application link between JIRA and Bitbucket
jsmAndBb.jiraAppsToInstall = ["https://marketplace.atlassian.com/download/apps/6820/version/1005740":scriptRunnerLicense]

//Stop and remove if already existing
jsmAndBb.stopAndRemoveDeployment()

jsmAndBb.setupDeployment()