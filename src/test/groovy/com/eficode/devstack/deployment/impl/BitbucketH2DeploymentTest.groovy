package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.client.DockerClientImpl
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


        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"


        log = LoggerFactory.getLogger(BitbucketH2DeploymentTest.class)

        cleanupContainerNames = ["bitbucket.domain.se", "bitbucket2.domain.se" , "localhost"]
        cleanupContainerPorts = [7990, 7992, 80]

        disableCleanup = false
    }


    def "def setupDeployment"(String dockerHost, String certPath, String baseUrl) {

        setup:
        BitbucketH2Deployment  bitbucketDep = new BitbucketH2Deployment(baseUrl, dockerHost, certPath)
        //bitbucketDep.setupSecureDockerConnection(dockerHost, baseUrl)
        bitbucketDep.setBitbucketLicence(bitbucketLicenseFile)
        //bitbucketDep.stopAndRemoveDeployment()

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



    /*
    DockerClientImpl resolveDockerClient() {

        if (this.dockerClient) {
            return this.dockerClient
        }

        log.info("Getting Docker client")

        if (!dockerRemoteHost) {
            log.info("\tNo remote host configured, returning local docker connection")
            return new DockerClientImpl()
        }

        File certDir = new File(dockerCertPath)

        if (!certDir.isDirectory()) {
            log.info("\tNo valid Docker Cert Path given, returning local docker connection")
            return new DockerClientImpl()
        }
        log.info("\tLooking for docker certs in:" + certDir.absolutePath)
        ArrayList<File> pemFiles = FileUtils.listFiles(certDir, ["pem"] as String[], false)
        log.debug("\t\tFound pem files:" + pemFiles.name.join(","))


        if (!pemFiles.empty && pemFiles.every { pemFile -> ["ca.pem", "cert.pem", "key.pem"].find { it == pemFile.name } }) {
            log.info("\tFound Docker certs, returning Secure remote Docker connection")
            try {
                DockerClientImpl dockerClient = setupSecureRemoteConnection(dockerRemoteHost, dockerCertPath)
                assert dockerClient.ping().content as String == "OK": "Error pinging remote Docker engine"
                return dockerClient
            } catch (ex) {
                log.error("\tError setting up connection to remote Docker engine:" + ex.message)
                log.info("\tReturning local Docker connection")
                return new DockerClientImpl()
            }

        }

        log.info("\tMissing Docker certs, returning local docker connection")

        return new DockerClientImpl()

    }*/

    /**
     * Replaced the default docker connection (local) with a remote, secure one
     * @param host ex: "https://docker.domain.se:2376"
     * @param certPath folder containing ca.pem, cert.pem, key.pem
     */
    /*
    static DockerClientImpl setupSecureRemoteConnection(String host, String certPath) {

        DockerClientConfig dockerConfig = new DockerClientConfig(host)
        DockerEnv dockerEnv = new DockerEnv(host)
        dockerEnv.setCertPath(certPath)
        dockerEnv.setTlsVerify("1")
        dockerConfig.apply(dockerEnv)

        return new DockerClientImpl(dockerConfig)

    }

     */
}
