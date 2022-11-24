package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.BitbucketContainer
import com.eficode.devstack.container.impl.JsmContainer
import com.eficode.devstack.deployment.Deployment
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class JsmAndBitbucketH2Deployment implements Deployment {


    String friendlyName = "JIRA and Bitbucket H2 Deployment"
    String containerNetworkName = "jsm_and_bitbucket"
    ArrayList<Deployment> subDeployments = []
    Logger log = LoggerFactory.getLogger(this.class)

    Map<String, String> jiraAppsToInstall = [:]
    String jiraLicense
    String jiraBaseUrl
    JiraInstanceManagerRest jiraRest

    String bitbucketBaseUrl
    BitbucketInstanceManagerRest bitbucketRest
    String bitbucketLicense

    JsmAndBitbucketH2Deployment(String jiraBaseUrl, String bitbucketBaseUrl, String dockerHost = "", String dockerCertPath = "") {

        this.jiraBaseUrl = jiraBaseUrl
        this.jiraRest = new JiraInstanceManagerRest(jiraBaseUrl)

        this.bitbucketBaseUrl = bitbucketBaseUrl
        this.bitbucketRest = new BitbucketInstanceManagerRest(bitbucketBaseUrl)


        this.subDeployments = [new JsmH2Deployment(jiraBaseUrl, dockerHost, dockerCertPath), new BitbucketH2Deployment(bitbucketBaseUrl, dockerHost, dockerCertPath)]


    }

    ArrayList<Container> getContainers() {
        return [jsmContainer, bitbucketContainer]
    }

    void setContainers(ArrayList<Container> containers) {
        this.containers = containers
    }

    JsmH2Deployment getJsmH2Deployment() {
        return subDeployments.find { it instanceof JsmH2Deployment } as JsmH2Deployment
    }

    JsmContainer getJsmContainer() {
        return jsmH2Deployment.jsmContainer
    }

    BitbucketH2Deployment getBitbucketH2Deployment() {
        return subDeployments.find { it instanceof BitbucketH2Deployment } as BitbucketH2Deployment
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

        assert jiraLicense: "Error no Jira License has been setup"
        assert bitbucketLicense: "Error no Bitbucket License has been setup"

        jsmH2Deployment.setJiraLicense(new File(jiraLicense))
        bitbucketH2Deployment.setBitbucketLicence(new File(bitbucketLicense))

        jsmH2Deployment.deploymentNetworkName = this.containerNetworkName
        bitbucketH2Deployment.deploymentNetworkName = this.containerNetworkName
        jsmContainer.createBridgeNetwork(this.containerNetworkName)

        ExecutorService threadPool = Executors.newFixedThreadPool(2)
        Future jsmFuture = threadPool.submit(new SetupDeploymentTask(jsmH2Deployment))
        Future bitbucketFuture = threadPool.submit(new SetupDeploymentTask(bitbucketH2Deployment))
        threadPool.shutdown()


        while (!jsmFuture.done || !bitbucketFuture.done) {
            log.info("Waiting for deployments to finish")
            log.info("\tJSM Finished:" + jsmFuture.done)
            log.info("\tBitbucket Finished:" + bitbucketFuture.done)

            if (bitbucketFuture.done) {
                log.info("\tBitbucket deployment finished successfully:" + bitbucketFuture.get())
            }

            if (jsmFuture.done) {
                log.info("\tJSM deployment finished successfully:" + jsmFuture.get())
            }

            sleep(5000)
        }
        if (bitbucketFuture.done) {
            log.info("\tBitbucket deployment finished successfully:" + bitbucketFuture.get())
        }

        if (jsmFuture.done) {
            log.info("\tJSM deployment finished successfully:" + jsmFuture.get())
        }

        if (jiraAppsToInstall) {
            log.info("\tInstalling user defined JIRA Apps")
            assert installJiraApps(): "Error installing user defined JIRA apps"
            log.info("\t\tFinished installing user defined JIRA Apps")
        }

        if (!jiraAppsToInstall.any { it.key.contains("JiraShortcuts") }) {
            log.info("\tInstalling JiraShortcuts app") //Needed for setting up applink to bitbucket
            assert installJiraApps(
                    [
                            "https://github.com/eficode/JiraShortcuts/raw/packages/repository/com/eficode/atlassian/jira/jiraShortcuts/2.0.1-SNAPSHOT-groovy-3.0/jiraShortcuts-2.0.1-SNAPSHOT-groovy-3.0.jar":""
                    ]
            ) : "Error installing JiraShortcuts JIRA apps"
            log.info("\t\tFinished installing JiraShortcuts JIRA Apps")
        }

        if (jiraRest.scriptRunnerIsInstalled()) {
            log.info("\tSetting up application link between JIRA and Bitbucket")


            String appLinkScript = getClass().getResourceAsStream("/com/eficode/devstack/deployment/jira/scripts/CreateBitbucketLink.groovy").text
            appLinkScript = appLinkScript.replaceAll("BITBUCKET_URL", bitbucketBaseUrl)
            appLinkScript = appLinkScript.replaceAll("BITBUCKET_USER", "admin")
            appLinkScript = appLinkScript.replaceAll("BITBUCKET_PASSWORD", "admin")




            log.trace("\t\tUsing Script:")
            appLinkScript.eachLine {line ->
                log.trace("\t"*3 + line)
            }

            Map appLinkResult = jiraRest.executeLocalScriptFile(appLinkScript)
            log.debug("\t\tFinished executing application link script")
            log.trace("\t"* 3 + "Script returned logs:")
            appLinkResult.log.each {log.trace("\t"*4 + it)}

            assert appLinkResult.log.any {it.contains("Created link:Bitbucket")}  : "Error creating application link from JIRA to bitbucket"
            assert appLinkResult.success : "Error creating application link from JIRA to bitbucket"
            log.info("\tFinished setting up application between JIRA and Bitbucket successfully")
        }


        return jsmFuture.get() && bitbucketFuture.get()

    }

    /*
    @Override
    void setupSecureDockerConnection(String host, String certPath) {

        subDeployments.each { deployment ->
            deployment.setupSecureDockerConnection(host, certPath)
        }
    }

     */

    /**
     * Install apps in to JIRA
     * @param appsAndLicenses key = App url (from marketplace), value = license string (optional)
     * @return true if no apps where installed, or apps where installed successfully
     */
    boolean installJiraApps(Map<String, String> appsAndLicenses = jiraAppsToInstall) {

        if (appsAndLicenses) {
            log.info("Installing ${appsAndLicenses.size()}  jiraapp(s)")
            appsAndLicenses.each { url, license ->
                assert jiraRest.installApp(url, license): "Error installing app:" + url
            }
        }

        return true

    }


}
