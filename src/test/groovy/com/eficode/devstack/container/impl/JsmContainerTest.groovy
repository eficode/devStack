package com.eficode.devstack.container.impl


import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.core.ClientException
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class JsmContainerTest extends Specification {

    @Shared
    static Logger log = LoggerFactory.getLogger(JsmContainerTest.class)

    @Shared
    DockerClientImpl dockerClient


    @Shared
    String dockerRemoteHost = "https://docker.domain.com:2376"
    @Shared
    String dockerCertPath = "src/test/resources/dockerCert"


    def setupSpec() {
        dockerClient = resolveDockerClient()
        dockerClient.rm("JSM-H2")
    }


    def "test setupSecureRemoteConnection"() {

        /**
         * Tests that a secure docker connection can be setup
         * Presumes dockerRemoteHost and dockerCertPath are valid and setup
         */
        when:
        JsmContainer jsm = new JsmContainer(dockerRemoteHost, dockerCertPath)

        then:
        assert jsm.ping(): "Error pinging docker engine"
        assert jsm.dockerClient.dockerClientConfig.scheme == "https"

    }



    def "test setupContainer"() {
        setup:
        log.info("Testing setup of JSM container using trait method")
        JsmContainer jsm = new JsmContainer(dockerRemoteHost, dockerCertPath)

        when:
        String containerId = jsm.createContainer()
        ContainerInspectResponse containerInspect =  dockerClient.inspectContainer(containerId).content


        then:
        assert containerInspect.name ==  "/" + jsm.containerName : "JSM was not given the expected name"
        assert containerInspect.state.status == ContainerState.Status.Created : "JSM Container status is of unexpected value"
        assert containerInspect.state.running == false : "JSM Container was started even though it should only have been created"
        assert dockerClient.inspectImage(containerInspect.image).content.repoTags.find {it == "atlassian/jira-servicemanagement:latest"} : "JSM container was created with incorrect Docker image"
        assert containerInspect.hostConfig.portBindings.containsKey("8080/tcp") : "JSM Container port binding was not setup correctly"
        log.info("\tJSM Container was setup correctly")

        cleanup:
        containerId ? dockerClient.rm(containerId) : ""


    }



    def "test createJsmContainer"() {
        setup:
        log.info("Testing setup of JSM container using dedicated JSM method")
        JsmContainer jsm = new JsmContainer(dockerRemoteHost, dockerCertPath)

        when:
        String containerId = jsm.createJsmContainer("Spoc-JSM", "atlassian/jira-servicemanagement", "4-ubuntu-jdk11", 10, "666")
        ContainerInspectResponse containerInspect =  dockerClient.inspectContainer(containerId).content


        then:
        assert containerInspect.name ==  "/Spoc-JSM" : "JSM was not given the expected name"
        assert containerInspect.state.status == ContainerState.Status.Created : "JSM Container status is of unexpected value"
        assert containerInspect.state.running == false : "JSM Container was started even though it should only have been created"
        assert containerInspect.hostConfig.portBindings.containsKey("666/tcp") : "JSM Container port binding was not setup correctly"
        assert dockerClient.inspectImage(containerInspect.image).content.repoTags.find {it == "atlassian/jira-servicemanagement:4-ubuntu-jdk11"} : "JSM container was created with incorrect Docker image"
        log.info("\tJSM Container was setup correctly")

        cleanup:
        containerId ? dockerClient.rm(containerId) : ""


    }



   def "test stopAndRemoveContainer"() {


       setup:
       log.info("Testing stop and removal of JSM container")
       JsmContainer jsm = new JsmContainer(dockerRemoteHost, dockerCertPath)

       when: "Setting up the container with the trait method"
       log.info("\tSetting up JSM container using trait method")
       String containerId = jsm.createContainer()

       then: "Removing it should return true"
       jsm.stopAndRemoveContainer()

       when: "Inspecting the deleted container"
       dockerClient.inspectContainer(containerId)

       then: "Exception should be thrown"
       ClientException ex = thrown(ClientException)
       assert ex.message.startsWith("Client error : 404 Not Found") : "Unexpected exception thrown when inspecting the deleted container"



       when: "Setting up the container with the trait method"
       log.info("\tSetting up JSM container using dedicated JSM method")
       String containerId2 = jsm.createJsmContainer()

       then: "Removing it should return true"
       jsm.stopAndRemoveContainer()

       when: "Inspecting the deleted container"
       dockerClient.inspectContainer(containerId2)

       then: "Exception should be thrown"
       ClientException ex2 = thrown(ClientException)
       assert ex2.message.startsWith("Client error : 404 Not Found") : "Unexpected exception thrown when inspecting the deleted container"

   }

    def "test startContainer, runBashCommandInContainer, copyFileToContainer and copyFilesFromContainer"() {

        setup:
        String containerSrcPath = "/opt/atlassian/jira/atlassian-jira/WEB-INF/classes/com/atlassian/jira/"
        String containerDstDir = "/var/atlassian/application-data/jira/"

        log.info("Testing copying files to and from JSM container")
        JsmContainer jsm = new JsmContainer(dockerRemoteHost, dockerCertPath)
        String containerId = jsm.createContainer()
        log.info("\tCreated container:" + containerId)

        Path tempDir = Files.createTempDirectory("testing-${this.class.simpleName}")
        log.info("\tCreated temp dir:" + containerId.toString())


        when: "Copying files from container path:"
        log.info("\tCopying files from container path:" + containerSrcPath)
        ArrayList<File>copiedFiles = jsm.copyFilesFromContainer(containerSrcPath, tempDir.toString() + "/")
        log.info("\tCopied ${copiedFiles.size()} files from container")

        then: "Several files and directories should have been copied"
        assert copiedFiles.size() : "No files where copied from container"
        assert copiedFiles.any {it.directory} : "No directories where copied from container"
        log.info("\tCopying files from container appears successful")

        when: "Copying a file to container"
        assert jsm.startContainer() : "Error starting container"

        File largestFile = copiedFiles.sort {it.size()}.last()
        String fileHash = largestFile.bytes.sha256()
        log.info("\tCopying file ($largestFile.name) to container path:" + containerDstDir + largestFile.name)
        log.debug("\t\tFile size:" + (largestFile.size() * 0.000001).round(1) + "MB")
        log.debug("\t\tFile hash:" + fileHash)

        then: "File should copy without error"
        jsm.copyFileToContainer(largestFile.path, containerDstDir)
        log.info("\tFinished copying file to container")

        when: "Running a hash in the container"
        log.info("Executing hash calculation of file in container")
        ArrayList<String> hashOutput = jsm.runBashCommandInContainer("sha256sum " + containerDstDir + largestFile.name)
        log.debug("\tContainer hash output:" + hashOutput)

        then: "The container hash and local hash should be identical"
        assert hashOutput.size() == 1 : "Expected one output row from remote bash command"
        assert hashOutput.first().contains(fileHash) : "Output from container does not contain the expected hash"
        assert hashOutput.first().contains(containerDstDir + largestFile.name) : "Output from container does not contain the expected file path"
        assert hashOutput == [ fileHash + "  " + containerDstDir + largestFile.name] : "Output from container is not formatted as expected"


        then:
        true

        cleanup:
        jsm.stopAndRemoveContainer() ? log.info("\tDeleted JSM container") : log.info("\tError deleting JSM container")
        log.info("\tDeleting temp dir:" + tempDir.toString())
        FileUtils.deleteDirectory(tempDir.toFile())
    }



    DockerClientImpl resolveDockerClient() {

        if (this.dockerClient) {
            return this.dockerClient
        }

        log.info("Getting Docker client")

        if (!dockerRemoteHost) {
            log.info("\tNo remote host configured, returning local docker connection")
            return new DockerClientImpl()
        }

        File certDir = new File(dockerCertPath)

        if (!certDir.isDirectory()) {
            log.info("\tNo valid Docker Cert Path given, returning local docker connection")
            return new DockerClientImpl()
        }
        log.info("\tLooking for docker certs in:" + certDir.absolutePath)
        ArrayList<File> pemFiles = FileUtils.listFiles(certDir, ["pem"] as String[], false)
        log.debug("\t\tFound pem files:" + pemFiles.name.join(","))


        if (!pemFiles.empty && pemFiles.every { pemFile -> ["ca.pem", "cert.pem", "key.pem"].find { it == pemFile.name } }) {
            log.info("\tFound Docker certs, returning Secure remote Docker connection")
            try {
                DockerClientImpl dockerClient = JsmContainer.setupSecureRemoteConnection(dockerRemoteHost, dockerCertPath)
                assert dockerClient.ping().content as String == "OK": "Error pinging remote Docker engine"
                return dockerClient
            } catch (ex) {
                log.error("\tError setting up connection to remote Docker engine:" + ex.message)
                log.info("\tReturning local Docker connection")
                return new DockerClientImpl()
            }

        }

        log.info("\tMissing Docker certs, returning local docker connection")

        return new DockerClientImpl()

    }
}
