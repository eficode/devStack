package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.DoodContainer
import com.eficode.devstack.container.impl.HarborManagerContainer
import com.eficode.devstack.deployment.Deployment
import de.gesellix.docker.remote.api.ContainerSummary

class HarborDeployment implements Deployment{

    String friendlyName = "Harbor Deployment"
    ArrayList<Container> containers = []


    HarborDeployment(String baseUrl,  String harborVersion = "v2.6.0", String baseDir = "/opt/", String dockerHost = "", String dockerCertPath = "") {


        HarborManagerContainer managerContainer = new HarborManagerContainer(baseUrl, harborVersion, baseDir, dockerHost, dockerCertPath)
        this.containers = [managerContainer]


    }

    HarborManagerContainer getManagerContainer() {
        this.containers.find {it instanceof HarborManagerContainer} as HarborManagerContainer
    }

    @Override
    boolean setupDeployment() {

        managerContainer.createContainer([],["tail", "-f", "/dev/null"])
        managerContainer.startContainer()
    }

    @Override
    boolean startDeployment() {

        assert managerContainer.startContainer()
        sleep(5000)
        ArrayList<String> cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.installPath}/harbor ; docker-compose start ; echo status: \$?", 120 )
        assert cmdOutput.last() == "status: 0": "Error starting harbor:" + cmdOutput.join("\n")

    }


    @Override
    boolean stopDeployment() {

        assert managerContainer.startContainer()
        ArrayList<String> cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.installPath}/harbor ; docker-compose stop ; echo status: \$?", 120 )
        assert cmdOutput.last() == "status: 0": "Error stopping harbor:" + cmdOutput.join("\n")

        assert managerContainer.stopContainer()
        return true
    }

    @Override
    boolean stopAndRemoveDeployment(){


        assert managerContainer.startContainer()
        ArrayList<String> cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.installPath}/harbor ; docker-compose down ; echo status: \$?", 120 )
        assert cmdOutput.last() == "status: 0": "Error downing harbor:" + cmdOutput.join("\n")


        cmdOutput = managerContainer.runBashCommandInContainer("cd ${managerContainer.basePath} ; ls -I data -I install | wc -l", 5 )
        if (cmdOutput != ["0"]) {

            log.warn("\tNot cleaning up base path ${managerContainer.basePath}, found unexpected content")
        }else {
            cmdOutput = managerContainer.runBashCommandInContainer("rm -rf ${managerContainer.basePath} ; echo status: \$?", 120 )
            assert cmdOutput.last() == "status: 0": "Error cleaning up base path ${managerContainer.basePath}: " + cmdOutput.join("\n")
        }

        return managerContainer.stopAndRemoveContainer()


    }

    /**
     * Gets all containers running a Harbor image and the harbor manager container.
     * NOTE: this is a soft check that likely will return false positives
     * @return
     */
    ArrayList<ContainerSummary>getHarborContainers() {

        ArrayList<ContainerSummary> containers = managerContainer.dockerClient.ps().content

        containers = containers.findAll { it.image.startsWith("goharbor") || it.names.first() == managerContainer.containerName}

        return containers

    }

}
