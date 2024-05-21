package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.jira.remotespock.beans.responses.SpockOutputType
import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.atlassian.jiraInstanceManager.beans.MarketplaceApp
import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.remote.api.ContainerSummary
import org.slf4j.LoggerFactory
import spock.lang.Shared

class JsmDevDeploymentSpec extends DevStackSpec {

    @Shared
    File srLicenseFile = new File(System.getProperty("user.home") + "/.licenses/jira/sr.license")
    @Shared
    File jsmLicenseFile = new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license")


    def setupSpec() {


        DevStackSpec.log = LoggerFactory.getLogger(JsmDevDeploymentSpec.class)


        cleanupContainerNames = []
        cleanupContainerPorts = []

        disableCleanup = false


    }

    ArrayList<ContainerSummary> getContainers() {

        return dockerClient.ps(true).content
    }


    String getSuccessfulSpockBody() {
        return """
        package com.eficode.atlassian.jira.jiraLocalScripts
        
        
        import org.slf4j.Logger
        import org.slf4j.LoggerFactory
        import spock.lang.Shared
        import spock.lang.Specification
        
        class JiraLocalSpockTest extends Specification {
        
            @Shared
            static Logger log = LoggerFactory.getLogger(JiraLocalSpockTest.class)
        
        
            def "A successful test in JiraLocalSpockTest"() {
        
                setup:
        
                log.warn("Running spock test:" + this.specificationContext.currentIteration.name)
                expect:
                true
        
                cleanup:
                log.warn("\\tTest finished with exception:" + \$spock_feature_throwable)
        
            }
        
        
        
        }
        """.stripIndent()
    }

    def "Test basics"() {


        setup:

        File localSrcDir = File.createTempDir()
        localSrcDir.deleteOnExit()

        String hostName = "spockdev.localhost"
        String baseUrl = "http://$hostName"

        JiraInstanceManagerRest jim = new JiraInstanceManagerRest(baseUrl)


        when:
        JsmDevDeployment jsmDevDep = new JsmDevDeployment.Builder(baseUrl, jsmLicenseFile.text, [localSrcDir.canonicalPath])
                .setJsmJvmDebugPort("5005")
                .setJsmVersion("latest")
                .enableJsmDood()
                .addAppToInstall(MarketplaceApp.getScriptRunnerVersion().downloadUrl, srLicenseFile.text)
                .build()



        jsmDevDep.setupDeployment()
        ArrayList<ContainerSummary> containersAfter = getContainers()


        then:
        containersAfter.find {it.names.toString().contains("spockdev.localhost")}
        containersAfter.find {it.names.toString().contains("spockdev.localhost-reporter")}
        containersAfter.find {it.names.toString().contains("ReportSyncer")}
        containersAfter.find {it.names.toString().contains("SrcSyncer")}


        when: "Creating a file in the local synced dir"

        File localTestFile =  new File(localSrcDir, "com/eficode/atlassian/jira/jiraLocalScripts/JiraLocalSpockTest.groovy")
        localTestFile.createParentDirectories()
        localTestFile.text = getSuccessfulSpockBody()
        sleep(2000)

        then: "It should be available in the container"
        jim.getScriptrunnerFile("com/eficode/atlassian/jira/jiraLocalScripts/JiraLocalSpockTest.groovy") == getSuccessfulSpockBody()

        expect:
        jsmDevDep.jsmContainer.appAppUploadEnabled
        jsmDevDep.jsmContainer.enableJvmDebug() == null
        assert jim.isSpockEndpointDeployed(true) : "Spock endpoint was not deployed"

        when:"Running spocktest"
        jim.runSpockTest("com.eficode.atlassian.jira.jiraLocalScripts.JiraLocalSpockTest", "", SpockOutputType.AllureReport, "allure-results/")
        sleep(2000)
        then:
        jsmDevDep.allureContainer.runBashCommandInContainer("ls -l /app/allure-results/")
        assert jsmDevDep.jsmContainer.runBashCommandInContainer("ls allure-results").first().split("\n").every { reportName ->
            jsmDevDep.allureContainer.runBashCommandInContainer("ls /app/allure-results/$reportName && echo Status: \$?").toString().contains("Status: 0")
        } : "All Reports in JIRA container haven't been synced to Allure container"


        cleanup:
        jsmDevDep.stopAndRemoveDeployment()



    }
}
