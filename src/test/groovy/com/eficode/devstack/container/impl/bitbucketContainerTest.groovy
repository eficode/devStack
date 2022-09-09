package com.eficode.devstack.container.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
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

class bitbucketContainerTest extends Specification {

    @Shared
    static Logger log = LoggerFactory.getLogger(bitbucketContainerTest.class)

    @Shared
    DockerClientImpl dockerClient


    @Shared
    String dockerRemoteHost = "https://docker.domain.se:2376"
    @Shared
    String dockerCertPath = "resources/dockerCert"
    @Shared
    String bitbucketBaseUrl = "http://bitbucket.domain.se:7990"


    def setupSpec() {
        dockerClient = resolveDockerClient()
        dockerClient.stop("Bitbucket")
        dockerClient.rm("Bitbucket")
    }



    def "sandbox"() {

        setup:
        dockerClient.manageImage.build()
    }

    def "test setupContainer"() {
        setup:
        log.info("Testing setup of BB container using trait method")
        BitbucketContainer bbc = new BitbucketContainer(dockerRemoteHost, dockerCertPath)
        BitbucketInstanceManagerRest bbr = new BitbucketInstanceManagerRest(bitbucketBaseUrl)

        bbc.jvmMaxRam = 4096

        when:
        String containerId = bbc.createContainer()
        ContainerInspectResponse containerInspect =  dockerClient.inspectContainer(containerId).content


        then:
        assert containerInspect.name ==  "/" + bbc.containerName : "BB was not given the expected name"
        assert containerInspect.state.status == ContainerState.Status.Created : "BB Container status is of unexpected value"
        assert containerInspect.state.running == false : "BB Container was started even though it should only have been created"
        assert dockerClient.inspectImage(containerInspect.image).content.repoTags.find {it == "atlassian/bitbucket:latest"} : "BB container was created with incorrect Docker image"
        assert containerInspect.hostConfig.portBindings.containsKey("7990/tcp") : "BB Container port binding was not setup correctly"
        log.info("\tBB Container was setup correctly")

        expect:
        bbc.startContainer()
        bbr.setApplicationProperties(new File("resources/bitbucket/licenses/bitbucketLicense").text)
        bbr.status == "RUNNING"



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
                DockerClientImpl dockerClient = setupSecureRemoteConnection(dockerRemoteHost, dockerCertPath)
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

    /**
     * Replaced the default docker connection (local) with a remote, secure one
     * @param host ex: "https://docker.domain.se:2376"
     * @param certPath folder containing ca.pem, cert.pem, key.pem
     */
    static DockerClientImpl setupSecureRemoteConnection(String host, String certPath) {

        DockerClientConfig dockerConfig = new DockerClientConfig(host)
        DockerEnv dockerEnv = new DockerEnv(host)
        dockerEnv.setCertPath(certPath)
        dockerEnv.setTlsVerify("1")
        dockerConfig.apply(dockerEnv)

        return new DockerClientImpl(dockerConfig)

    }
}
