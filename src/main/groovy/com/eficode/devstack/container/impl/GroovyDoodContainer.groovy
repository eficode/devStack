package com.eficode.devstack.container.impl

class GroovyDoodContainer extends DoodContainer{

    String containerImage = "groovy"
    String containerImageTag = "latest"


    GroovyDoodContainer(String dockerHost = "", String dockerCertPath = "") {
        super(dockerHost, dockerCertPath)
    }

    /**
     * Runs a groovy script in the container
     * @param scriptText Text of the script that should be run
     * @param arguments Optional arguments to pass to the script
     * @param timeOutS
     * @param containerUser What user to run the script as
     * @return An arraylist containing console output from the script
     */
    ArrayList<String> runScriptInContainer(String scriptText, String arguments = "", long timeOutS = 120, String containerUser = "groovy") {
        assert replaceFileInContainer(scriptText, "/home/groovy/userScript.groovy") : "Error uploading script to container $id"
        return runBashCommandInContainer("groovy /home/groovy/userScript.groovy" + arguments , timeOutS, containerUser)

    }

    /**
     * Creates a container that wont automatically terminate immediately
     * @return container id
     */
    String createSleepyContainer() {
        return createContainer([],["/bin/bash" ,"-c" ,"trap \"exit\" SIGINT SIGTERM && tail -F /var/log/*"])
    }

    void useGroovy3() {
        this.containerImageTag = "3.0-jdk11-jammy"
    }





}
