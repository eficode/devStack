package com.eficode.devstack.container.impl
import com.eficode.devstack.container.Container


class GroovyContainer implements Container{

    String containerName = "groovy-container"
    String containerMainPort = ""
    String containerImage = "groovy"
    String containerImageTag = "latest"


    GroovyContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }

    /**
     * Runs a groovy script in the container
     * @param scriptText Text of the script that should be run
     * @param options Optional options to pass to the groovy process: ex: -Dorg.slf4j.simpleLogger.defaultLogLevel=trace
     * @param arguments Optional arguments to pass to the script
     * @param timeOutS
     * @param containerUser What user to run the script as
     * @return An arraylist containing console output from the script
     */
    ArrayList<String> runScriptInContainer(String scriptText,String options ="",  String arguments = "", long timeOutS = 120, String containerUser = "groovy") {
        assert replaceFileInContainer(scriptText, "/home/groovy/userScript.groovy") : "Error uploading script to container $id"
        return runBashCommandInContainer("groovy $options /home/groovy/userScript.groovy $arguments 2>&1 | tee /var/log/userScript.log"  , timeOutS, containerUser)

    }

    /**
     * Creates a container that wont automatically terminate immediately
     * @return container id
     */
    String createSleepyContainer() {
        return createContainer([],["/bin/bash" ,"-c" ,"trap \"exit\" SIGINT SIGTERM && tail -F /var/log/*  /var/log/userScript.log"])
    }

    void useGroovy3() {
        this.containerImageTag = "3.0-jdk11-jammy"
    }

    void setGroovyVersion(String version) {
        this.containerImageTag = "$version-jdk11-jammy"
    }




}
