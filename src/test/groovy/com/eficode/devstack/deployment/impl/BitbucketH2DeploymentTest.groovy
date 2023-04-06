package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.util.DockerClientDS
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import kong.unirest.Unirest
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import spock.lang.Shared

class BitbucketH2DeploymentTest extends DevStackSpec{



    @Shared
    File bitbucketLicenseFile = new File("resources/bitbucket/licenses/bitbucketLicense")

    def setupSpec() {


        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"



        log = LoggerFactory.getLogger(BitbucketH2DeploymentTest.class)

        cleanupContainerNames = ["bitbucket.domain.se", "bitbucket2.domain.se" , "localhost"]
        cleanupContainerPorts = [7990, 7992, 80]

        disableCleanup = false
    }


    def "def setupDeployment"(String dockerHost, String certPath, String baseUrl) {

        setup:
        BitbucketH2Deployment  bitbucketDep = new BitbucketH2Deployment(baseUrl, dockerHost, certPath)
        bitbucketDep.setBitbucketLicence(bitbucketLicenseFile)

        String port = bitbucketDep.bitbucketContainer.extractPortFromUrl(baseUrl)


        when:
        boolean setupSuccess = bitbucketDep.setupDeployment()

        then:
        setupSuccess
        Unirest.get(baseUrl).asEmpty().status == 200
        bitbucketDep.bitbucketContainer.inspectContainer().networkSettings.ports.find {it.key == "$port/tcp"}


        where:
        dockerHost       | certPath       | baseUrl
        ""               | ""             | "http://localhost"
        dockerRemoteHost | dockerCertPath | "http://bitbucket.domain.se:7990"
        dockerRemoteHost | dockerCertPath | "http://bitbucket2.domain.se:7992"





    }

}
