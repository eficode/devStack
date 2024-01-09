package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.jiraInstanceManager.beans.MarketplaceApp
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

        disableCleanup = true


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
        jsmDep.jsmContainer.runBashCommandInContainer("cat jira-config.properties").find { it == "jira.websudo.is.disabled=true" }
        jsmDep.jsmContainer.containerLogs.find { it.matches(".*jira.websudo.is.disabled.*:.*true.*") }


        where:
        baseurl                       | port   | dockerHost       | certPath
        "http://localhost"            | "80"   | ""               | ""
        "http://jira2.domain.se:8082" | "8082" | dockerRemoteHost | dockerCertPath
        "http://jira.domain.se:8080"  | "8080" | dockerRemoteHost | dockerCertPath

    }

    def "test FakeTime"(String baseurl, String port, String dockerHost, String certPath) {
        setup:

        JsmH2Deployment jsmDep = new JsmH2Deployment(baseurl, dockerHost, certPath)

        String srLicense = new File(System.getProperty("user.home") + "/.licenses/jira/sr.license").text
        assert srLicense: "Error finding script runner license"


        MarketplaceApp srMarketApp = MarketplaceApp.searchMarketplace("Adaptavist ScriptRunner for JIRA", MarketplaceApp.Hosting.Datacenter).find { it.key == "com.onresolve.jira.groovy.groovyrunner" }
        MarketplaceApp.Version srVersion = srMarketApp?.getVersion("latest", MarketplaceApp.Hosting.Datacenter)

        jsmDep.setJiraLicense(new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license").text)
        jsmDep.appsToInstall.put(srVersion, srLicense)
        jsmDep.jsmContainer.enableJvmTimeTravel(true)
        when:


        boolean setupSuccess = jsmDep.setupDeployment(true, true)
        jsmDep.jiraRest.waitForSrToBeResponsive()
        String jvmArgs = jsmDep.jsmContainer.inspectContainer().config.env.find { it.startsWith("JVM_SUPPORT_RECOMMENDED_ARGS") }
        then:
        assert setupSuccess: "Error setting up JIRA"
        assert jvmArgs.contains("-XX:DisableIntrinsic=_currentTimeMillis"): "Container is missing expected env var"
        assert jvmArgs.contains("-XX:+UnlockDiagnosticVMOptions"): "Container is missing expected env var"
        assert jvmArgs.contains("-agentpath:"): "Container is missing expected env var"
        assert jsmDep.jsmContainer.runBashCommandInContainer("test -f /faketime.cpp && echo status: \$?").contains("status: 0"): "Could not find the expected file /faketime.cpp in the container "
        assert (new Date().toInstant().epochSecond - getJsmGroovyTime(jsmDep)).abs() < 5: "Time diff between JVM and localhost before any time traveling"


        when:
        log.info("Time traveling +60s")
        assert setOffset(jsmDep, 600): "Error setting offset"
        log.debug("\tSuccessfully set the time offset property")
        sleep(30 * 1000) //Just to be sure the property change has time to get picked up

        then:
        log.debug("\tVerifying the travel worked")
        assert (getJsmGroovyTime(jsmDep) - new Date().toInstant().epochSecond) > 50: "Time diff between JVM and localhost before any time traveling"
        log.debug("\tSuccessfully time traveled!")


        where:
        baseurl                      | port   | dockerHost       | certPath
        //"http://localhost"            | "80"   | ""               | ""
        //"http://jira2.domain.se:8082" | "8082" | dockerRemoteHost | dockerCertPath
        "http://jira.domain.se:8080" | "8080" | dockerRemoteHost | dockerCertPath

    }


    long getJsmGroovyTime(JsmH2Deployment jsmDeploy) {


        Map rawOut = jsmDeploy.jiraRest.executeLocalScriptFile("log.warn(\"EPOCH:\" + ( System.currentTimeMillis() / 1000).round(0))")
        //Map rawOut = jsmDeploy.jiraRest.executeLocalScriptFile("log.warn(\"EPOCH:\" + new Date().toInstant().epochSecond)")

        assert rawOut.success == true: "There was an error querying for GroovyTime from JSM ScriptRunner"
        assert (rawOut.log as ArrayList<String>).size() == 1

        String rawLogStatement = (rawOut.log as ArrayList<String>).get(0)
        long epochS = rawLogStatement.substring(rawLogStatement.lastIndexOf(":") + 1).toLong()

        return epochS

    }


    boolean setOffset(JsmH2Deployment jsmDeploy, long offsetS) {

        Map rawOut = jsmDeploy.jiraRest.executeLocalScriptFile("System.setProperty(\"faketime.offset.seconds\", \"$offsetS\")")

        assert rawOut.success == true: "There was an error querying for GroovyTime from JSM ScriptRunner"

        return true
    }


}
