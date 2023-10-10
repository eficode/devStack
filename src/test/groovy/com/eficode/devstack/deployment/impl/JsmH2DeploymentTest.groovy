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


        DevStackSpec.log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)


        cleanupContainerNames = ["jira.domain.se", "jira2.domain.se", "localhost"]
        cleanupContainerPorts = [8080, 8082, 80]

        disableCleanup = false


    }

    def "test setupDeployment"(String baseurl, String port, String dockerHost, String certPath) {
        setup:

        JsmH2Deployment jsmDep = new JsmH2Deployment(baseurl, dockerHost, certPath)


        jsmDep.setJiraLicense(new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license").text)
        jsmDep.appsToInstall = [
                "https://marketplace.atlassian.com/download/apps/1211542/version/302030": ""
        ]

        when:

        boolean setupSuccess = jsmDep.setupDeployment()
        then:
        setupSuccess
        Unirest.get(baseurl).asEmpty().status == 200
        jsmDep.jsmContainer.inspectContainer().networkSettings.ports.find { it.key == "$port/tcp" }

        //Make sure websudo was disabled
        jsmDep.jsmContainer.runBashCommandInContainer("cat jira-config.properties").find {it == "jira.websudo.is.disabled=true"}
        jsmDep.jsmContainer.containerLogs.find {it.matches(".*jira.websudo.is.disabled.*:.*true.*")}



        where:
        baseurl                       | port   | dockerHost       | certPath
        "http://localhost"            | "80"   | ""               | ""
        "http://jira2.domain.se:8082" | "8082" | dockerRemoteHost | dockerCertPath
        "http://jira.domain.se:8080"  | "8080" | dockerRemoteHost | dockerCertPath

    }


}
