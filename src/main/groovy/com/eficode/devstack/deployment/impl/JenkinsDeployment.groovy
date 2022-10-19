package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.JenkinsContainer
import com.eficode.devstack.deployment.Deployment
import kong.unirest.Unirest
import kong.unirest.UnirestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException

class JenkinsDeployment implements Deployment{

    Logger log = LoggerFactory.getLogger(this.class)
    String friendlyName = "Jenkins Deployment"
    ArrayList<Container> containers = []
    String baseUrl

    JenkinsDeployment(String jenkinsBaseUrl, String dockerHost = "", String dockerCertPath = "") {

        baseUrl = jenkinsBaseUrl
        containers = [new JenkinsContainer(dockerHost, dockerCertPath)]
        jenkinsContainer.containerName  = JenkinsContainer.extractDomainFromUrl(jenkinsBaseUrl)
        jenkinsContainer.containerMainPort = JenkinsContainer.extractPortFromUrl(jenkinsBaseUrl)



    }

    JenkinsContainer getJenkinsContainer() {
        return  containers.find {it instanceof JenkinsContainer} as JenkinsContainer
    }

    @Override
    boolean setupDeployment() {


        jenkinsContainer.containerDefaultNetworks = [this.deploymentNetworkName]
        jenkinsContainer.createContainer()

        boolean containerSetupSuccess = jenkinsContainer.startContainer()
        assert containerSetupSuccess : "Error starting Jenkins container"
        log.info("\tJenkins container has started")


        log.info("\tWaiting for Jenkins GUI to become responsive")

        UnirestInstance unirestInstance =  Unirest.spawnInstance()
        long start = System.currentTimeMillis()
        while (true) {
            if (start + (2 * 60000 ) > System.currentTimeMillis()) {
                try {
                    int status = unirestInstance.get(baseUrl + "/login").socketTimeout(5000).connectTimeout(10000).asEmpty()?.status
                    if (status == 200){
                        log.info("\tJenkins is ready and responded with HTTP status:" + status + " after " + ((System.currentTimeMillis() - start)/1000).round() + "s")
                        break
                    }
                    else {
                        log.info("\tJenkins responded with HTTP status:" + status)
                        sleep(2000)
                    }
                }catch(ex) {
                    log.warn("\tError accessing Jenkins WEB-UI:" + ex.message)
                    sleep(2000)
                }
            }else {
                log.error("\tTimed our waiting for Jenkins after:" + ((System.currentTimeMillis() - start)/1000).round() + "s")
                unirestInstance.shutDown()
                throw new TimeoutException("Error waiting for Jenkins WEB to become available:" + baseUrl)
            }

        }
        unirestInstance.shutDown()


        log.info("\tInitial admin password is:" + jenkinsContainer?.initialAdminPassword)

        return true
    }


}
