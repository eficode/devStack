package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.JenkinsContainer
import com.eficode.devstack.deployment.Deployment

class JenkinsDeployment implements Deployment{

    String friendlyName = "Jenkins Deployment"
    ArrayList<Container> containers = []

    JenkinsDeployment(String baseUrl, String dockerHost = "", String dockerCertPath = "") {

        containers = [new JenkinsContainer(dockerHost, dockerCertPath)]
        jenkinsContainer.containerName  = JenkinsContainer.extractDomainFromUrl(baseUrl)

    }

    JenkinsContainer getJenkinsContainer() {
        return  containers.find {it instanceof JenkinsContainer} as JenkinsContainer
    }

    @Override
    boolean setupDeployment() {

        jenkinsContainer.createContainer()
        jenkinsContainer.startContainer()
        log.info("Jenkins has started")
        log.info("\tInitial admin password is:" + jenkinsContainer?.initialAdminPassword)

    }


}
