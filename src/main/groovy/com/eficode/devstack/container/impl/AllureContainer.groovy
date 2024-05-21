package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.remote.api.Volume

class AllureContainer implements Container {

    String containerName = "Alpine"
    String containerMainPort = "5050"
    String containerImage = "frankescobar/allure-docker-service"
    String containerImageTag = "latest"
    String defaultShell = "/bin/bash"


    AllureContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }

    void setResultsVolume(String volumeName) {
        Volume volume = dockerClient.getOrCreateVolume(volumeName)
        prepareVolumeMount(volume.name, "/app/allure-results", false)
    }


}
