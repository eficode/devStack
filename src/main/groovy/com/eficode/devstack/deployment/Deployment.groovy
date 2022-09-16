package com.eficode.devstack.deployment

import com.eficode.devstack.container.Container
import org.slf4j.Logger
import org.slf4j.LoggerFactory

trait Deployment {

    static Logger log = LoggerFactory.getLogger(Deployment.class)
    abstract ArrayList<Container> containers
    abstract String friendlyName


    abstract boolean setupDeployment()

    void setupSecureDockerConnection(String host, String certPath) {

        log.info("Setting up secure connection to docker engine")
        assert getContainers() != null && !getContainers().empty: "Deployment has no containers defined"
        //assert getContainers().any{! it.ping()} : "Connection has already been established."
        //assert getContainers().created.each {!it} : "Cant setup secure connection when containers have already been created in docker engine"


        getContainers().each {
            assert it.setupSecureRemoteConnection(host, certPath): "Error setting up secure connection to docker engine"
            log.info("\tSecure connection setup for container:" + getFriendlyName())
        }
        log.info("\tSuccessfully setup secure connections to docker engine")
    }


    boolean startDeployment() {

        log.info("Starting deployment: " + this.friendlyName)

        this.containers.each { container ->
            log.debug("\tStarting:" + container.containerName)
            assert container.startContainer(): "Error starting container:" + container.containerId
        }

        log.info("\tFinished starting deployment")
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