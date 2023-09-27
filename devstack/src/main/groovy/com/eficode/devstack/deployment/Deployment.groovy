package com.eficode.devstack.deployment

import com.eficode.devstack.container.Container
import de.gesellix.docker.remote.api.core.Frame
import de.gesellix.docker.remote.api.core.StreamCallback
import org.slf4j.Logger
import org.slf4j.LoggerFactory

trait Deployment {


    Logger log  = LoggerFactory.getLogger(this.class)
    abstract ArrayList<Container> containers
    abstract String friendlyName
    String deploymentNetworkName = "bridge"


    abstract boolean setupDeployment()

    boolean startDeployment() {

        log.info("Starting deployment: " + this.getFriendlyName())


        this.getContainers().each { container ->
            log.debug("\tStarting:" + container.containerName)
            assert container.startContainer(): "Error starting container:" + container.containerId
        }

        log.info("\tFinished starting deployment")
        return true

    }

    boolean stopAndRemoveDeployment() {

        log.info("Stopping and removing deployment: " + this.getFriendlyName())


        this.getContainers().findAll {it != null}.each { container ->
            log.debug("\tStopping container:" + container.containerName)
            assert container.stopAndRemoveContainer(0): "Error stopping container:" + container.containerId
        }

        log.info("\tFinished stopping deployment")
        return true

    }

    boolean stopDeployment() {
        log.info("Stopping deployment: " + this.getFriendlyName())


        this.getContainers().each { container ->
            log.debug("\tStopping container:" + container.containerName)
            assert container.stopContainer(): "Error stopping container:" + container.containerId
        }

        log.info("\tFinished stopping deployment")
        return true

    }

    boolean removeDeployment() {
        log.info("Removing deployment: " + this.getFriendlyName())


        getContainers().each { container ->

            log.debug("\tRemoving container:" + container.containerName)
            container.stopAndRemoveContainer()

        }

        log.info("\tFinished removing deployment")
        return true
    }
}