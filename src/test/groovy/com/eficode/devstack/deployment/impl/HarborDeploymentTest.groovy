package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.container.impl.DoodContainer
import de.gesellix.docker.remote.api.ContainerState
import org.slf4j.LoggerFactory

class HarborDeploymentTest extends DevStackSpec {

    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"

        dockerClient = resolveDockerClient()

        log = LoggerFactory.getLogger(HarborDeploymentTest.class)

        dockerClient = resolveDockerClient()

        containerNames = ["harbor.domain.se-manager"]
        //containerPorts = ["80"]

        disableCleanupAfter = true
    }


    def "Test the basics"() {

        when:
        HarborDeployment hd = new HarborDeployment("http://harbor.domain.se")
        hd.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)
        //hd.managerContainer.runAfterDockerSetup()
        hd.setupDeployment()
        then:
        true




    }
}
