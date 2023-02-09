package com.eficode.devstack.examples

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.container.impl.GroovyContainer
import org.slf4j.LoggerFactory
import spock.lang.Shared

class ExamplesTest extends DevStackSpec{
    @Shared
    File examplesDir = new File("examples").canonicalFile
    @Shared
    File resourcesDir = new File("resources").canonicalFile
    @Shared
    File projectRoot = new File(".").canonicalFile
    @Shared
    File jsmLicenseFile = new File(projectRoot.path + "/resources/jira/licenses/jsm.license")
    @Shared
    File bitbucketLicenseFile = new File(projectRoot.path + "/resources/bitbucket/licenses/bitbucketLicense")
    @Shared
    File srLicenseFile = new File(projectRoot.path + "/resources/jira/licenses/scriptrunnerForJira.license")


    def setupSpec() {
        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"
        dockerCertPath = ""
        dockerRemoteHost = ""


        log = LoggerFactory.getLogger(ExamplesTest.class)

        cleanupContainerNames = ["groovy-container", "jira.local", "bitbucket.local"]
        cleanupContainerPorts = [8080,7990]

        disableCleanup = false

        assert examplesDir.exists()
        assert resourcesDir.exists()
        assert jsmLicenseFile.canRead() && jsmLicenseFile.exists()
        assert srLicenseFile.canRead() && srLicenseFile.exists()
        assert bitbucketLicenseFile.canRead() && bitbucketLicenseFile.exists()

    }


    def "Test Basic JSM setup example"() {

        setup:
        File scriptFile = new File(examplesDir, "Basic JSM Setup.groovy")
        assert scriptFile.exists()


        GroovyContainer groovyContainer = new GroovyContainer(dockerRemoteHost, dockerCertPath)

        groovyContainer.prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock") //Mount docker socket from host
        groovyContainer.containerDefaultNetworks = ["jsm"] // Make sure groovy container is on the same network as other containers
        groovyContainer.setGroovyVersion("3.0.14")
        groovyContainer.stopAndRemoveContainer()
        groovyContainer.createSleepyContainer()
        groovyContainer.startContainer()


        groovyContainer.runBashCommandInContainer("mkdir -p /home/resources", 30, "root") //Create dir where example script expects licenses etc
        groovyContainer.copyFileToContainer(resourcesDir.canonicalPath, "/home/resources/") //Copy over application licenses etc




        when:
        String scriptText = scriptFile.text
        scriptText = replaceVariableValue(scriptText, "jiraBaseUrl", "\"http://jira.local:8080\"")
        scriptText = replaceVariableValue(scriptText, "jsmLicense", "\"\"\"${jsmLicenseFile.text}\"\"\"")
        ArrayList<String> scriptOutput = groovyContainer.runScriptInContainer(scriptText,"-Dorg.slf4j.simpleLogger.defaultLogLevel=trace", "",600, "root" )

        then:
        !scriptOutput.any {it.contains("unable to resolve class")}
        scriptOutput.any {it.contains("JSM deployment finished, you should now be able to login")}

        cleanup:
        groovyContainer.stopAndRemoveContainer()

    }

    def "Test JSM And Bitbucket Example"() {

        setup:
        File scriptFile = new File(examplesDir, "Basic JSM and Bitbucket setup.groovy")
        assert scriptFile.exists()


        GroovyContainer groovyContainer = new GroovyContainer(dockerRemoteHost, dockerCertPath)

        groovyContainer.prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock") //Mount docker socket from host
        groovyContainer.containerDefaultNetworks = ["jsm_and_bitbucket"] // Make sure groovy container is on the same network as other containers
        groovyContainer.setGroovyVersion("3.0.14")
        groovyContainer.stopAndRemoveContainer()
        groovyContainer.createSleepyContainer()
        groovyContainer.startContainer()


        groovyContainer.runBashCommandInContainer("mkdir -p /home/resources", 30, "root") //Create dir where example script expects licenses etc
        groovyContainer.copyFileToContainer(resourcesDir.canonicalPath, "/home/resources/") //Copy over application licenses etc


        when:
        String scriptText = scriptFile.text
        scriptText = replaceVariableValue(scriptText, "jiraBaseUrl", "\"http://jira.local:8080\"")
        scriptText = replaceVariableValue(scriptText, "bbBaseUrl", "\"http://bitbucket.local:7990\"")
        scriptText = replaceVariableValue(scriptText, "jsmLicense", "\"\"\"${jsmLicenseFile.text}\"\"\"")
        scriptText = replaceVariableValue(scriptText, "scriptRunnerLicense", "\"\"\"${srLicenseFile.text}\"\"\"")
        scriptText = replaceVariableValue(scriptText, "bbLicense", "\"\"\"${bitbucketLicenseFile.text}\"\"\"")


        ArrayList<String> scriptOutput = groovyContainer.runScriptInContainer(scriptText,"-Dorg.slf4j.simpleLogger.defaultLogLevel=trace", "",600, "root" )

        then:
        !scriptOutput.any {it.contains("unable to resolve class")}
        scriptOutput.any {it.contains("Bitbucket deployment finished successfully:true")}
        scriptOutput.any {it.contains("JSM deployment finished successfully:true")}
        scriptOutput.any {it.contains("Finished setting up application link")}

        cleanup:
        groovyContainer.stopAndRemoveContainer()

    }
}
