package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import kong.unirest.Unirest
import org.slf4j.LoggerFactory
import spock.lang.Shared

class JsmH2DeploymentTest extends DevStackSpec {




    @Shared
    File projectRoot = new File(".")

    def setupSpec() {

        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"


        log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)


        cleanupContainerNames = ["jira.domain.se", "jira2.domain.se", "localhost"]
        cleanupContainerPorts = [8080, 8082, 80]

        disableCleanup = false


    }

    def "test setupDeployment"(String baseurl, String port, String dockerHost, String certPath) {
        setup:

        JsmH2Deployment jsmDep = new JsmH2Deployment(baseurl, dockerHost, certPath)


        jsmDep.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license"))
        jsmDep.appsToInstall = [
                "https://marketplace.atlassian.com/download/apps/1211542/version/302030": ""
        ]

        when:

        boolean setupSuccess = jsmDep.setupDeployment()
        then:
        setupSuccess
        Unirest.get(baseurl).asEmpty().status == 200
        jsmDep.jsmContainer.inspectContainer().networkSettings.ports.find { it.key == "$port/tcp" }


        where:
        baseurl                       | port   | dockerHost       | certPath
        "http://localhost"            | "80"   | ""               | ""
        "http://jira2.domain.se:8082" | "8082" | dockerRemoteHost | dockerCertPath
        "http://jira.domain.se:8080"  | "8080" | dockerRemoteHost | dockerCertPath

    }


}
