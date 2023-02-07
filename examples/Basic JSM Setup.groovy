@GrabResolver(name = 'github', root = 'https://github.com/eficode/DevStack/raw/packages/repository/')
@Grab(group = 'com.eficode', module = 'devstack', version = '2.2.0-SNAPSHOT-groovy-3.0.14', classifier = "standalone")
@Grab(group='org.slf4j', module='slf4j-simple', version='1.7.36', scope='test')
@GrabConfig(systemClassLoader=true, initContextClassLoader=true)


import com.eficode.devstack.deployment.impl.JsmH2Deployment


String dockerRemoteHost = "https://docker.domain.se:2376"
String dockerCertPath = "~/.docker/"


JsmH2Deployment jsmDep = new JsmH2Deployment("http://localhost:8080") //If using a local docker engine
//JsmH2Deployment jsmDep = new JsmH2Deployment("http://jira.domain.se:8080", dockerRemoteHost, dockerCertPath) //If using a remote docker Engine

File projectRoot = new File("../").canonicalFile

//Set JSM license that should be used
jsmDep.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license"))

//Optional settings
//jsmDep.jsmContainer.containerImageTag = "5.5.1" //Set docker image (and JSM) version
//jsmDep.jsmContainer.containerName = "Testing" //Set custom container name
//jsmDep.jsmContainer.jvmMaxRam = 4000 //Set max ram used by JSM
//jsmDep.appsToInstall = ["https://marketplace.atlassian.com/download/apps/6820/version/1005740":new File(projectRoot.path + "/resources/jira/licenses/scriptrunnerForJira.license").text]

//Stop and remove if already existing
jsmDep.stopAndRemoveDeployment()

jsmDep.setupDeployment()