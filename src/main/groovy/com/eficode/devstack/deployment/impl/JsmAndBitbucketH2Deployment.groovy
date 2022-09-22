package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.BitbucketContainer
import com.eficode.devstack.container.impl.JsmContainer
import com.eficode.devstack.deployment.Deployment

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class JsmAndBitbucketH2Deployment implements Deployment{

    String friendlyName = "JIRA and Bitbucket H2 Deployment"
    String containerNetworkName = "jsm_and_bitbucket"
    ArrayList<Deployment> subDeployments = []

    Map<String, String> jiraAppsToInstall = [:]
    String jiraLicense
    String jiraBaseUrl
    JiraInstanceManagerRest jiraRest

    String bitbucketBaseUrl
    BitbucketInstanceManagerRest bitbucketRest
    String bitbucketLicense

    JsmAndBitbucketH2Deployment(String jiraBaseUrl, String bitbucketBaseUrl) {

        this.jiraBaseUrl = jiraBaseUrl
        this.jiraRest = new JiraInstanceManagerRest(jiraBaseUrl)

        this.bitbucketBaseUrl = bitbucketBaseUrl
        this.bitbucketRest = new BitbucketInstanceManagerRest(bitbucketBaseUrl)


        this.subDeployments = [new JsmH2Deployment(jiraBaseUrl), new BitbucketH2Deployment(bitbucketBaseUrl)]




    }

    ArrayList<Container>getContainers() {
        return [jsmContainer, bitbucketContainer]
    }

    void setContainers(ArrayList<Container> containers) {
        this.containers = containers
    }

    JsmH2Deployment getJsmH2Deployment() {
        return subDeployments.find{it instanceof JsmH2Deployment} as JsmH2Deployment
    }

    JsmContainer getJsmContainer() {
        return jsmH2Deployment.jsmContainer
    }

    BitbucketH2Deployment getBitbucketH2Deployment() {
        return subDeployments.find{it instanceof BitbucketH2Deployment} as BitbucketH2Deployment
    }

    BitbucketContainer getBitbucketContainer() {
        return bitbucketH2Deployment.bitbucketContainer
    }

    void setJiraLicense(String licenseText) {
        this.jiraLicense = licenseText
    }

    void setBitbucketLicense(String licenseText) {

        this.bitbucketLicense = licenseText
    }

    /**
     * Install apps in to JIRA
     * @param appsAndLicenses key = App url (from marketplace), value = license string (optional)
     * @return true if no apps where installed, or apps where installed successfully
     */
    /*
    boolean installJiraApps(Map<String,String> appsAndLicenses = jiraAppsToInstall) {

        if (appsAndLicenses) {
            log.info("Installing ${appsAndLicenses.size()} app(s)")
            appsAndLicenses.each {url, license ->
                assert jiraRest.installApp(url, license) : "Error installing app:" + url
            }
        }

        return true

    }

     */



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

    boolean setupDeployment() {

        log.info("Setting up deployment:" + friendlyName)

        assert jiraLicense : "Error no Jira License has been setup"
        assert bitbucketLicense : "Error no Bitbucket License has been setup"

        jsmH2Deployment.setJiraLicense(new File(jiraLicense))
        bitbucketH2Deployment.setBitbucketLicence(new File(bitbucketLicense))

        jsmH2Deployment.deploymentNetworkName = this.containerNetworkName
        bitbucketH2Deployment.deploymentNetworkName = this.containerNetworkName

        ExecutorService threadPool = Executors.newFixedThreadPool(2)
        Future jsmFuture = threadPool.submit(new SetupDeploymentTask(jsmH2Deployment))
        Future bitbucketFuture = threadPool.submit(new SetupDeploymentTask(bitbucketH2Deployment))
        threadPool.shutdown()


        while(!jsmFuture.done || !bitbucketFuture.done) {
            log.info("Waiting for deployments to finish")
            log.info("\tJSM Finished:" + jsmFuture.done)
            log.info("\tBitbucket Finished:" + bitbucketFuture.done)

            if(bitbucketFuture.done) {
                log.info("\tBitbucket deployment finished successfully:" + bitbucketFuture.get())
            }

            if(jsmFuture.done) {
                log.info("\tJSM deployment finished successfully:" + jsmFuture.get())
            }

            sleep(5000)
        }
        if(bitbucketFuture.done) {
            log.info("\tBitbucket deployment finished successfully:" + bitbucketFuture.get())
        }

        if(jsmFuture.done) {
            log.info("\tJSM deployment finished successfully:" + jsmFuture.get())
        }

        return jsmFuture.get() && bitbucketFuture.get()

    }

    @Override
    void setupSecureDockerConnection(String host, String certPath) {

        subDeployments.each {deployment ->
            deployment.setupSecureDockerConnection(host, certPath)
        }
    }

    /**
     * Install apps in to JIRA
     * @param appsAndLicenses key = App url (from marketplace), value = license string (optional)
     * @return true if no apps where installed, or apps where installed successfully
     */
    boolean installApps(Map<String,String> appsAndLicenses = jiraAppsToInstall ) {

        if (appsAndLicenses) {
            log.info("Installing ${appsAndLicenses.size()} app(s)")
            appsAndLicenses.each {url, license ->
                assert jiraRest.installApp(url, license) : "Error installing app:" + url
            }
        }

        return true

    }



}