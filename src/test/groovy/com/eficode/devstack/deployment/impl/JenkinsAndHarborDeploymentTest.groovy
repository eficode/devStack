package com.eficode.devstack.deployment.impl;

import com.eficode.devstack.DevStackSpec
import org.slf4j.LoggerFactory

public class JenkinsAndHarborDeploymentTest extends DevStackSpec {

    def setupSpec() {


        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"


        log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)


        cleanupContainerNames = ["jenkins.domain.se", "harbor.domain.se"]
        cleanupContainerPorts = [8080, 80]

        disableCleanup = false
    }

    def "test setupDeployment"(String jenkinsBaseUrl, String harborBaseUrl, String dockerHost, String certPath) {

        setup:
        JenkinsAndHarborDeployment jh = new JenkinsAndHarborDeployment(jenkinsBaseUrl, harborBaseUrl, dockerHost, certPath)

        expect:
        jh.setupDeployment()

        where:
        jenkinsBaseUrl             | harborBaseUrl             | dockerHost       | certPath
        "http://jenkins.domain.se" | "http://harbor.domain.se" | dockerRemoteHost | dockerCertPath


    }
}
