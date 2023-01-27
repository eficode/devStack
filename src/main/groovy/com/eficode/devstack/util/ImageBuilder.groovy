package com.eficode.devstack.util;
import com.eficode.devstack.container.impl.DoodContainer
import de.gesellix.docker.remote.api.ImageSummary
import java.util.concurrent.TimeoutException


/**
 * A utility class intended to build docker images so that they match the docker engines CPU architecture
 *
 */

class ImageBuilder extends DoodContainer {


    LinkedHashMap<String, String> builderCommands = [:]
    Map<String, ArrayList<String>>builderOut = [:]
    long cmdTimeoutS = 200 //Will timeout individual container commands after this many seconds
    long totalTimeoutS = 10 * 60


    ImageBuilder(String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock")
    }

    ImageBuilder() {
        prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock")
    }

    /**
     * Add bash commands that should be run in the build container
     * @param cmd The command to run
     * @param expectedLastOut That line of console output expected when running $cmd. Will throw exception if not true, will ignore if set to ""
     *
     * Ex: cmd              = "echo status:\$?
     * Ex: expectedLastOut  = "status:0
     */
    void putBuilderCommand(String cmd, String expectedLastOut) {
        builderCommands.put(cmd, expectedLastOut)
    }


    /**
     * Will check out Atlassian docker repo and build a JSM image matching docker engine CPU arch.
     * The new image will be named atlassian/jira-servicemanagement and tagged $version-$cpuArch
     *  ex: atlassian/jira-servicemanagement:5.3.1-x86_64
     * @param jsmVersion The JSM versions to build
     * @param force If true, will always create and potentially overwrite existing image. If false, will return a pre-existing image if available
     * @return
     */
    ImageSummary buildJsm(String jsmVersion, boolean force = false){

        String imageName = "atlassian/jira-servicemanagement"
        String artifactName = "atlassian-servicedesk"
        String archType = dockerClient.engineArch
        String imageTag = "$imageName:$jsmVersion-$archType"

        //Check first if an image with the expected tag already exists
        if (!force) {
            ArrayList<ImageSummary> existingImages = dockerClient.images().content
            ImageSummary existingImage =  existingImages.find {it.repoTags == [imageTag]}
            if (existingImage) {
                return existingImage
            }
        }

        putBuilderCommand("apt install git -y && echo status:\$?", "status:0")
        putBuilderCommand("git clone --recurse-submodule https://bitbucket.org/atlassian-docker/docker-atlassian-jira.git && echo status:\$?", "status:0")
        putBuilderCommand("cd docker-atlassian-jira && docker build --tag $imageTag --build-arg JIRA_VERSION=$jsmVersion --build-arg ARTEFACT_NAME=$artifactName . && echo status:\$?", "status:0")
        putBuilderCommand("pkill tail", "")

        assert build() : "Error building the image."

        ArrayList<ImageSummary> images = dockerClient.images().content
        ImageSummary newImage =  images.find {it.repoTags == [imageTag]}
        return newImage

    }

    /**
     * Takes care of setting up a Dood container and running the commands submitted with putBuilderCommand
     * The putBuilderCommand-commands should take care of killing the tail process with ex: pkill tail
     * @return true on success. Cmd output can be found in builderOut
     */
    private boolean build() {

        log.info("Creating and starting Image Builder container")

        long start = System.currentTimeSeconds()
        long end = start + totalTimeoutS
        createContainer([],["/bin/bash" ,"-c" ,"trap \"exit\" SIGINT SIGTERM && tail -F /var/log/*"])
        startContainer()
        log.info("\tContainer started, waiting for it to finish")
        while (running && (System.currentTimeSeconds() < end)) {
            sleep(2000)
            log.info("\t\tStill waiting for container to finish, waited ${System.currentTimeSeconds() - start}s")
        }

        if (running) {
            stopAndRemoveContainer()
            throw new TimeoutException("Timed out waiting for build container to finish")
        }

        stopAndRemoveContainer()
        return true
    }

    @Override
    boolean runAfterDockerSetup(){


        builderCommands.each {cmd, expectedLastOut ->
            log.info("Running container command:" + cmd)
            log.info("\tExpecting last output from command:" + expectedLastOut)
            ArrayList<String> output = runBashCommandInContainer(cmd, cmdTimeoutS)
            log.debug("\tCmd returned output:")
            output.each {log.debug("\t\t" + it)}

            builderOut.put(cmd, output)
            if (expectedLastOut != "" && expectedLastOut != output.last()) {
                String error = "Container cmd \"$cmd\" returned unexpected output \"${output.last()}\", but expected \"$expectedLastOut\""
                log.error(error)
                throw new InputMismatchException(error)
            }
            log.debug("\tSuccessful validation of cmd output")
        }
        return true
    }

}
