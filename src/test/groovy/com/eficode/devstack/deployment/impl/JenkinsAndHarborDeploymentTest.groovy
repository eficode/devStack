package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import org.slf4j.LoggerFactory

class JenkinsAndHarborDeploymentTest extends DevStackSpec {

    def setupSpec() {


        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "~/.docker/"


        DevStackSpec.log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)


        cleanupContainerNames = [
                "jenkins.domain.se",
                "harbor.domain.se",
                "harbor-jobservice",
                "nginx", "harbor-core",
                "registry",
                "harbor-portal",
                "harbor-db",
                "registryctl",
                "redis",
                "harbor-log",
                "jenkins.domain.se",
                "harbor.domain.se-manager"
        ]
        cleanupContainerPorts = [8080, 80]

        disableCleanup = false
    }

    def "test setupDeployment"(String jenkinsBaseUrl, String harborBaseUrl, String dockerHost, String certPath) {

        setup:
        String networkName = "custom-network-" + System.currentTimeMillis().toString()[-5..-1]
        cleanupDockerNetworkNames.add(networkName)


        JenkinsAndHarborDeployment jh = new JenkinsAndHarborDeployment(jenkinsBaseUrl, harborBaseUrl, dockerHost, certPath)
        jh.deploymentNetworkName = networkName

        expect:
        jh.setupDeployment()

        when: "Collecting all networks used by harbor and jenkins containers"
        ArrayList<String> harborNetworks = jh.harborDeployment.harborContainers.networkSettings.networks.collect {it.keySet()}.flatten().unique()
        ArrayList<String> jenkinsNetworks = jh.jenkinsContainer.connectedContainerNetworks.name.unique()

        then: "They should all be in the same networks"
        harborNetworks == jenkinsNetworks

        where:
        jenkinsBaseUrl             | harborBaseUrl             | dockerHost       | certPath
        "http://jenkins.domain.se:8080" | "http://harbor.domain.se" | dockerRemoteHost | dockerCertPath


    }
}
