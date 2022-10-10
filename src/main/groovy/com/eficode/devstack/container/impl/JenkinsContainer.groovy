package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.remote.api.ContainerCreateRequest

import java.util.regex.Matcher

class JenkinsContainer implements Container{

    String containerName = "Jenkins"
    String containerMainPort = "8080"
    String containerImage = "jenkins/jenkins"
    String containerImageTag = "lts-jdk11"


    JenkinsContainer(){}

    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    JenkinsContainer(String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
    }

    /**
     * Looks in the container log after the initial admin password
     * @return
     */
    String getInitialAdminPassword() {
        assert !hasNeverBeenStarted() : "Container has never been started, cant retrieve password"

        Matcher matcher = containerLogs.join("\n") =~ /Please use the following password to proceed to installation:\n\n(.*)\n\nThis may also/

        return matcher.count && matcher[0].size() ? matcher[0][1] : null


    }

}
