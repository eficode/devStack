package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.HarborManagerContainer
import com.eficode.devstack.deployment.Deployment
import com.eficode.devstack.util.DockerClientDS
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.ContainerSummary
import de.gesellix.docker.remote.api.ContainerWaitExitError
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class HarborDeployment implements Deployment {

    Logger log = LoggerFactory.getLogger(this.class)
    String friendlyName = "Harbor Deployment"
    ArrayList<Container> containers = []
    String deploymentNetworkName = "harbor"


    HarborDeployment(String baseUrl, String harborVersion = "v2.6.0", String baseDir = "/opt/", String dockerHost = "", String dockerCertPath = "") {


        HarborManagerContainer managerContainer = new HarborManagerContainer(baseUrl, harborVersion, baseDir, dockerHost, dockerCertPath)
        this.containers = [managerContainer]


    }

    HarborManagerContainer getManagerContainer() {
        this.containers.find { it instanceof HarborManagerContainer } as HarborManagerContainer
    }

    @Override
    boolean setupDeployment() {


        managerContainer.containerDefaultNetworks = [deploymentNetworkName]
        assert managerContainer.createContainer([], ["tail", "-f", "/dev/null"]): "Error creating container"
        assert managerContainer.startContainer(): "Error starting container"

        sleep(1000)
        long start = System.currentTimeMillis()
        while (harborContainers.state.find { it != ContainerState.Status.Running.value }) {

            log.info("\tWaiting for containers to start")
            harborContainers.collectEntries { [it.names.first(), it.state] }.each { log.debug("\t\t" + it) }

            assert start + (4 * 60000) > System.currentTimeMillis(): "Timed out waiting for harbor containers to start, waited:" + ((System.currentTimeMillis() - start)/1000).round().toString()
            sleep(1500)
        }

        return true

    }

    @Override
    boolean startDeployment() {

        assert managerContainer.startContainer()
        sleep(5000)
        ArrayList<String> cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.installPath}/harbor ; docker-compose start ; echo status: \$?", 120)
        assert cmdOutput.last() == "status: 0": "Error starting harbor:" + cmdOutput.join("\n")

        return true
    }


    @Override
    boolean stopDeployment() {


        assert managerContainer.startContainer()
        ArrayList<String> cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.installPath}/harbor ; docker-compose stop ; echo status: \$?", 120)
        assert cmdOutput.last() == "status: 0": "Error stopping harbor:" + cmdOutput.join("\n")

        assert managerContainer.stopContainer()
        return true
    }

    @Override
    boolean stopAndRemoveDeployment() {

        log.info("Stopping and removing ${this.class}")




        assert managerContainer.stopAndRemoveContainer()
        ArrayList<ContainerSummary> harbContainers = harborContainers
        if (harbContainers) {
            //There are still harbor containers around

            DockerClientDS dockerClient = managerContainer.dockerClient
            harbContainers.each { container ->

                log.debug("\t\tKilling and removing:" + container.names)
                try {
                    dockerClient.kill(container.id)
                } catch (ignored){}

                dockerClient.rm(container.id)



            }

        }






        return harborContainers.isEmpty()

        /*
        if (!managerContainer.created) {
            //Manager container does not exist
            ArrayList<ContainerSummary> harbContainers = harborContainers
            if (harbContainers) {
                //There are still harbor containers around
                log.info("\tThe manager container does not exist, removing harbor containers manually")

                DockerClientDS dockerClient = managerContainer.dockerClient
                harbContainers.each { container ->

                    log.debug("\t\tKilling and removing:" + container.names)
                    try {
                        dockerClient.kill(container.id)
                    } catch (ignored){}

                    dockerClient.rm(container.id)



                }

            }

            return true


        }

        assert managerContainer.startContainer()
        ArrayList<String> cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.installPath}/harbor ; docker-compose down ; echo status: \$?", 120)
        assert cmdOutput.last() == "status: 0": "Error downing harbor:" + cmdOutput.join("\n")


        cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.basePath} ; ls -I data -I install | wc -l", 5)
        if (cmdOutput != ["0"]) {

            log.warn("\tNot cleaning up base path ${managerContainer.basePath}, found unexpected content")
        } else {
            cmdOutput = managerContainer.runBashCommandInContainer("rm -rf ${managerContainer.basePath} ; echo status: \$?", 120)
            assert cmdOutput.last() == "status: 0": "Error cleaning up base path ${managerContainer.basePath}: " + cmdOutput.join("\n")
        }

        return managerContainer.stopAndRemoveContainer()

         */


    }

    //TODO should return DevStack container objects, and filter more closely
    /**
     * Gets all containers running a Harbor image and the harbor manager container.
     * NOTE: this is a soft check that likely will return false positives
     * @return
     */
    ArrayList<ContainerSummary> getHarborContainers() {

        ArrayList<ContainerSummary> containers = managerContainer.dockerClient.ps().content

        containers = containers.findAll { it.image.startsWith("goharbor") || it.names.first() == "/" + managerContainer.containerName }

        return containers

    }

}
