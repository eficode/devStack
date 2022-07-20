package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.PortBinding

class JsmContainer implements Container{

    String containerName = "JSM"
    String containerMainPort = "8080"
    ArrayList <String> customEnvVar = [] //Ex: ["key=value", "PATH=/user/local/sbin"]
    String containerImage = "atlassian/jira-servicemanagement"
    String containerImageTag = "latest"
    long jvmMaxRam = 6000

    JsmContainer() {}

    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost  ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    JsmContainer(String dockerHost, String dockerCertPath) {
       assert setupSecureRemoteConnection(dockerHost, dockerCertPath) : "Error setting up secure remote docker connection"
    }


    /**
     * Creates the container
     * @return returns container ID
     */
    String createContainer() {

        containerId = createJsmContainer(this.containerName)
        return containerId

    }

    String createJsmContainer(String containerName = this.containerName, String imageName = containerImage, String imageTag = containerImageTag, long jsmMaxRamMB = jvmMaxRam, String webPort = containerMainPort) {

        assert dockerClient.ping().content as String == "OK", "Error Connecting to docker service"


        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = imageName + ":" + imageTag
            c.env = ["JVM_MAXIMUM_MEMORY=" + jsmMaxRamMB.toString() + "m", "JVM_MINIMUM_MEMORY=" + ((jsmMaxRamMB / 2) as String) + "m"] + customEnvVar
            c.exposedPorts = [(webPort + "/tcp"): [:]]
            c.hostConfig = new HostConfig().tap { h -> h.portBindings = [(webPort + "/tcp"): [new PortBinding("0.0.0.0", (webPort.toString()))]] }

        }



        //EngineResponseContent response = dockerClient.run(containerCreateRequest, jsmContainerName)
        EngineResponseContent response = dockerClient.createContainer(containerCreateRequest, containerName)
        assert response.content.warnings.isEmpty(): "Error when creating $containerName container:" + response.content.warnings.join(",")

        containerId = response.content.id
        return containerId


    }

}
