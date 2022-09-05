package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.jiraInstanceManger.JiraInstanceMangerRest
import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.JsmContainer
import com.eficode.devstack.deployment.Deployment

class JsmH2Deployment implements Deployment{

    String friendlyName = "JIRA H2 Deployment"
    JiraInstanceMangerRest jiraRest
    ArrayList<Container> containers = []
    Map<String,String> appsToInstall = [:]
    String jiraLicense

    String jiraBaseUrl

    JsmH2Deployment(String jiraBaseUrl) {
        this.jiraBaseUrl = jiraBaseUrl
        this.jiraRest = new JiraInstanceMangerRest(jiraBaseUrl)
        this.containers = [new JsmContainer()]
    }

    JsmContainer getJsmContainer() {
        return containers.find {it instanceof JsmContainer} as JsmContainer
    }

    String getJsmContainerId() {
        return jsmContainer.id
    }



    void setJiraLicense(File licenseFile) {
        jiraLicense = licenseFile.text
    }

    void setJiraLicense(String licenseText) {
        jiraLicense = licenseText
    }

    /**
     * Install apps in to JIRA
     * @param appsAndLicenses key = App url (from marketplace), value = license string (optional)
     * @return true if no apps where installed, or apps where installed successfully
     */
    boolean installApps(Map<String,String> appsAndLicenses = appsToInstall) {

        if (appsAndLicenses) {
            log.info("Installing ${appsAndLicenses.size()} app(s)")
            appsAndLicenses.each {url, license ->
                assert jiraRest.installApp(url, license) : "Error installing app:" + url
            }
        }

        return true

    }

    /**
     * Uploads multiple new, or updates existing script files on the JIRA server
     * @param srcDest A map where the key is a source file or folder, and value is destination file or folder, ex:
     *     <p>"../src/someDir/someSubPath/"             :   "someDir/someSubPath/"
     *     <p>"../src/somOtherDir/SomeScript.groovy"    :   "somOtherDir/SomeScript.groovy"
     *
     * @return true on success
     */
    boolean updateScriptrunnerFiles(Map<String,String>srcDest) {
        jiraRest.updateScriptrunnerFiles(srcDest)
    }

    boolean setupDeployment() {
        log.info("Setting up deployment:" + friendlyName)

        assert jiraLicense : "Error no Jira License has been setup"

        jsmContainer.createContainer()
        log.info("\tCreated jsm container:" + jsmContainer.id)
        assert jsmContainer.startContainer() : "Error starting JSM container:" + jsmContainer.id
        log.info("\tStarted JSM container")

        log.debug("\tCreating folders needed for running Spoc tests with ScriptRunner")
        assert jsmContainer.runBashCommandInContainer("mkdir  /opt/atlassian/jira/surefire-reports ; chown jira:jira  /opt/atlassian/jira/surefire-reports").empty
        log.debug("\tUpdating apt and installing dependencies")
        jsmContainer.runBashCommandInContainer("apt update; apt install -y htop nano", 20)

        log.info("\tSetting up local H2 database")
        assert jiraRest.setupH2Database() : "Error setting up H2 database for JSM"
        log.info("\t\tDatabase setup successfully")
        log.info("\tSetting up application properties and Jira license")
        assert jiraRest.setApplicationProperties(jiraLicense, "JIRA", jiraBaseUrl)
        log.info("\t\tLicense and properties setup successfully")

       if(appsToInstall) {
           installApps()
       }


        log.info("\tJSM deployment finished, you should now be able to login")
        log.info("\t\tUrl:" + jiraBaseUrl)
        log.info("\t\tUsername:" + jiraRest.adminUsername)
        log.info("\t\tPassword:" + jiraRest.adminPassword)

        return true
    }




}