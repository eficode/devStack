package com.eficode.devstack.container.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.ContainerState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared

class BitbucketContainerTest extends DevStackSpec {

    @Shared
    static Logger log = LoggerFactory.getLogger(BitbucketContainerTest.class)


    def setupSpec() {
        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"


        log = LoggerFactory.getLogger(BitbucketContainerTest.class)

        cleanupContainerNames = ["bitbucket.domain.se", "localhost"]
        cleanupContainerPorts = [7990]

        disableCleanup = false

    }


    def "test setupContainer"(String dockerHost, String certPath, String baseUrl) {
        setup:
        log.info("Testing setup of BB container using trait method")
        BitbucketContainer bbc = new BitbucketContainer(baseUrl, dockerHost, certPath)
        bbc.containerName = bbc.extractDomainFromUrl(baseUrl)
        BitbucketInstanceManagerRest bbr = new BitbucketInstanceManagerRest(baseUrl)


        when:
        String containerId = bbc.createContainer()
        ContainerInspectResponse containerInspect = dockerClient.inspectContainer(containerId).content


        then:
        assert containerInspect.name == "/" + bbc.containerName: "BB was not given the expected name"
        assert containerInspect.state.status == ContainerState.Status.Created: "BB Container status is of unexpected value"
        assert containerInspect.state.running == false: "BB Container was started even though it should only have been created"
        assert dockerClient.inspectImage(containerInspect.image).content.repoTags.find { it == "atlassian/bitbucket:latest" || it.contains("atlassian/bitbucket:${BitbucketContainer.getLatestBbVersion()}") }: "BB container was created with incorrect Docker image"
        assert containerInspect.hostConfig.portBindings.containsKey("7990/tcp"): "BB Container port binding was not setup correctly"
        log.info("\tBB Container was setup correctly")

        expect:
        bbc.startContainer()
        bbr.setApplicationProperties(new File(System.getProperty("user.home") + "/.licenses/bitbucket/bitbucket.license").text)
        bbr.status == "RUNNING"


        where:
        dockerHost       | certPath       | baseUrl
        ""               | ""             | "http://localhost:7990"


    }


}
