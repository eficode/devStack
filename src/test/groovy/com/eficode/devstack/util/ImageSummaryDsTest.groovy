package com.eficode.devstack.util

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.container.impl.AbstractContainer
import de.gesellix.docker.remote.api.ImageSummary
import org.apache.tools.ant.util.StringUtils
import org.codehaus.groovy.util.StringUtil
import org.slf4j.LoggerFactory
import org.spockframework.runtime.condition.EditDistance

import java.time.Duration
import java.time.temporal.ChronoUnit

class ImageSummaryDsTest extends DevStackSpec {

    def setupSpec() {

        DevStackSpec.log = LoggerFactory.getLogger(this.class)

        cleanupContainerNames = ["SpockReplaceUser"]
        cleanupContainerPorts = []

        disableCleanup = false

    }

    def "Test prependStartupScript"(String image, String tag) {

        setup:
        ImageSummaryDS srcImage = getOrPullImage(image, tag)

        AbstractContainer container = new AbstractContainer(System.nanoTime().toString().takeRight(5), "", srcImage.name, tag, "/bin/sh")
        container.createContainer()
        container.startContainer()
        sleep(5000)
        ArrayList<String> srcImageOutput = container.containerLogs
        container.stopAndRemoveContainer()


        when:
        ImageSummaryDS prependedImage = srcImage.prependStartupScript("echo THIS IS SPOCK")
        container = new AbstractContainer(System.nanoTime().toString().takeRight(5), "", prependedImage.name, prependedImage.tag, "/bin/sh")
        container.createContainer()
        container.startContainer()
        sleep(5000)
        ArrayList<String> updatedImageOutput = container.containerLogs
        container.stopAndRemoveContainer()

        //Compare the similarity between the images console output, removing the first 30chars of each row as these usually contain timestamps
        Integer consoleSimilarity = new EditDistance(srcImageOutput.collect { it.takeRight(it.length() - 30) }.toString(), updatedImageOutput.collect { it.takeRight(it.length() - 30) }.toString()).getSimilarityInPercent()

        then:
        updatedImageOutput.first().contains("THIS IS SPOCK")
        assert consoleSimilarity > 80: "The console output appears to have changed significantly in the new image"

        cleanup:
        dockerClient.rmi(prependedImage.id)


        where:
        image                              | tag
        "nginx"                            | "latest"
        "ubuntu"                           | "latest"
        "bitnami/postgresql"               | "latest"
        "atlassian/jira-servicemanagement" | "5.14.0-aarch64"
    }

    def "Test replaceDockerUser"(String srcUser, String image, String tag) {

        setup:


        AbstractContainer container = new AbstractContainer("SpockReplaceUser", "", image, tag, "/bin/sh")
        container.createSleepyContainer()
        assert container.startContainer(): "Error starting container with image $image:$tag"

        when: "Getting image default user and group"

        String defaultUser = container.runBashCommandInContainer("whoami", 5, srcUser).first()
        String defaultUserId = container.runBashCommandInContainer("id -u", 5, srcUser).first()
        String defaultGroup = container.runBashCommandInContainer("id -gn", 5, srcUser).first()
        String defaultGroupId = container.runBashCommandInContainer("id -g", 5, srcUser).first()


        then: "Should get those parameters"
        defaultUser != ""
        defaultUserId != ""
        defaultGroup != ""
        defaultGroupId != ""

        when: "Creating an image with a replaced user"
        ImageSummaryDS srcImage = getOrPullImage(image, tag)
        ImageSummaryDS newImage = srcImage.replaceDockerUser(defaultUser, defaultUserId, defaultGroup, defaultGroupId, "spock", "2500", "spock", "2500")
        container.stopAndRemoveContainer()

        container = new AbstractContainer("SpockReplaceUser", "", newImage.name, newImage.tag, "/bin/sh")
        container.createSleepyContainer()
        container.startContainer()

        ArrayList<String>oldUidPaths = container.runBashCommandInContainer("find / -user $defaultUserId", 5, "root").findAll {!it.contains("Permission denied") && !it.contains("No such file or directory")}
        ArrayList<String>oldGidPaths = container.runBashCommandInContainer("find / -group $defaultGroupId", 5, "root").findAll {!it.contains("Permission denied") && !it.contains("No such file or directory")}

        then:
        srcImage
        container.running
        container.runBashCommandInContainer("cd && find . -user spock | wc -l", 2, "spock").find { true }?.toInteger() > 0
        container.runBashCommandInContainer("cd && find . -group spock | wc -l", 2, "spock").find { true }?.toInteger() > 0
        container.runBashCommandInContainer("id -g", 2, "spock").find { true } == "2500"
        assert oldGidPaths.empty : "Container started with paths owned by old gid:" + oldGidPaths.join(", ")
        assert oldUidPaths.empty : "Container started with paths owned by old uid:" + oldUidPaths.join(", ")


        cleanup:
        container.created ? container.stopAndRemoveContainer() : null
        dockerClient.manageImage.rmi(newImage.id)


        where:
        srcUser  | image                                | tag
        "allure" | "frankescobar/allure-docker-service" | "latest"
        //"ubuntu" | "ubuntu"                             | "latest"

    }


    ImageSummaryDS getOrPullImage(String name, String tag) {


        ImageSummary image = dockerClient.images().content.find { it.repoTags.any { it == "$name:$tag" } }
        if (!image) {
            dockerClient.manageImage.pull(
                    {
                        log.info(it.status)
                    },
                    Duration.of(2, ChronoUnit.MINUTES),
                    "ubuntu",
                    "latest"
            )
            image = dockerClient.images().content.find { it.repoTags.any { it == "$name:$tag" } }
        }

        assert image: "Error finding and pulling image: $name:$tag"


        return new ImageSummaryDS(dockerClient, image)

    }

}
