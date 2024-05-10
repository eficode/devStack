package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container

/**
 * A helper class intended to let you create arbitrary containers quickly
 */
class AbstractContainer implements Container {

    String containerName
    String containerMainPort = null
    String containerImage
    String containerImageTag
    String defaultShell


    AbstractContainer(String containerName, String containerMainPort, String containerImage, String containerImageTag, String defaultShell) {
        this.containerName = containerName
        this.containerMainPort = containerMainPort
        this.containerImage = containerImage
        this.containerImageTag = containerImageTag
        this.defaultShell = defaultShell
    }


}
