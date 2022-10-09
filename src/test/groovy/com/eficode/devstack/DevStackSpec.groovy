package com.eficode.devstack

import com.eficode.devstack.deployment.impl.JsmH2DeploymentTest
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.remote.api.ContainerSummary
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class DevStackSpec extends Specification{

    @Shared
    String dockerRemoteHost = "https://docker.domain.se:2376"
    @Shared
    String dockerCertPath = "resources/dockerCert"

    @Shared
    DockerClientImpl dockerClient

    @Shared
    ArrayList<String> containerNames
    @Shared
    ArrayList<Integer> containerPorts

    @Shared
    boolean disableCleanupAfter = false


    @Shared
    static Logger log = LoggerFactory.getLogger(this.class)


    def setup() {
        cleanupContainers()
    }

    def cleanup() {
        if (!disableCleanupAfter) {
            cleanupContainers()
        }

    }

    def cleanupSpec() {
        if (!disableCleanupAfter) {
            cleanupContainers()
        }
    }



    boolean cleanupContainers() {



        DockerClientImpl dockerClient = resolveDockerClient()
        log.info("Cleaning up containers")

        ArrayList<ContainerSummary> containers = dockerClient.ps().content

        log.debug("\tThere are currenlty ${containers.size()} containers")
        log.debug("\tWill remove any container named:" + containerNames?.join(","))
        log.debug("\tWill remove any container bound to ports:" + containerPorts?.join(","))
        containers.each {container->

            boolean nameCollision = false
            container.names.any {existingName ->
                containerNames.any {unwantedName ->
                    if (existingName == "/"+ unwantedName) {
                        nameCollision = true
                    }
                }
            }


            boolean portCollision = false

            container.ports.find { existingPort ->
                containerPorts.each {unwantedPort ->
                    if (existingPort.publicPort == unwantedPort) {
                        portCollision = true
                    }
                }
            }

            if (nameCollision || portCollision) {
                log.info("\tWill kill and remove container: ${container.names.join(",")} (${container.id})")
                log.debug("\t\tContainer has matching name:" + nameCollision + " (${container.names.join(",")})")
                log.debug("\t\tContainer has matching port:" + portCollision + " (${container.ports.publicPort.join(",")})")

                if (container.state == "running") {
                    dockerClient.kill(container.id)
                }
                dockerClient.rm(container.id)
                log.info("Stopped and removed container: ${container?.names?.join(",")} (${container?.id})")
            }
        }


    }


    /**
    def stopAndRemoveContainer(ArrayList<String> containerNames) {


        DockerClientImpl dockerClient = resolveDockerClient()

        ArrayList<ContainerSummary> containers = dockerClient.ps().content

        containerNames.each {containerName ->

            ContainerSummary container = containers.find { it.Names.first() == "/" + containerName }
            String id = container?.id

            if (id) {
                if (container.state == "running") {
                    dockerClient.kill(id)
                }
                dockerClient.rm(id)
                log.info("Stopped and removed container: ${container?.names?.join(",")} (${container?.id})")
            }
        }


    }
     */

    DockerClientImpl resolveDockerClient() {


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