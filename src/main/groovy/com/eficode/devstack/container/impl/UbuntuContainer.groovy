package com.eficode.devstack.container.impl

class UbuntuContainer extends AlpineContainer{

    String containerName = "Ubuntu"
    String containerImage = "ubuntu"
    String defaultShell = "/bin/bash"


    UbuntuContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }
}
