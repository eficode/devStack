package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.HarborManagerContainer
import com.eficode.devstack.container.impl.JenkinsContainer
import com.eficode.devstack.deployment.Deployment

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class JenkinsAndHarborDeployment implements Deployment {

    String friendlyName = "Jenkins and Harbor Deployment"
    String deploymentNetworkName = "jenkins_and_harbor"
    ArrayList<Deployment> subDeployments = []

    JenkinsAndHarborDeployment(String jenkinsBaseUrl, String harborBaseUrl, String dockerHost = "", String dockerCertPath = "") {

        subDeployments = [
                new JenkinsDeployment(jenkinsBaseUrl, dockerHost, dockerCertPath),
                new HarborDeployment(harborBaseUrl, "v2.6.0", "/tmp/", dockerHost, dockerCertPath)
        ]


    }

    private class SetupDeploymentTask implements Callable<Boolean> {

        Deployment deployment

        SetupDeploymentTask(Deployment deployment) {
            this.deployment = deployment
        }

        @Override
        Boolean call() throws Exception {
            this.deployment.setupDeployment()
        }
    }

    JenkinsDeployment getJenkinsDeployment() {
        return subDeployments.find { it instanceof JenkinsDeployment } as JenkinsDeployment
    }

    JenkinsContainer getJenkinsContainer() {
        return jenkinsDeployment.jenkinsContainer
    }

    HarborDeployment getHarborDeployment() {
        return subDeployments.find { it instanceof HarborDeployment } as HarborDeployment
    }

    HarborManagerContainer getHarborManagerContainer() {
        return harborDeployment.harborContainers
    }

    ArrayList<Container> getContainers() {
        return [jenkinsContainer, harborDeployment.harborContainers]
    }

    void setContainers(ArrayList<Container> containers) {
        this.containers = containers
    }


    boolean setupDeployment() {


        log.info("Setting up deployment:" + friendlyName)


        subDeployments.each { it.deploymentNetworkName = deploymentNetworkName }
        jenkinsContainer.createBridgeNetwork(this.deploymentNetworkName)

        ExecutorService threadPool = Executors.newFixedThreadPool(2)
        Future jenkinsFuture = threadPool.submit(new SetupDeploymentTask(jenkinsDeployment))
        Future harborFuture = threadPool.submit(new SetupDeploymentTask(harborDeployment))
        threadPool.shutdown()


        while (!jenkinsFuture.done || !harborFuture.done) {
            log.info("Waiting for deployments to finish")
            log.info("\tJenkins Finished:" + jenkinsFuture.done)
            log.info("\tHarbor Finished:" + harborFuture.done)

            if (harborFuture.done) {
                log.info("\tHarbor deployment finished successfully:" + harborFuture.get())
            }

            if (jenkinsFuture.done) {
                log.info("\tJenkins deployment finished successfully:" + jenkinsFuture.get())
            }

            sleep(5000)
        }
        if (harborFuture.done) {
            log.info("\tHarbor deployment finished successfully:" + harborFuture.get())
        }

        if (jenkinsFuture.done) {
            log.info("\tJenkins deployment finished successfully:" + jenkinsFuture.get())
        }


        //TODO fix this hardcoded true
        return true


    }



}
