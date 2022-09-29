package com.eficode.devstack.deployment.impl

import com.eficode.devstack.DevStackSpec
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.remote.api.ContainerInspectResponse
import kong.unirest.HttpResponse
import kong.unirest.Unirest
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

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

        containerNames = ["jira.domain.se", "jira2.domain.se"]
        containerPorts = [8080, 8082]

        disableCleanupAfter = false
        dockerClient = resolveDockerClient()

    }

    def "test setupDeployment"() {
        setup:

        JsmH2Deployment jsmDep = new JsmH2Deployment(jiraBaseUrl)
        jsmDep.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)

        jsmDep.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license"))
        jsmDep.appsToInstall = [
                "https://marketplace.atlassian.com/download/apps/1211542/version/302030": ""
        ]

        when:

        boolean setupSuccess = jsmDep.setupDeployment()
        then:
        setupSuccess

    }


    def "test non default domain name and port"() {

        setup:
        String
        JsmH2Deployment jsmDep = new JsmH2Deployment(jira2BaseUrl)
        jsmDep.setupSecureDockerConnection(dockerRemoteHost, dockerCertPath)
        jsmDep.setJiraLicense(new File(projectRoot.path + "/resources/jira/licenses/jsm.license"))

        expect:
        jsmDep.setupDeployment()
        ContainerInspectResponse inspectResponse = jsmDep.jsmContainer.inspectContainer()

        inspectResponse.networkSettings.ports.find {it.key == "8082/tcp"}
        Unirest.get(jira2BaseUrl).asEmpty().status == 200

    }


}
