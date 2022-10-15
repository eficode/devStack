package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import org.slf4j.LoggerFactory
import spock.lang.Shared

class JsmAndBitbucketH2DeploymentTest extends DevStackSpec {


    @Shared
    File bitbucketLicenseFile = new File("resources/bitbucket/licenses/bitbucketLicense")

    @Shared
    File jsmLicenseFile = new File("resources/jira/licenses/jsm.license")


    def setupSpec() {


        assert jsmLicenseFile.text.length() > 10: "Jira license file does not appear valid"
        assert bitbucketLicenseFile.text.length() > 10: "Bitbucket license file does not appear valid"

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"

        dockerClient = resolveDockerClient()

        log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)

        dockerClient = resolveDockerClient()

        cleanupContainerNames = ["jira.domain.se", "jira2.domain.se", "bitbucket.domain.se", "bitbucket2.domain.se"]
        cleanupContainerPorts = [8080, 8082, 7990, 7992]

        disableCleanup = true
    }

    def "test setupDeployment"(String jiraBaseUrl, String jiraPort, String bitbucketBaseUrl, String bitbucketPort) {

        setup:

        JsmAndBitbucketH2Deployment jsmAndBb = new JsmAndBitbucketH2Deployment(jiraBaseUrl, bitbucketBaseUrl)
        jsmAndBb.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)

        jsmAndBb.jiraAppsToInstall = [
                "https://marketplace.atlassian.com/download/apps/6820/version/1005740": new File("resources/jira/licenses/scriptrunnerForJira.license").text
        ]

        jsmAndBb.bitbucketLicense = bitbucketLicenseFile
        jsmAndBb.jiraLicense = jsmLicenseFile


        expect:
        jsmAndBb.setupDeployment()
        jsmAndBb.bitbucketContainer.runBashCommandInContainer("ping -c 1 ${jsmAndBb.jsmContainer.containerName}").any { it.contains("0% packet loss") }
        jsmAndBb.jsmContainer.runBashCommandInContainer("ping -c 1 ${jsmAndBb.bitbucketContainer.containerName}").any { it.contains("0% packet loss") }

        where:
        jiraBaseUrl                   | jiraPort | bitbucketBaseUrl                   | bitbucketPort
        "http://jira.domain.se:8080"  | 8080     | "http://bitbucket.domain.se:7990"  | 7990
        "http://jira2.domain.se:8082" | 8082     | "http://bitbucket2.domain.se:7992" | 7992


    }





}
