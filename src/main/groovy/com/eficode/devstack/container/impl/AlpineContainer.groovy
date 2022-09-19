package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.PortBinding

class AlpineContainer implements Container {

    String containerName = "Alpine"
    String containerMainPort = null
    String containerImage = "alpine"
    String containerImageTag = "latest"
    String defaultShell = "/bin/sh"


    AlpineContainer() {}

    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    AlpineContainer(String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
    }

    /**
     * Will create an Alpine Container that will sleep indefinitely
     * @return
     */
    String createSleepyContainer(){
        return createContainer(["sleep", "infinity"], [])
    }


    String createContainer(ArrayList<String> cmd , ArrayList<String> entrypoint ) {

        assert ping(): "Error connecting to docker engine"

        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = containerImage + ":" + containerImageTag
            c.hostname = containerName


        }

        if (cmd.size()) {
            containerCreateRequest.cmd = cmd
        }

        if (entrypoint.size()) {
            containerCreateRequest.entrypoint = entrypoint
        }

        EngineResponseContent response = dockerClient.createContainer(containerCreateRequest, containerName)
        assert response.content.warnings.isEmpty(): "Error when creating $containerName container:" + response.content.warnings.join(",")

        containerId = response.content.id
        return containerId

    }

    String createContainer() {
        return createContainer([], [])
    }



    boolean runOnFirstStartup() {

        return true
    }

}
