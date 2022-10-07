package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.NetworkingConfig
import de.gesellix.docker.remote.api.PortBinding

class JsmContainer implements Container {

    String containerName = "JSM"
    String containerMainPort = "8080"
    ArrayList<String> customEnvVar = []
    String containerImage = "atlassian/jira-servicemanagement"
    String containerImageTag = "latest"
    long jvmMaxRam = 6000

    JsmContainer() {}

    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    JsmContainer(String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
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
