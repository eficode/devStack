package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import kong.unirest.Unirest
import org.slf4j.LoggerFactory
import spock.lang.Shared

class BitbucketH2DeploymentTest extends DevStackSpec {


    @Shared
    File bitbucketLicenseFile = new File(System.getProperty("user.home") + "/.licenses/bitbucket/bitbucket.license")

    def setupSpec() {


        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"


        DevStackSpec.log = LoggerFactory.getLogger(BitbucketH2DeploymentTest.class)

        cleanupContainerNames = ["bitbucket.domain.se", "bitbucket2.domain.se", "localhost"]
        cleanupContainerPorts = [7990, 7992, 80]

        disableCleanup = false
    }


    def "def setupDeployment"(String dockerHost, String certPath, String baseUrl) {

        setup:
        BitbucketH2Deployment bitbucketDep = new BitbucketH2Deployment(baseUrl, dockerHost, certPath)
        bitbucketDep.setBitbucketLicence(bitbucketLicenseFile)

        String port = bitbucketDep.bitbucketContainer.extractPortFromUrl(baseUrl)


        when:
        boolean setupSuccess = bitbucketDep.setupDeployment()

        then:
        setupSuccess
        Unirest.get(baseUrl).asEmpty().status == 200
        bitbucketDep.bitbucketContainer.inspectContainer().networkSettings.ports.find { it.key == "$port/tcp" }


        where:
        dockerHost | certPath | baseUrl
        ""         | ""       | "http://localhost"
        ""         | ""       | "http://bitbucket.localhost:7992"


    }

}
