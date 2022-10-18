package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.remote.api.ContainerInspectResponse
import kong.unirest.Unirest
import org.slf4j.LoggerFactory
import spock.lang.Shared

class NginxContainerTest extends DevStackSpec {


    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"


        log = LoggerFactory.getLogger(this.class)

        cleanupContainerNames = ["Nginx"]
        cleanupContainerPorts = [80]


    }


    def "test default setup"(String dockerHost, String certPath) {

        setup:

        NginxContainer nginxC = new NginxContainer(dockerHost, certPath)

        expect:
        nginxC.createContainer()
        nginxC.startContainer()

        where:
        dockerHost       | certPath
        ""               | ""
        dockerRemoteHost | dockerCertPath

    }


    def "test setting nginx root bind dir"(String dockerHost, String certPath, String baseUrl) {

        setup:
        String localNginxRoot = "/tmp/"
        log.info("Testing setting nginx root dir")
        log.info("\tWill use dir:" + localNginxRoot)

        NginxContainer nginxC = new NginxContainer(dockerHost, certPath)
        //stopAndRemoveContainer([nginxC.containerName])

        when: "bindHtmlRoot should add a mount to the mounts array"
        nginxC.bindHtmlRoot(localNginxRoot, false)

        then:
        nginxC.mounts.size() == 1

        when: "After creating the container, the inspect result should confirm the mount"
        String containerId = nginxC.createContainer()
        log.info("\tCreated container:" + containerId)
        assert nginxC.startContainer(): "Error starting container"
        ContainerInspectResponse inspectResponse = dockerClient.inspectContainer(nginxC.id).getContent()
        log.info("\tContainer created")

        then:
        inspectResponse.hostConfig.mounts.find {it.source == localNginxRoot}
        log.info("\tDocker API confirms mount was created")

        when: "Creating a file in the mounted dir"
        ArrayList<String> cmdOutput = nginxC.runBashCommandInContainer("mkdir -p /usr/share/nginx/html/nginxTest && touch /usr/share/nginx/html/nginxTest/a.file && echo status: \$?")


        then: "Nginx should return the expected files"
        cmdOutput.last().contains("status: 0")
        Unirest.get(baseUrl + "/nginxTest/a.file").asEmpty().status == 200
        Unirest.get(baseUrl + "/MISSINGFILE").asEmpty().status == 404


        cleanup:
        nginxC.runBashCommandInContainer("rm -rf /usr/share/nginx/html/nginxTest")


        where:
        dockerHost       | certPath       | baseUrl
        ""               | ""             | "http://localhost"
        dockerRemoteHost | dockerCertPath | "http://docker.domain.se"


    }


}