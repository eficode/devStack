package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.atlassian.jiraInstanceManager.beans.MarketplaceApp
import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.remote.api.ContainerSummary
import org.slf4j.LoggerFactory
import spock.lang.Shared

class JsmDevDeploymentSpec extends DevStackSpec{

    @Shared
    File srLicenseFile = new File(System.getProperty("user.home") + "/.licenses/jira/sr.license")
    @Shared
    File jsmLicenseFile = new File(System.getProperty("user.home") +  "/.licenses/jira/jsm.license")



    def setupSpec() {


        DevStackSpec.log = LoggerFactory.getLogger(JsmDevDeploymentSpec.class)


        cleanupContainerNames = []
        cleanupContainerPorts = []

        disableCleanup = false


    }

    ArrayList<ContainerSummary> getContainers() {

        return dockerClient.ps(true).content
    }


    def "Test basics"(){


        setup:

        File localSrcDir = File.createTempDir()
        localSrcDir.deleteOnExit()

        String baseUrl = "http://spockdev.localhost"

        JiraInstanceManagerRest jim = new JiraInstanceManagerRest(baseUrl)


        when:
        JsmDevDeployment jsmDevDep= new JsmDevDeployment.Builder(baseUrl ,jsmLicenseFile.text, [localSrcDir.canonicalPath])
                .setJsmJvmDebugPort("5005")
                .setJsmVersion("latest")
                .enableJsmDood()
                .addAppToInstall(MarketplaceApp.getScriptRunnerVersion().downloadUrl, srLicenseFile.text)
                .build()

        jsmDevDep.stopAndRemoveDeployment()

        ArrayList<ContainerSummary> containersBefore = getContainers()

        jsmDevDep.setupDeployment()
        ArrayList<ContainerSummary> containersAfter = getContainers()


        then:
        containersAfter.size() - containersBefore.size() == 3


        when: "Creating a file in the local synced dir"
        File localTestFile = new File(localSrcDir, "testFile.groovy")
        localTestFile.text = System.currentTimeMillis()

        then: "It should be available in the container"
        jim.getScriptrunnerFile("testFile.groovy") == localTestFile.text

        expect:
        jsmDevDep.jsmContainer.appAppUploadEnabled
        jsmDevDep.jsmContainer.enableJvmDebug() == null


        cleanup:
        jsmDevDep.stopAndRemoveDeployment()



    }
}
