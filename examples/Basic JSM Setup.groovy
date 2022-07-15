import com.eficode.devstack.deployment.impl.JsmH2Deployment

String jiraBaseUrl = "http://localhost:8080"
JsmH2Deployment jsmDep = new JsmH2Deployment(jiraBaseUrl)
File projectRoot = new File(".")
jsmDep.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license"))
jsmDep.setupDeployment()