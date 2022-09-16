package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.BitbucketContainer
import com.eficode.devstack.container.impl.JsmContainer
import com.eficode.devstack.deployment.Deployment

class JsmAndBitbucketH2Deployment implements Deployment{

    String friendlyName = "JIRA and Bitbucket H2 Deployment"
    ArrayList<Container> containers = []

    Map<String, String> jiraAppsToInstall = [:]
    String jiraLicense
    String jiraBaseUrl
    JiraInstanceManagerRest jiraRest

    String bitbucketBaseUrl
    BitbucketInstanceManagerRest bitbucketRest

    JsmAndBitbucketH2Deployment(String jiraBaseUrl, String bitbucketBaseUrl) {

        this.jiraBaseUrl = jiraBaseUrl
        this.jiraRest = new JiraInstanceManagerRest(jiraBaseUrl)

        this.bitbucketBaseUrl = bitbucketBaseUrl
        this.bitbucketRest = new BitbucketInstanceManagerRest(bitbucketBaseUrl)

        log.info("jira:" + this.jiraRest.baseUrl)
        log.info("bitbucket:" + this.bitbucketRest.baseUrl)
        this.containers = [new JsmContainer(), new BitbucketContainer()]



    }

    JsmContainer getJsmContainer() {
        return containers.find{it instanceof JsmContainer} as JsmContainer
    }

    BitbucketContainer getBitbucketContainer() {
        return containers.find {it instanceof BitbucketContainer} as BitbucketContainer
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
    boolean installJiraApps(Map<String,String> appsAndLicenses = jiraAppsToInstall) {

        if (appsAndLicenses) {
            log.info("Installing ${appsAndLicenses.size()} app(s)")
            appsAndLicenses.each {url, license ->
                assert jiraRest.installApp(url, license) : "Error installing app:" + url
            }
        }

        return true

    }

    boolean setupDeployment() {

        log.info("Setting up deployment:" + friendlyName)

        assert jiraLicense : "Error no Jira License has been setup"

        jsmContainer.createContainer()
        log.info("\tCreated jsm container:" + jsmContainer.id)
        assert jsmContainer.startContainer() : "Error starting JSM container:" + jsmContainer.id
        log.info("\tStarted JSM container")

        log.info("\tSetting up local H2 database")
        assert jiraRest.setupH2Database() : "Error setting up H2 database for JSM"
        log.info("\t\tDatabase setup successfully")
        log.info("\tSetting up application properties and Jira license")
        assert jiraRest.setApplicationProperties(jiraLicense, "JIRA", jiraBaseUrl)
        log.info("\t\tLicense and properties setup successfully")

        if(jiraAppsToInstall) {
            installApps()
        }



        return false

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
