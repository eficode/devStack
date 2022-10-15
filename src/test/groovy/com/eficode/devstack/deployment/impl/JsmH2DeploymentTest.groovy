package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import kong.unirest.Unirest
import org.slf4j.LoggerFactory
import spock.lang.Shared

class JsmH2DeploymentTest extends DevStackSpec {


    @Shared
    String jiraBaseUrl = "http://jira.domain.se:8080"

    @Shared
    String jira2BaseUrl = "http://jira2.domain.se:8082"


    @Shared
    File projectRoot = new File(".")

    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"

        dockerClient = resolveDockerClient()

        log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)

        dockerClient = resolveDockerClient()

        cleanupContainerNames = ["jira.domain.se", "jira2.domain.se"]
        cleanupContainerPorts = [8080, 8082]

        disableCleanup = false


    }

    def "test setupDeployment"(String baseurl, String port) {
        setup:

        JsmH2Deployment jsmDep = new JsmH2Deployment(baseurl)
        jsmDep.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)

        jsmDep.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license"))
        jsmDep.appsToInstall = [
                "https://marketplace.atlassian.com/download/apps/1211542/version/302030": ""
        ]

        when:

        boolean setupSuccess = jsmDep.setupDeployment()
        then:
        setupSuccess
        Unirest.get(baseurl).asEmpty().status == 200
        jsmDep.jsmContainer.inspectContainer().networkSettings.ports.find {it.key == "$port/tcp"}



        where:
        baseurl | port
        jira2BaseUrl | "8082"
        jiraBaseUrl | "8080"

    }





}
