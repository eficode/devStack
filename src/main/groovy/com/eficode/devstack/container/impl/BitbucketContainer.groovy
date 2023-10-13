package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.util.ImageBuilder
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.ImageSummary
import de.gesellix.docker.remote.api.PortBinding
import kong.unirest.HttpResponse
import kong.unirest.JsonResponse
import kong.unirest.Unirest
import kong.unirest.UnirestInstance

class BitbucketContainer implements Container {

    String containerName = "Bitbucket"
    String containerMainPort = "7990"
    String containerImage = "atlassian/bitbucket"
    String containerImageTag = "latest"
    String baseUrl
    long jvmMaxRam = 4096


    BitbucketContainer(String baseUrl, String dockerHost = "", String dockerCertPath = "") {

        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
        this.baseUrl = baseUrl
    }

    static String getLatestBbVersion() {
        UnirestInstance unirest = Unirest.spawnInstance()

        HttpResponse<JsonResponse> response = unirest.get("https://marketplace.atlassian.com/rest/2/applications/bitbucket/versions/latest").asJson() as HttpResponse<JsonResponse>
        assert response.success : "Error getting latest Bitbucket version from marketplace"
        String version = response?.body?.object?.get("version")
        assert version : "Error parsing latest Bitbucket version from marketplace response"

        unirest.shutDown()

        return version
    }

    @Override
    ContainerCreateRequest setupContainerCreateRequest() {

        String image = containerImage + ":" + containerImageTag

        log.debug("Setting up container create request for Bitbucket container")
        if (dockerClient.engineArch != "x86_64") {
            log.debug("\tDocker engine is not x86, building custom Bitbucket docker image")

            ImageBuilder imageBuilder = new ImageBuilder(dockerClient.host, dockerClient.certPath)
            String bbVersion = containerImageTag
            if (bbVersion == "latest") {
                log.debug("\tCurrent image tag is set to \"latest\", need to resolve latest version number from Atlassian Marketplace in order to build custom image")
                bbVersion = getLatestBbVersion()
            }
            log.debug("\tStarting building of Docker Image for Bitbucket verion $bbVersion")
            ImageSummary newImage = imageBuilder.buildBb(bbVersion)
            log.debug("\tFinished building custom image:" + newImage.repoTags.join(","))

            image = newImage.repoTags.first()
        }

        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = image
            c.exposedPorts = [(containerMainPort + "/tcp"): [:]]
            c.hostConfig = new HostConfig().tap { h ->
                h.portBindings = [(containerMainPort + "/tcp"): [new PortBinding("0.0.0.0", (containerMainPort))]]
                h.mounts = this.preparedMounts
            }
            c.hostname = containerName
            c.env = ["JVM_MAXIMUM_MEMORY=" + jvmMaxRam + "m", "JVM_MINIMUM_MEMORY=" + ((jvmMaxRam / 2) as String) + "m", "SETUP_BASEURL=" + baseUrl, "SERVER_PORT=" + containerMainPort] + customEnvVar


        }

        return containerCreateRequest

    }


    boolean runOnFirstStartup() {
        log.debug("\tUpdating apt and installing dependencies")
        assert runBashCommandInContainer("apt update; apt install -y htop nano inetutils-ping net-tools; echo status: \$?", 300).any { it.contains("status: 0") }

        return true
    }


}
