package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.BitbucketContainer
import com.eficode.devstack.deployment.Deployment
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BitbucketH2Deployment implements Deployment {

    Logger log = LoggerFactory.getLogger(this.class)
    String friendlyName = "Bitbucket H2 Deployment"
    BitbucketInstanceManagerRest bitbucketRest
    ArrayList<Container> containers = []

    String bitbucketLicense
    String bitbucketBaseUrl

    BitbucketH2Deployment(String bitbucketBaseUrl, String dockerHost = "", String dockerCertPath = "") {

        this.bitbucketBaseUrl = bitbucketBaseUrl
        this.bitbucketRest = new BitbucketInstanceManagerRest(bitbucketBaseUrl)
        this.containers = [new BitbucketContainer(bitbucketBaseUrl, dockerHost, dockerCertPath)]
        bitbucketContainer.containerName = bitbucketContainer.extractDomainFromUrl(bitbucketBaseUrl)
        bitbucketContainer.containerMainPort = bitbucketContainer.extractPortFromUrl(bitbucketBaseUrl)

    }

    BitbucketContainer getBitbucketContainer() {
        return containers.find { it instanceof BitbucketContainer } as BitbucketContainer
    }

    String getBitbucketContainerId() {
        return bitbucketContainer.id
    }

    void setBitbucketLicence(File licenseFile) {
        bitbucketLicense = licenseFile.text
    }

    void setBitbucketLicence(String licenseText) {
        bitbucketLicense = licenseText
    }


    boolean setupDeployment() {

        log.info("Setting up deployment:" + friendlyName)

        assert bitbucketLicense: "Error no Bitbucket License has been setup"


        bitbucketContainer.containerDefaultNetworks = [this.deploymentNetworkName]
        bitbucketContainer.createContainer()

        log.info("\tCreated Bitbucket container:" + bitbucketContainer.id)


        assert bitbucketContainer.startContainer(): "Error starting Bitbucket container:" + bitbucketContainer.id
        log.info("\tStarted Bitbucket container")


        log.info("\tSetting up local H2 database, License, Name etc")
        assert bitbucketRest.setApplicationProperties(bitbucketLicense, "Bitbucket", bitbucketBaseUrl): "Error setting up H2 database for Bitbucket"
        log.info("\t\tApplication basics setup successfully")


        log.info("\tBitbucket deployment finished, you should now be able to login")
        log.info("\t\tUrl:" + bitbucketBaseUrl)
        log.info("\t\tUsername:" + bitbucketRest.adminUsername)
        log.info("\t\tPassword:" + bitbucketRest.adminPassword)

        return true

    }
}
