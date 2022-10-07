package com.eficode.devstack.container.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.ContainerState
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class BitbucketContainerTest extends DevStackSpec {

    @Shared
    static Logger log = LoggerFactory.getLogger(BitbucketContainerTest.class)

    @Shared
    String bitbucketBaseUrl = "http://bitbucket.domain.se:7990"



    def setupSpec() {
        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"

        dockerClient = resolveDockerClient()

        log = LoggerFactory.getLogger(BitbucketContainerTest.class)

        dockerClient = resolveDockerClient()

        containerNames = ["bitbucket.domain.se"]
        containerPorts = [7990]

        disableCleanupAfter = false

    }



    def "test setupContainer"() {
        setup:
        log.info("Testing setup of BB container using trait method")
        BitbucketContainer bbc = new BitbucketContainer(bitbucketBaseUrl, dockerRemoteHost, dockerCertPath)
        bbc.containerName = bbc.extractDomainFromUrl(bitbucketBaseUrl)
        BitbucketInstanceManagerRest bbr = new BitbucketInstanceManagerRest(bitbucketBaseUrl)


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


}
