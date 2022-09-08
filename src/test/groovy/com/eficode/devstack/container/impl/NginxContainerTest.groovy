package com.eficode.devstack.container.impl

import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.remote.api.ContainerInspectResponse
import kong.unirest.Unirest
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.FileFileFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification


class NginxContainerTest extends Specification {

    @Shared
    static Logger log = LoggerFactory.getLogger(NginxContainerTest.class)

    @Shared
    DockerClientImpl dockerClient

    @Shared
    File localNginxRoot = new File("examples")


    @Shared
    String dockerRemoteHost //= "https://docker.domain.se:2376"
    @Shared
    String dockerCertPath //= "resources/dockerCert"
    @Shared
    String nginxUrl = "http://localhost"


    def setupSpec() {
        dockerClient = resolveDockerClient()
        dockerClient.stop("Nginx")
        dockerClient.rm("Nginx")

        assert localNginxRoot.listFiles().findAll{it.isFile()}.size() > 0 : "localNginxRoot must contain at least one file"

    }


    def "test default setup"() {

        setup:
        NginxContainer nginxC = new NginxContainer()

        expect:
        nginxC.createContainer()
        nginxC.startContainer()

        cleanup:
        nginxC.stopAndRemoveContainer()

    }

    def "test setting nginx root bind dir"() {

        setup:
        log.info("Testing setting nginx root dir")
        log.info("\tWill use dir:" + localNginxRoot.absolutePath)

        NginxContainer nginxC = new NginxContainer()

        when: "bindHtmlRoot should add a mount to the mounts array"
        nginxC.bindHtmlRoot(localNginxRoot.absolutePath, true)

        then:
        nginxC.mounts.size() == 1

        when: "After creating the container, the inspect result should confirm the mount"
        nginxC.createContainer()
        nginxC.startContainer()
        ContainerInspectResponse inspectResponse = dockerClient.inspectContainer(nginxC.id).getContent()
        log.info("\tContainer created")

        then:
        inspectResponse.mounts.find {it.source == localNginxRoot.absolutePath}
        log.info("\tDocker API confirms mount was created")

        expect: "Nginx should return the expected files"
        log.info("\tConfirming Nginx returns files found in the bind directory")
        localNginxRoot.listFiles().findAll {it.isFile()}.every {file ->
            log.debug("\t"*2 + "Requesting file:" + file.name)
            int status = Unirest.get(nginxUrl + "/" + file.name).asEmpty().status
            log.debug("\t"*3 + "HTTP Status:" + status)
            return status == 200
        }

        Unirest.get(nginxUrl + "/MISSINGFILE").asEmpty().status == 404




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