package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.PortBinding

class BitbucketContainer implements Container{

    String containerName = "Bitbucket"
    String containerMainPort = "7990"
    String containerImage = "atlassian/bitbucket"
    String containerImageTag = "latest"
    long jvmMaxRam = 4096


    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost  ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    BitbucketContainer(String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath) : "Error setting up secure remote docker connection"
    }

    BitbucketContainer() {}


    /**
     * Creates the container
     * @return returns container ID
     */
    String createContainer() {

        containerId = createBbContainer(this.containerName)
        return containerId

    }

    String createContainer(ArrayList<String> cmd , ArrayList<String> entrypoint ) {

        if (cmd || entrypoint) {
            throw new InputMismatchException("cmd and entrypoint cant be supplied to ${BitbucketContainer.simpleName}")
        }

        return createContainer()

    }

    String createBbContainer(String containerName = this.containerName, String imageName = containerImage, String imageTag = containerImageTag, long maxRamMB = jvmMaxRam, String mainPort = containerMainPort) {

        assert dockerClient.ping().content as String == "OK", "Error Connecting to docker service"


        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = imageName + ":" + imageTag
            c.env = ["JVM_MAXIMUM_MEMORY=" + maxRamMB.toString() + "m", "JVM_MINIMUM_MEMORY=" + ((maxRamMB / 2) as String) + "m"]
            c.exposedPorts = [(mainPort + "/tcp"): [:]]
            c.hostConfig = new HostConfig().tap { h -> h.portBindings = [(mainPort + "/tcp"): [new PortBinding("0.0.0.0", (mainPort.toString()))]] }
            c.hostname = containerName

        }


        EngineResponseContent response = dockerClient.createContainer(containerCreateRequest, containerName)
        assert response.content.warnings.isEmpty(): "Error when creating $containerName container:" + response.content.warnings.join(",")

        containerId = response.content.id
        return containerId


    }


    boolean runOnFirstStartup() {
        log.debug("\tUpdating apt and installing dependencies")
        assert runBashCommandInContainer("apt update; apt install -y htop nano inetutils-ping; echo \$?", 20).last() == "0"

        return true
    }



}
