package com.eficode.devstack.deployment.impl

import com.eficode.atlassian.jiraInstanceManager.JiraInstanceManagerRest
import com.eficode.devstack.DevStackSpec
import org.slf4j.LoggerFactory
import spock.lang.Shared

class JsmH2DeploymentTest extends DevStackSpec {


    @Shared
    File srLicenseFile = new File(System.getProperty("user.home") + "/.licenses/jira/sr.license")


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
        jsmDep.jiraRest.rest.get(baseurl).asEmpty().status == 200
        jsmDep.jsmContainer.inspectContainer().networkSettings.ports.find { it.key == "$port/tcp" }

        //Make sure websudo was disabled
        jsmDep.jsmContainer.runBashCommandInContainer("cat jira-config.properties").find { it == "jira.websudo.is.disabled=true" }
        jsmDep.jsmContainer.containerLogs.find { it.matches(".*jira.websudo.is.disabled.*:.*true.*") }


        where:
        baseurl            | port | dockerHost | certPath
        "http://localhost" | "80" | ""         | ""

    }

    def "Test DevStack In ScriptRunner"(String srVerion, String packageParameters) {

        setup:

        JsmH2Deployment jsmDep = new JsmH2Deployment("http://jira.localhost:8080")


        jsmDep.setJiraLicense(new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license").text)

        if (!jsmDep.jsmContainer.created || !jsmDep.jsmContainer.running) {
            assert jsmDep.setupDeployment(true, !jsmDep.jsmContainer.getSnapshotVolume()): "Error setting up or snapshoting JSM"
        }


        JiraInstanceManagerRest jiraRest = jsmDep.jiraRest

        jiraRest.waitForJiraToBeResponsive(240)
        assert jiraRest.installScriptRunner(srLicenseFile.text, srVerion): "Error installing SR version $srVerion"

        jiraRest.waitForSrToBeResponsive(120)
        when:

        log.info("Building DevStack jar")
        File devstackJar = buildDevStackJar(true, packageParameters)
        log.debug("\tGot DevStack jar:" + devstackJar.name)
        jsmDep.jsmContainer.copyFileToContainer(devstackJar.canonicalPath, "/var/atlassian/application-data/jira/tmp/")

        String containerJarPath = "/var/atlassian/application-data/jira/tmp/$devstackJar.name"

        Map<String, ArrayList<String>> srOut = jiraRest.executeLocalScriptFile("""
        
        File jarFile = new File("$containerJarPath")
        assert jarFile.exists() : "Could not find jar file in JIRA container"
                
        this.class.classLoader.addURL(jarFile.toURI().toURL());
       
        
        def jsmDeployment = Class.forName("com.eficode.devstack.deployment.impl.JsmH2Deployment").newInstance("http://jira.localhost:8080");
        String userKey = jsmDeployment.jiraRest.getUserKey("admin")
        log.error("Current userKey is:" + jsmDeployment.jiraRest.getUserKey("admin"))
        //throw new InputMismatchException("Current userKey is:" + userKey)
        return userKey


        """
        )

        then:
        srOut.log.any { it.toString().contains("Current userKey is:JIRAUSER10000") }

        cleanup:

        jsmDep.jsmContainer.runBashCommandInContainer("rm '$containerJarPath'", 10, "root")
        assert runDevstackMvnClean(): "Error cleaning DevStack maven project"
        runCmd("cd ${devStackProjectRoot} && rm ")

        where:
        srVerion | packageParameters
        "latest" | "-DskipTests"
        "latest" | "-Dgroovy.version=4.0.18 -DskipTests"
        "latest" | "-Dgroovy.version=4.0.16 -DskipTests"
        "latest" | "-Dgroovy.version=4.0.14 -DskipTests"
        "latest" | "-Dgroovy.version=4.0.12 -DskipTests"
        "latest" | "-Dgroovy.version=4.0.11 -DskipTests"
        "latest" | "-Dgroovy.version=3.0.20 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "latest" | "-Dgroovy.version=3.0.18 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "latest" | "-Dgroovy.version=3.0.17 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"

        "8.20.0" | "-DskipTests"
        "8.20.0" | "-Dgroovy.version=4.0.18 -DskipTests"
        "8.20.0" | "-Dgroovy.version=4.0.16 -DskipTests"
        "8.20.0" | "-Dgroovy.version=4.0.14 -DskipTests"
        "8.20.0" | "-Dgroovy.version=4.0.12 -DskipTests"
        "8.20.0" | "-Dgroovy.version=4.0.11 -DskipTests"
        "8.20.0" | "-Dgroovy.version=3.0.20 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "8.20.0" | "-Dgroovy.version=3.0.18 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "8.20.0" | "-Dgroovy.version=3.0.17 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"

        "8.10.0" | "-DskipTests"
        "8.10.0" | "-Dgroovy.version=4.0.18 -DskipTests"
        "8.10.0" | "-Dgroovy.version=4.0.16 -DskipTests"
        "8.10.0" | "-Dgroovy.version=4.0.14 -DskipTests"
        "8.10.0" | "-Dgroovy.version=4.0.12 -DskipTests"
        "8.10.0" | "-Dgroovy.version=4.0.11 -DskipTests"
        "8.10.0" | "-Dgroovy.version=3.0.20 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "8.10.0" | "-Dgroovy.version=3.0.18 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "8.10.0" | "-Dgroovy.version=3.0.17 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"


        "8.0.0" | "-DskipTests"
        "8.0.0" | "-Dgroovy.version=4.0.18 -DskipTests"
        "8.0.0" | "-Dgroovy.version=4.0.16 -DskipTests"
        "8.0.0" | "-Dgroovy.version=4.0.14 -DskipTests"
        "8.0.0" | "-Dgroovy.version=4.0.12 -DskipTests"
        "8.0.0" | "-Dgroovy.version=4.0.11 -DskipTests"
        "8.0.0" | "-Dgroovy.version=3.0.20 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "8.0.0" | "-Dgroovy.version=3.0.18 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "8.0.0" | "-Dgroovy.version=3.0.17 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"


        "7.10.0" | "-DskipTests"
        "7.10.0" | "-Dgroovy.version=4.0.18 -DskipTests"
        "7.10.0" | "-Dgroovy.version=4.0.16 -DskipTests"
        "7.10.0" | "-Dgroovy.version=4.0.14 -DskipTests"
        "7.10.0" | "-Dgroovy.version=4.0.12 -DskipTests"
        "7.10.0" | "-Dgroovy.version=4.0.11 -DskipTests"
        "7.10.0" | "-Dgroovy.version=3.0.20 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "7.10.0" | "-Dgroovy.version=3.0.18 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        "7.10.0" | "-Dgroovy.version=3.0.17 -Dgroovy.groupId=org.codehaus.groovy -Dgroovy.major.version=3.0 -DskipTests"
        



    }


}
