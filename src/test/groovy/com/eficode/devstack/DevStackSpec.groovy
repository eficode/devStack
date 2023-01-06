package com.eficode.devstack

import com.eficode.devstack.container.impl.AlpineContainer
import com.eficode.devstack.util.DockerClientDS
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.ContainerSummary
import de.gesellix.docker.remote.api.Network
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class DevStackSpec extends Specification {

    @Shared
    String dockerRemoteHost = "https://docker.domain.se:2376"
    @Shared
    String dockerCertPath = "~/.docker/"

    @Shared
    DockerClientDS dockerClient

    @Shared
    ArrayList<String> cleanupContainerNames
    @Shared
    ArrayList<Integer> cleanupContainerPorts

    @Shared
    ArrayList<String> cleanupDockerNetworkNames = []

    @Shared
    boolean disableCleanup = false


    @Shared
    static Logger log = LoggerFactory.getLogger(this.class)


    //Run before every test
    def setup() {

        dockerClient = resolveDockerClient()
        if (!disableCleanup) {
            cleanupContainers()
            cleanupNetworks()
        }
    }


    //Run after every test
    def cleanup() {
        if (!disableCleanup) {
            cleanupContainers()
            cleanupNetworks()
        }

    }


    boolean cleanupNetworks() {

        AlpineContainer alp = new AlpineContainer(dockerRemoteHost, dockerCertPath)
        cleanupDockerNetworkNames.each {networkName ->
            Network network = alp.getDockerNetwork(networkName)
            log.info("\tRemoving network ${network.name} " + network?.id[0..7])
            assert alp.removeNetwork(network) : "Error removing network:" + network.toString()
        }

    }


    boolean cleanupContainers() {


        DockerClientDS dockerClient = resolveDockerClient()
        log.info("Cleaning up containers")

        ArrayList<ContainerInspectResponse> containers = dockerClient.ps().content.collect {dockerClient.inspectContainer(it.id as String).content}

        log.debug("\tThere are currenlty ${containers.size()} containers")
        log.debug("\tWill remove any container named:" + cleanupContainerNames?.join(","))
        log.debug("\tWill remove any container bound to ports:" + cleanupContainerPorts?.join(","))
        containers.each { container ->




            boolean nameCollision = cleanupContainerNames.any { container.name == "/" + it}

            boolean portCollision  = cleanupContainerPorts.any {unwantedPort ->container?.hostConfig?.portBindings?.values()?.hostPort?.flatten()?.contains(unwantedPort.toString()) }


            if (nameCollision || portCollision) {
                log.info("\tWill kill and remove container: ${container.name} (${container.id})")
                log.debug("\t\tContainer has matching name:" + nameCollision + " (${container.name})")
                log.debug("\t\tContainer has matching port:" + portCollision + " (${container?.hostConfig?.portBindings?.values()?.hostPort?.flatten()?.join(",")})")

                if (container.state.status in [ContainerState.Status.Running, ContainerState.Status.Restarting] ) {
                    dockerClient.kill(container.id)
                }
                dockerClient.rm(container.id)
                log.info("Stopped and removed container: ${container.name} (${container?.id})")
            }
        }

        log.info("\tFinished cleanup of containers")
        return true

    }


    DockerClientDS resolveDockerClient() {

        log.info("Resolving Docker client")

        String dockerHost = null
        String certPath = null
        File certDir = null

        try {

            if (specificationContext?.currentIteration?.dataVariables?.dockerHost) {
                dockerHost = specificationContext.currentIteration.dataVariables.dockerHost
                log.debug("\tThe current spec provided docker host:" + dockerHost)


            }

            if (specificationContext?.currentIteration?.dataVariables?.certPath) {
                certPath = specificationContext.currentIteration.dataVariables.certPath
                log.debug("\tThe current spec provided cert path:" + certPath)
                if (certPath.startsWith("~")) {
                    certPath = certPath[1..-1]
                    certPath = System.getProperty("user.home") + certPath
                    log.trace("\t\tResolved to:" + certPath)
                }
                certDir = new File(certPath)


                assert certDir.isDirectory(): "The given cert path is not a directory:" + certPath
            }
        } catch (IllegalStateException ex) {
            if (ex.message == "Cannot request current iteration in @Shared context") {
                log.error("\tCant determine resolve DockerClient")
                throw ex
            }
        }


        assert (dockerHost && certPath) || (!dockerHost && !certPath): "Either both of or neither dockerHost and certPath must be provided"


        if (!dockerHost) {
            log.info("\tNo remote host configured, returning local docker connection")
            return new DockerClientDS()
        }


        log.info("\tLooking for docker certs in:" + certDir.absolutePath)
        ArrayList<File> pemFiles = FileUtils.listFiles(certDir, ["pem"] as String[], false)
        log.debug("\t\tFound pem files:" + pemFiles.name.join(","))


        if (!pemFiles.empty && ["ca.pem", "cert.pem", "key.pem"].every { expectedFile -> pemFiles.any { actualFile -> actualFile.name == expectedFile } }) {
            log.info("\tFound Docker certs, returning Secure remote Docker connection")
            try {
                DockerClientDS dockerClient = setupSecureRemoteConnection(dockerRemoteHost, dockerCertPath)
                assert dockerClient.ping().content as String == "OK": "Error pinging remote Docker engine"
                return dockerClient
            } catch (ex) {
                log.error("\tError setting up connection to remote Docker engine:" + ex.message)
                throw ex
            }

        } else {
            log.error("\tCould not find Docker certs, expected ca.pem, cert.pem and key.pem in:" + certDir.absolutePath)
            throw new InputMismatchException("Could not find Docker certs, expected ca.pem, cert.pem and key.pem in:" + certDir.absolutePath)
        }

    }


    /**
     * Replaced the default docker connection (local) with a remote, secure one
     * @param host ex: "https://docker.domain.se:2376"
     * @param certPath folder containing ca.pem, cert.pem, key.pem
     */
    static DockerClientDS setupSecureRemoteConnection(String host, String certPath) {

        DockerClientConfig dockerConfig = new DockerClientConfig(host)
        DockerEnv dockerEnv = new DockerEnv(host)
        dockerEnv.setCertPath(certPath)
        dockerEnv.setTlsVerify("1")
        dockerConfig.apply(dockerEnv)

        return new DockerClientDS(dockerConfig)

    }

}