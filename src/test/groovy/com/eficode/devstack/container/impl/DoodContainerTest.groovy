package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.remote.api.ContainerState
import org.slf4j.LoggerFactory

class DoodContainerTest extends DevStackSpec {

    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "~/.docker/"


        log = LoggerFactory.getLogger(DoodContainerTest.class)


        cleanupContainerNames = ["dood.domain.se"]
        cleanupContainerPorts = []

        disableCleanup = false
    }

    def "Test the basics with local and remote"(String dockerHost, String certPath) {

        when:
        DoodContainer dc = new DoodContainer(dockerHost, certPath)
        if (dockerHost && certPath) {
            assert dc.dockerClient.dockerClientConfig.host == dc.extractDomainFromUrl(dockerHost): "Connection to remote Docker host was not setup"

        }

        dc.containerName = "dood.domain.se"
        String containerId = dc.createContainer([], ["tail", "-f", "/dev/null"])


        then:
        containerId == dc.id
        dc.containerName == "dood.domain.se"
        dc.inspectContainer().name == "/dood.domain.se"
        dc.inspectContainer().mounts.any { it.source == "/var/run/docker.sock" && it.destination == "/var/run/docker.sock" }
        dc.status() == ContainerState.Status.Created
        dc.startContainer()
        dc.status() == ContainerState.Status.Running

        when: "Child docker client queries for running containers"
        String cmdOut = dc.runBashCommandInContainer("docker ps --no-trunc --format \"{{.ID}}\" | grep " + containerId).last()

        then: "It should find it self being run on the parent docker engine"
        cmdOut == containerId


        where:
        dockerHost       | certPath
        dockerRemoteHost | dockerCertPath
        ""               | ""


    }

}