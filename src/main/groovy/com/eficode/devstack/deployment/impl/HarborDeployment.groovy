package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.DoodContainer
import com.eficode.devstack.container.impl.HarborManagerContainer
import com.eficode.devstack.deployment.Deployment

class HarborDeployment implements Deployment{

    String friendlyName = "Harbor Deployment"
    ArrayList<Container> containers = []


    HarborDeployment(String baseUrl,  String harborVersion = "v2.6.0") {


        HarborManagerContainer managerContainer = new HarborManagerContainer(baseUrl, harborVersion)
        this.containers = [managerContainer]


    }

    DoodContainer getManagerContainer() {
        this.containers.find {it instanceof HarborManagerContainer} as DoodContainer
    }

    @Override
    boolean setupDeployment() {

        managerContainer.createContainer(["sleep", "infinity"], [])
        managerContainer.startContainer()
    }
}
