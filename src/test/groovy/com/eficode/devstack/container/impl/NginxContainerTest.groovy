package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.client.DockerClient
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.ContainerSummary
import kong.unirest.Unirest
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.FileFileFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification


class NginxContainerTest extends DevStackSpec {


    @Shared
    File localNginxRoot = new File("/")



    @Shared
    String nginxUrl = "http://docker.domain.se"


    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"

        dockerClient = resolveDockerClient()

        log = LoggerFactory.getLogger(this.class)

        containerNames = ["Nginx"]
        containerPorts = [80]




    }

    def setup() {
        cleanupContainers()
    }

    def cleanup() {
        cleanupContainers()
    }


    def "test default setup"() {

        setup:

        NginxContainer nginxC = new NginxContainer(dockerRemoteHost, dockerCertPath)


        expect:
        nginxC.createContainer()
        nginxC.startContainer()

        //cleanup:
        //nginxC.stopAndRemoveContainer()

    }


    def "test setting nginx root bind dir"() {

        setup:
        log.info("Testing setting nginx root dir")
        log.info("\tWill use dir:" + localNginxRoot.absolutePath)

        NginxContainer nginxC = new NginxContainer(dockerRemoteHost, dockerCertPath)
        //stopAndRemoveContainer([nginxC.containerName])

        when: "bindHtmlRoot should add a mount to the mounts array"
        nginxC.bindHtmlRoot(localNginxRoot.absolutePath, true)

        then:
        nginxC.mounts.size() == 1

        when: "After creating the container, the inspect result should confirm the mount"
        String containerId = nginxC.createContainer()
        log.info("\tCreated container:" + containerId)
        assert nginxC.startContainer() : "Error starting container"
        ContainerInspectResponse inspectResponse = dockerClient.inspectContainer(nginxC.id).getContent()
        log.info("\tContainer created")

        then:
        inspectResponse.mounts.find { it.source == localNginxRoot.absolutePath }
        log.info("\tDocker API confirms mount was created")

        /**
         * Needs to be reworked for remote docker engines


         expect: "Nginx should return the expected files"
         log.info("\tConfirming Nginx returns files found in the bind directory")
         localNginxRoot.listFiles().findAll {it.isFile()}.every {file ->
         log.debug("\t"*2 + "Requesting file:" + file.name)
         int status = Unirest.get(nginxUrl + "/" + file.name).asEmpty().status
         log.debug("\t"*3 + "HTTP Status:" + status)
         return status == 200
         }

         *
         */


        Unirest.get(nginxUrl + "/MISSINGFILE").asEmpty().status == 404


        //cleanup:
        //nginxC.stopAndRemoveContainer()


    }


}