package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.core.Frame
import de.gesellix.docker.remote.api.core.StreamCallback
import kong.unirest.Unirest
import kong.unirest.UnirestInstance
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeoutException

class JenkinsContainerTest extends DevStackSpec{

    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "~/.docker/"

        DevStackSpec.log = LoggerFactory.getLogger(JenkinsContainerTest.class)


        cleanupContainerNames = ["jenkins.domain.se", "jenkins-agent.domain.se", "localhost"]
        cleanupContainerPorts = [8080, 50000]

    }

    def "Test the basics"(String dockerHost, String certPath, String baseUrl) {

        when:
        JenkinsContainer jc = new JenkinsContainer(dockerHost, certPath)
        jc.containerName = JenkinsContainer.extractDomainFromUrl(baseUrl)
        String containerId = jc.createContainer()


        then:
        containerId == jc.id
        jc.inspectContainer().name == "/" + JenkinsContainer.extractDomainFromUrl(baseUrl)
        jc.status() == ContainerState.Status.Created
        jc.startContainer()
        jc.status() == ContainerState.Status.Running

        when:
        long start = System.currentTimeMillis()
        //String baseUrl = "http://" + jc.containerName + ":" + jc.containerMainPort + "/login"

        DevStackSpec.log.info("Waiting for Jenkins WEB-UI to become responsive")



        //jc.dockerClient.info()
        //ContainerCallback callBack = new ContainerCallback()
        //jc.dockerClient.logs(jc.id, null,callBack, Duration.ofMillis(1))
        //callBack.output.each {log.info("LOCAL:" + it)}




        then:
        UnirestInstance unirestInstance =  Unirest.spawnInstance()
        while (true) {
            if (start + (2 * 60000 ) > System.currentTimeMillis()) {
                try {
                    int status = unirestInstance.get(baseUrl + "/login").socketTimeout(5000).connectTimeout(10000).asEmpty()?.status
                    if (status == 200){
                        DevStackSpec.log.info("\tJenkins is ready and responded with HTTP status:" + status + " after " + ((System.currentTimeMillis() - start)/1000).round() + "s")
                        break
                    }
                    else {
                        DevStackSpec.log.info("\tJenkins responded with HTTP status:" + status)
                        sleep(2000)
                    }
                }catch(ex) {
                    DevStackSpec.log.warn("\tError accessing Jenkins WEB-UI:" + ex.message)
                    sleep(2000)
                }
            }else {
                DevStackSpec.log.error("\tTimed our waiting for Jenkins after:" + ((System.currentTimeMillis() - start)/1000).round() + "s")
                throw new TimeoutException("Error waiting for Jenkins WEB to become available:" + baseUrl)
            }

        }
        unirestInstance.shutDown()

        when:
        ArrayList<String> logRows = jc.containerLogs
        String initialAdminPw = jc.initialAdminPassword

        then:
        initialAdminPw != null
        logRows.find {it.startsWith(initialAdminPw)}



        where:
        dockerHost       | certPath       | baseUrl
        ""               | ""             | "http://localhost:8080"
        dockerRemoteHost | dockerCertPath | "http://jenkins.domain.se:8080"




    }

    static class ContainerCallback<T> implements StreamCallback<T> {

        ArrayList<String> output = []

        @Override
        void onNext(Object o) {
            if (o instanceof Frame) {
                output.add(o.payloadAsString)
            } else {
                output.add(o.toString())
            }

        }
    }
}
