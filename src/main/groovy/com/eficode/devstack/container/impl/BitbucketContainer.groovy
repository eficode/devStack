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
    String baseUrl
    long jvmMaxRam = 4096


    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost  ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    BitbucketContainer(String baseUrl, String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath) : "Error setting up secure remote docker connection"
        this.baseUrl = baseUrl
    }

    BitbucketContainer(String baseUrl) {
        this.baseUrl = baseUrl
    }

    @Override
    ContainerCreateRequest setupContainerCreateRequest() {

        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = containerImage + ":" + containerImageTag
            c.exposedPorts = [(containerMainPort + "/tcp"): [:]]
            c.hostConfig = new HostConfig().tap { h ->
                h.portBindings = [(containerMainPort + "/tcp"): [new PortBinding("0.0.0.0", (containerMainPort))]]
                h.mounts = this.mounts
            }
            c.hostname = containerName
            c.env = ["JVM_MAXIMUM_MEMORY=" + jvmMaxRam + "m", "JVM_MINIMUM_MEMORY=" + ((jvmMaxRam / 2) as String) + "m", "SETUP_BASEURL=" + baseUrl , "SERVER_PORT=" + containerMainPort] + customEnvVar


        }

        return containerCreateRequest

    }



    boolean runOnFirstStartup() {
        log.debug("\tUpdating apt and installing dependencies")
        assert runBashCommandInContainer("apt update; apt install -y htop nano inetutils-ping net-tools; echo status: \$?", 300).any {it.contains("status: 0")}

        return true
    }



}
