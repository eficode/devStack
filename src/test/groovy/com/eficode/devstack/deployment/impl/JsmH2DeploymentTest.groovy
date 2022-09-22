package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class JsmH2DeploymentTest extends DevStackSpec {


    @Shared
    String jiraBaseUrl = "http://jira.domain.se:8080"

    @Shared
    File projectRoot = new File(".")

    def setupSpec() {

        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "resources/dockerCert"

        dockerClient = resolveDockerClient()

        log = LoggerFactory.getLogger(JsmH2DeploymentTest.class)

        dockerClient = resolveDockerClient()

        containerNames = ["jira.domain.se"]
        containerPorts = [8080]


        dockerClient = resolveDockerClient()
        dockerClient.stop("JSM")
        dockerClient.rm("JSM")
    }

    def "test setupDeployment"() {
        setup:

        JsmH2Deployment jsmDep = new JsmH2Deployment(jiraBaseUrl)
        jsmDep.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)

        jsmDep.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license"))
        jsmDep.appsToInstall = [
                "https://marketplace.atlassian.com/download/apps/6820/version/1005740"  : new File(projectRoot.path + "/resources/jira/licenses/scriptrunnerForJira.license").text,
                "https://marketplace.atlassian.com/download/apps/6572/version/1311472"  : new File(projectRoot.path + "/resources/jira/licenses/tempoTimeSheets.license").text,
                "https://marketplace.atlassian.com/download/apps/1211542/version/302030": ""
        ]

        when:
        boolean setupSuccess = jsmDep.setupDeployment()
        then:
        setupSuccess

        //cleanup:

        //jsmDep.containers.each {it.stopAndRemoveContainer()}
    }





}
