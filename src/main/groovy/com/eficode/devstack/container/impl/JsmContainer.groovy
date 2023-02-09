package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.util.ImageBuilder
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.ImageSummary
import de.gesellix.docker.remote.api.NetworkingConfig
import de.gesellix.docker.remote.api.PortBinding
import kong.unirest.HttpResponse
import kong.unirest.JsonResponse
import kong.unirest.Unirest
import kong.unirest.UnirestInstance

class JsmContainer implements Container {

    String containerName = "JSM"
    String containerMainPort = "8080"
    ArrayList<String> customEnvVar = []
    String containerImage = "atlassian/jira-servicemanagement"
    String containerImageTag = "latest"
    long jvmMaxRam = 6000

    JsmContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }


    /**
     * Gets the latest version number from Atlassian Marketplace
     * @return ex: 5.6.0
     */
    static String getLatestJsmVersion() {

        UnirestInstance unirest = Unirest.spawnInstance()

        HttpResponse<JsonResponse> response = unirest.get("https://marketplace.atlassian.com/rest/2/products/key/jira-servicedesk/versions/latest").asJson() as HttpResponse<JsonResponse>
        assert response.success : "Error getting latest JSM version from marketplace"
        String version = response?.body?.object?.get("name")
        assert version : "Error parsing latest JSM version from marketplace response"

        unirest.shutDown()

        return version
    }

    @Override
    ContainerCreateRequest setupContainerCreateRequest() {

       String image = containerImage + ":" + containerImageTag

        log.debug("Setting up container create request for JSM container")
        if (dockerClient.engineArch != "x86_64") {
            log.debug("\tDocker engine is not x86, building custom JSM docker image")

            ImageBuilder imageBuilder = new ImageBuilder(dockerClient.host, dockerClient.certPath)
            String jsmVersion = containerImageTag
            if (jsmVersion == "latest") {
                log.debug("\tCurrent image tag is set to \"latest\", need to resolve latest version number from Atlassian Marketplace in order to build custom image")
                jsmVersion = getLatestJsmVersion()
            }
            log.debug("\tStarting building of Docker Image for JSM verion $jsmVersion")
            ImageSummary newImage = imageBuilder.buildJsm(jsmVersion)
            log.debug("\tFinished building custom image:" + newImage.repoTags.join(","))

            image = newImage.repoTags.first()
        }

        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = image
            c.exposedPorts = [(containerMainPort + "/tcp"): [:]]
            c.hostConfig = new HostConfig().tap { h ->
                h.portBindings = [(containerMainPort + "/tcp"): [new PortBinding("0.0.0.0", (containerMainPort))]]
                h.mounts = this.mounts
            }
            c.hostname = containerName
            c.env = ["JVM_MAXIMUM_MEMORY=" + jvmMaxRam + "m", "JVM_MINIMUM_MEMORY=" + ((jvmMaxRam / 2) as String) + "m", "ATL_TOMCAT_PORT=" + containerMainPort] + customEnvVar


        }

        return containerCreateRequest

    }






    boolean runOnFirstStartup() {

        ArrayList<String> initialOut = runBashCommandInContainer("echo END")
        initialOut.remove { "END" }
        if (initialOut) {
            log.warn("StdOut contains unexpected output on initial startup:")
            initialOut.each { log.warn("\t" + it) }
        }
        log.debug("\tCreating folders needed for running Spoc tests with ScriptRunner")
        assert runBashCommandInContainer("mkdir  /opt/atlassian/jira/surefire-reports ; chown jira:jira  /opt/atlassian/jira/surefire-reports").empty
        log.debug("\tUpdating apt and installing dependencies")
        assert runBashCommandInContainer("apt update; apt install -y htop nano inetutils-ping; echo status: \$?", 300).any { it.contains("status: 0") }

        return true


    }


}
