package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.remote.api.ContainerCreateRequest

class AllureContainer implements Container {

    String containerName = "Alpine"
    String containerMainPort = "5050"
    String containerImage = "frankescobar/allure-docker-service"
    String containerImageTag = "latest"
    String defaultShell = "/bin/bash"
    String user = "2001:2001"


    AllureContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }


}
