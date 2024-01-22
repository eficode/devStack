package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import org.slf4j.LoggerFactory
import spock.lang.Shared

/**
 *
 * Prerequisites
 *  DNS names jira.domain.se jira2.domain.se bitbucket.domain.se bitbucket2.domain.se must point to dockerRemoteHost
 *
 */

class JsmAndBitbucketH2DeploymentTest extends DevStackSpec {


    @Shared
    File bitbucketLicenseFile = new File(System.getProperty("user.home") + "/.licenses/bitbucket/bitbucket.license")

    @Shared
    File jsmLicenseFile = new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license")


    def setupSpec() {


        assert jsmLicenseFile.text.length() > 30: "Jira license file does not appear valid"
        assert bitbucketLicenseFile.text.length() > 30: "Bitbucket license file does not appear valid"

        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"


        DevStackSpec.log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)


        cleanupContainerNames = ["jira.domain.se", "jira2.domain.se", "bitbucket.domain.se", "bitbucket2.domain.se"]
        cleanupContainerPorts = [8080, 8082, 7990, 7992]

        disableCleanup = false
    }

    def "test setupDeployment"(String jiraBaseUrl, String jiraPort, String jiraVersion, String bitbucketBaseUrl, String bitbucketVersion, String bitbucketPort, String dockerHost, String certPath) {

        setup:

        JsmAndBitbucketH2Deployment jsmAndBb = new JsmAndBitbucketH2Deployment(jiraBaseUrl, bitbucketBaseUrl, dockerHost, certPath)

        jsmAndBb.jsmContainer.containerImageTag = jiraVersion
        jsmAndBb.bitbucketContainer.containerImageTag = bitbucketVersion
        //jsmAndBb.jsmH2Deployment.jiraRest.setProxy("localhost", 8092)
        //jsmAndBb.jsmH2Deployment.jiraRest.setVerifySsl(false)

        jsmAndBb.jiraAppsToInstall = [
                "https://marketplace.atlassian.com/download/apps/6820/version/1006540": new File(System.getProperty("user.home") + "/.licenses/jira/sr.license").text
        ]

        jsmAndBb.bitbucketLicense = bitbucketLicenseFile.text
        jsmAndBb.jiraLicense = jsmLicenseFile.text

        expect:
        jsmAndBb.setupDeployment()
        jsmAndBb.bitbucketContainer.runBashCommandInContainer("ping -c 1 ${jsmAndBb.jsmContainer.containerName}").any { it.contains("0% packet loss") }
        jsmAndBb.jsmContainer.runBashCommandInContainer("ping -c 1 ${jsmAndBb.bitbucketContainer.containerName}").any { it.contains("0% packet loss") }

        where:
        jiraBaseUrl                   | jiraPort | jiraVersion | bitbucketBaseUrl                   | bitbucketPort | bitbucketVersion | dockerHost       | certPath
        "http://jira2.localhost:8082" | 8082     | "latest"    | "http://bitbucket2.localhost:7992" | 7992          | "latest"         | dockerRemoteHost | dockerCertPath
        "http://jira.localhost:8080"  | 8080     | "5.4.2"     | "http://bitbucket.localhost:7990"  | 7990          | "8.6.4"          | dockerRemoteHost | dockerCertPath


    }


}
