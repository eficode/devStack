package com.eficode.devstack.container.impl

class GroovyDoodContainer extends DoodContainer{

    String containerName = "GroovyDood"
    String containerMainPort = ""
    String containerImage = "groovy"
    String containerImageTag = "jdk11-jammy"



    @Override
    boolean runAfterDockerSetup() {
        return changeDockerSockOwner()
    }

}

