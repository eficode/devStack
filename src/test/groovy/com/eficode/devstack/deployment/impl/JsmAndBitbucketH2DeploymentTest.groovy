package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.impl.JsmContainer
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class JsmAndBitbucketH2DeploymentTest extends Specification{

    @Shared
    String dockerRemoteHost = "https://docker.domain.se:2376"
    @Shared
    String dockerCertPath = "resources/dockerCert"

    @Shared
    String jiraBaseUrl = "http://jira.domain.se:8080"

    @Shared
    String bitbucketBaseUrl = "http://bitbucket.domain.se:7990"

    @Shared
    String jiraDomain = JsmContainer.extractDomainFromUrl(jiraBaseUrl)

    @Shared
    String bitbucketDomain = JsmContainer.extractDomainFromUrl(bitbucketBaseUrl)


    @Shared
    DockerClientImpl dockerClient

    @Shared
    static Logger log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)

    @Shared
    File bitbucketLicenseFile = new File("resources/bitbucket/licenses/bitbucketLicense")

    @Shared
    File jsmLicenseFile = new File("resources/jira/licenses/jsm.license")


    def setupSpec() {


        assert jsmLicenseFile.text.length() > 10 : "Jira license file does not appear valid"
        assert bitbucketLicenseFile.text.length() > 10 : "Bitbucket license file does not appear valid"
        dockerClient = resolveDockerClient()
        dockerClient.stop(jiraDomain, 1)
        dockerClient.rm(jiraDomain)
        dockerClient.stop(bitbucketDomain, 1)
        dockerClient.rm(bitbucketDomain)
    }


    def "test setupDeployment"() {

        setup:
        JsmAndBitbucketH2Deployment jsmAndBb = new JsmAndBitbucketH2Deployment(jiraBaseUrl, bitbucketBaseUrl)
        jsmAndBb.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)
        jsmAndBb.bitbucketLicense = bitbucketLicenseFile
        jsmAndBb.jiraLicense = jsmLicenseFile


        expect:
        jsmAndBb.setupDeployment()
        jsmAndBb.jsmContainer.runBashCommandInContainer("ping -c 1 ${bitbucketDomain}").any {it.contains("0% packet loss")}
        jsmAndBb.bitbucketContainer.runBashCommandInContainer("ping -c 1 ${jiraDomain}").any {it.contains("0% packet loss")}
    }




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

    }

    /**
     * Replaced the default docker connection (local) with a remote, secure one
     * @param host ex: "https://docker.domain.se:2376"
     * @param certPath folder containing ca.pem, cert.pem, key.pem
     */
    static DockerClientImpl setupSecureRemoteConnection(String host, String certPath) {

        DockerClientConfig dockerConfig = new DockerClientConfig(host)
        DockerEnv dockerEnv = new DockerEnv(host)
        dockerEnv.setCertPath(certPath)
        dockerEnv.setTlsVerify("1")
        dockerConfig.apply(dockerEnv)

        return new DockerClientImpl(dockerConfig)

    }


}
