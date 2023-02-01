@GrabResolver(name = 'github', root = 'https://github.com/eficode/DevStack/raw/packages/repository/')
@Grab(group = 'com.eficode', module = 'devstack', version = '2.1.1-SNAPSHOT-groovy-3.0', classifier = "standalone")





import com.eficode.devstack.deployment.impl.JsmAndBitbucketH2Deployment

String dockerRemoteHost = "https://docker.domain.se:2376"
String dockerCertPath = "~/.docker/"

File projectRoot = new File("../").canonicalFile

//Using local Docker engine
//Make sure these DNS names resolves to 127.0.0.1
JsmAndBitbucketH2Deployment jsmAndBb = new JsmAndBitbucketH2Deployment("http://jira.local:8080", "http://bitbucket.local:7990")
//Using remote Docker Engine
//Make sure these DNS resolves
//JsmAndBitbucketH2Deployment jsmAndBb = new JsmAndBitbucketH2Deployment("http://jira.domain.se:8080", "http://bitbucket.domain.se:7990", dockerRemoteHost, dockerCertPath)


//Set JSM and Bitbucket ilicense that should be used
jsmAndBb.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license").text)
jsmAndBb.setBitbucketLicense(new File(projectRoot.path + "/resources/bitbucket/licenses/bitbucketLicense").text)

//Set the max ram per application, make sure your Docker engine is setup to support this
jsmAndBb.bitbucketContainer.setJvmMaxRam(4096)
jsmAndBb.jsmContainer.setJvmMaxRam(4096)

//Install JIRA App.
//ScriptRunner is needed for setting up application link between JIRA and Bitbucket
jsmAndBb.jiraAppsToInstall = ["https://marketplace.atlassian.com/download/apps/6820/version/1005740":new File(projectRoot.path + "/resources/jira/licenses/scriptrunnerForJira.license").text]

//Stop and remove if already existing
jsmAndBb.stopAndRemoveDeployment()

jsmAndBb.setupDeployment()