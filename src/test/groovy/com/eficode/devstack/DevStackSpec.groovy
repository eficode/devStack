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
import org.apache.commons.io.IOCase
import org.apache.commons.io.filefilter.NameFileFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Field
import java.util.regex.Matcher

class DevStackSpec extends Specification {

    @Shared
    //String dockerRemoteHost = "https://docker.domain.se:2376"
    String dockerRemoteHost = ""
    @Shared
    //String dockerCertPath = "~/.docker/"
    String dockerCertPath = ""

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
        cleanupDockerNetworkNames.each { networkName ->
            Network network = alp.getDockerNetwork(networkName)
            log.info("\tRemoving network ${network.name} " + network?.id[0..7])
            assert alp.removeNetwork(network): "Error removing network:" + network.toString()
        }

    }


    boolean cleanupContainers() {


        DockerClientDS dockerClient = resolveDockerClient()
        log.info("Cleaning up containers")

        ArrayList<ContainerInspectResponse> containers = dockerClient.ps().content.collect { dockerClient.inspectContainer(it.id as String).content }

        log.debug("\tThere are currenlty ${containers.size()} containers")
        log.debug("\tWill remove any container named:" + cleanupContainerNames?.join(","))
        log.debug("\tWill remove any container bound to ports:" + cleanupContainerPorts?.join(","))
        containers.each { container ->


            boolean nameCollision = cleanupContainerNames.any { container.name == "/" + it }

            boolean portCollision = cleanupContainerPorts.any { unwantedPort -> container?.hostConfig?.portBindings?.values()?.hostPort?.flatten()?.contains(unwantedPort.toString()) }


            if (nameCollision || portCollision) {
                log.info("\tWill kill and remove container: ${container.name} (${container.id})")
                log.debug("\t\tContainer has matching name:" + nameCollision + " (${container.name})")
                log.debug("\t\tContainer has matching port:" + portCollision + " (${container?.hostConfig?.portBindings?.values()?.hostPort?.flatten()?.join(",")})")

                if (container.state.status in [ContainerState.Status.Running, ContainerState.Status.Restarting]) {
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


    /**
     * Replaces the value of a variable in a script text
     *  What ever comes after the "=" is replaces so make sure to include quotation-marks for strings for example
     *
     * @param src Original script text
     * @param variableName name of variable
     * @param newValue The new value
     * @return The new script text
     */
    static String replaceVariableValue(String src, String variableName, String newValue) {


        Matcher match = src =~ /(?m)^[A-Z].*? $variableName ?= ?(.*)$/

        if (match.size() != 1) {
            log.error("No matches found for variable:" + variableName)
            return null
        }

        String origRow = match[0][0]
        String origValue = match[0][1]

        String newRow = origRow.replace(origValue, newValue)

        String newText = src.replaceFirst("(?m)^$origRow\$", newRow)


        log.trace("-" * 10 + "START ORIGINAL INPUT" + "-" * 10)
        src.eachLine { log.trace("\t" + it) }
        log.trace("-" * 10 + "END ORIGINAL INPUT" + "-" * 10)

        log.trace("-" * 10 + "START NEW OUTPUT" + "-" * 10)
        newText.eachLine { log.trace("\t" + it) }
        log.trace("-" * 10 + "END NEW OUTPUT" + "-" * 10)

        return newText

    }


    /**
     * Starts in CWD and looks for pom.xml, traverse upwards in file tree until it is found.
     * If "/" is reached, null is returned
     * @return File representing project root dir
     */
    static File getDevStackProjectRoot() {

        log.info("Detecting Project root based on pom.xml file")
        File cwd = new File(".").getCanonicalFile()
        File projectRoot = null

        while (!projectRoot){

            log.debug("\tChecking if ${cwd.canonicalPath} is Project root")
            File pomFile = cwd.listFiles().find {it.name == "pom.xml"}

            if (pomFile) {
                log.debug("\t" * 2 + "Found root!")
                projectRoot = cwd
            }else {
                log.debug("\t" * 2 + "This is not root, testing parent")

                cwd = cwd.parentFile

                if (!cwd) {
                    log.warn("\t" * 2 + cwd?.canonicalPath + " has no parent")
                    log.warn("\tCould not find Project root")
                    return null
                }
            }

        }


        return projectRoot.getCanonicalFile()
    }


    Boolean runDevstackMvnClean() {

        File projectRoot = getDevStackProjectRoot()

        ArrayList<String> mvnOut = runCmd("cd '${projectRoot.canonicalPath}' && mvn clean && echo Exit Status: \$?")

        return mvnOut.any {it.contains("Status: 0")}

    }

    File buildDevStackJar(Boolean buildStandalone = false, String extraPackageParemeters = "-DskipTests") {

        File projectRoot = getDevStackProjectRoot()

        String cmd = "cd '${projectRoot.canonicalPath}'"


        if (buildStandalone) {
            cmd += " mvn gplus:execute@execute && mvn clean && mvn package -f pom-standalone.xml $extraPackageParemeters"
        }else {
            cmd += " mvn clean && mvn package $extraPackageParemeters"
        }

        log.info("Building devstack")
        ArrayList<String> mvnOut = runCmd(cmd)

        String jarPath = mvnOut.find {logRow ->
            logRow.contains("Building jar") &&
                    ! logRow.contains("-sources") &&
                    logRow.endsWith(".jar") &&
                    logRow.substring(logRow.lastIndexOf("/") + 1).startsWith("devstack-") &&
                    (logRow.contains("standalone") || !buildStandalone)
        }
        if (!jarPath) {
            mvnOut.each {log.warn(it)}
        }
        assert jarPath : "Error determining jar path after building DevStack"
        jarPath = jarPath.substring(jarPath.indexOf("/"))
        assert jarPath : "Error determining jar path after building DevStack"

        File jarFile = new File(jarPath)
        log.info("\tBuilt jar:" + jarFile.canonicalPath)

        return jarFile


    }

    static ArrayList<String> runCmd(String cmd, ArrayList<String> envs = []) {

        StringBuffer appenderStd = new StringBuffer()
        //StringBuffer appenderErr = new StringBuffer()


        Process proc = ["bash", "-c", cmd].execute(envs ?: null, null)

        proc.waitForProcessOutput(appenderStd, appenderStd)



        //Map out = ["stdOut": appenderStd.toString().split("\n"), "errOut": appenderErr.toString().split("\n")]


        return appenderStd.toString().split("\n")
    }

}