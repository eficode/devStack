package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.PortBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException

class AlpineContainer implements Container {

    String containerName = "Alpine"
    String containerMainPort = null
    String containerImage = "alpine"
    String containerImageTag = "latest"
    String defaultShell = "/bin/sh"


    AlpineContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }

    /**
     * Will create an Alpine Container that will sleep indefinitely
     * @return
     */
    String createSleepyContainer() {
        return createContainer([], ["tail", "-f", "/dev/null"])
    }


    /**
     * Creates an Alpine container, runs a command, exits and removes container
     * @param cmd A string that will be passed as a command to /bin/sh -c, ex: echo start;sleep 5
     * @param timeoutMs
     *      0 don't wait, return an array with the container ID immediately,
     *      timeoutMs > 0 Wait for container to stop, if it takes longer than timeoutMs an exception will be thrown
     * @param mounts bind mounts that the container should have:
     *      readOnly is optional and defaults to true
     *      ex:[[src: "/tmp/engine/test", target: "/tmp/container/test", readOnly :true]
     * @param dockerHost
     * @param dockerCertPath
     * @return An array of the container logs, or just an array containing container id if timeoutMs == 0
     */
    static ArrayList<String> runCmdAndRm(String cmd, long timeoutMs, ArrayList<Map> mounts = [:], String dockerHost = "", String dockerCertPath = "") {
        return runCmdAndRm(["/bin/sh", "-c", cmd], timeoutMs, mounts, dockerHost, dockerCertPath)
    }

    /**
     * Creates an Alpine container, runs a command, exits and removes container
     * @param cmd An array of commands to run, ex: [ "/bin/sh", "-c", "echo start;sleep 5"]
     * @param timeoutMs
     *      0 don't wait, return an array with the container ID immediately,
     *      timeoutMs > 0 Wait for container to stop, if it takes longer than timeoutMs an exception will be thrown
     * @param mounts bind mounts that the container should have:
     *      readOnly is optional and defaults to true
     *      ex:[[src: "/tmp/engine/test", target: "/tmp/container/test", readOnly :true]
     * @param dockerHost
     * @param dockerCertPath
     * @return An array of the container logs, or just an array containing container id if timeoutMs == 0
     */
    static ArrayList<String> runCmdAndRm(ArrayList<String> cmd, long timeoutMs, ArrayList<Map> mounts = [:], String dockerHost = "", String dockerCertPath = "") {

        Logger log = LoggerFactory.getLogger(AlpineContainer)

        log.info("Running alpine command")
        log.info("\tCmd:" + cmd)
        AlpineContainer container = null


        try {
            container = new AlpineContainer(dockerHost, dockerCertPath)
            container.containerName = container.containerName + "-cmd-" + System.currentTimeMillis().toString()[-5..-1]

            mounts.each {
                log.info("\tPreparing Bind mount:")
                container.prepareBindMount(it.src as String, it.target as String, it.containsKey("readOnly") ? it.readOnly as Boolean : true)
            }


            container.createContainer(cmd)
            log.info("\tCreated container: " + container.id)


            log.info("\tStarted container: " + container.startContainer())
            assert !container.hasNeverBeenStarted(): "Error starting Alpine container"

            if (timeoutMs == 0) {
                log.info("\tNo Timeout set, returning container id")
                return [container.id]
            }

            long start = System.currentTimeMillis()

            while (start + timeoutMs > System.currentTimeMillis() && container.running) {

                log.info("\tWaited ${System.currentTimeMillis() - start}ms for container to stop")
                sleep(1000)

            }
            log.info("\tContainer finisehd or timed out after ${System.currentTimeMillis() - start}ms")

            if (container.running) {
                log.info("\t"*2 + "Stopping container forcefully.")
                ArrayList<String> containerOut = container.containerLogs
                assert container.stopAndRemoveContainer(1): "Error stopping and removing Alpine container after it timed out"

                throw new TimeoutException("Alpine container timed out, was forcefully stopped and removed. Container logs:" + containerOut?.join("\n"))
            }



            ArrayList<String> containerOut = container.containerLogs

            log.info("\tReturning ${containerOut.size()} log lines")

            assert container.stopAndRemoveContainer(): "Error removing Container:" + container.id
            log.info("\tRemoved container:" + container.id)

            return containerOut
        } catch (ex) {


            try {
                container.stopAndRemoveContainer(1)
            } catch (ignored){}



            throw ex

        }


    }


}
