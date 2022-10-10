package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.ContainerSummary
import kong.unirest.Unirest
import org.slf4j.LoggerFactory

class HarborDeploymentTest extends DevStackSpec {

    String harborBaseUrl = "http://harbor.domain.se" //This domain name must resolve to the docker engines IP

    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"

        dockerClient = resolveDockerClient()

        log = LoggerFactory.getLogger(HarborDeploymentTest.class)

        dockerClient = resolveDockerClient()

        //containerNames = ["harbor.domain.se-manager"]
        //containerPorts = ["80"]

        disableCleanup = true
    }




    @Override
    ArrayList<String> getContainerNames() {

        ArrayList<ContainerSummary> containers = dockerClient.ps().content

        ArrayList<String> containerNames = containers.findAll { it.image.startsWith("goharbor") }.collect { it.names.first().replaceFirst("/", "") }
        containerNames.add("harbor.domain.se-manager")
        return containerNames

    }

    def "Test the basics"() {

        when:
        HarborDeployment hd = new HarborDeployment(harborBaseUrl)
        hd.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)
        hd.setupDeployment()
        hd.managerContainer.runAfterDockerSetup()


        then:
        Unirest.get(harborBaseUrl).basicAuth("admin", "Harbor12345").asEmpty().status == 200
        hd.getContainers().every {it.status() == ContainerState.Status.Running}

        when: "Stopping the deployment"
        hd.stopDeployment()

        then: "All containers should remain but have status Exited"
        hd.getContainers().every {it.status() == ContainerState.Status.Exited}

        when: "Staring the deployment"
        ArrayList<String> containerIdsBeforeStart = hd.getHarborContainers().id
        hd.startDeployment()

        then: "All containers should have the same IDs and status Running"
        containerIdsBeforeStart.sort() == hd.getHarborContainers().id.sort()
        hd.getContainers().every {it.status() == ContainerState.Status.Running}
        Unirest.get(harborBaseUrl).basicAuth("admin", "Harbor12345").asEmpty().status == 200

        when:
        hd.stopAndRemoveDeployment()

        then:
        hd.getHarborContainers().size() == 0

    }
}
