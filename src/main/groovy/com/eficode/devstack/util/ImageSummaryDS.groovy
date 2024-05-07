package com.eficode.devstack.util

import de.gesellix.docker.builder.BuildContextBuilder
import de.gesellix.docker.remote.api.ImageInspect
import de.gesellix.docker.remote.api.ImageSummary
import groovy.io.FileType
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.codehaus.groovy.runtime.ResourceGroovyMethods
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.time.Duration
import java.time.temporal.ChronoUnit

class ImageSummaryDS {


    Logger log = LoggerFactory.getLogger(ImageSummaryDS.class)
    String id
    String parentId
    int created
    long propertySize
    long sharedSize
    int containers
    List<String> repoTags
    List<String> repoDigests
    Long virtualSize
    Map<String, String> labels

    DockerClientDS dockerClient

    ImageSummaryDS(DockerClientDS dockerClient, ImageSummary imageSummary) {
        id = imageSummary.id
        parentId = imageSummary.parentId
        created = imageSummary.created
        propertySize = imageSummary.propertySize
        sharedSize = imageSummary.sharedSize
        containers = imageSummary.containers
        repoTags = imageSummary.repoTags
        repoDigests = imageSummary.repoDigests
        virtualSize = imageSummary.virtualSize
        labels = imageSummary.labels
        this.dockerClient = dockerClient
    }

    ImageInspect inspect() {
        return dockerClient.inspectImage(this.id).content
    }

    static String getReplaceUserScriptBody(String fromUserName, String fromUid, String fromGroupName, String fromGid, String toUserName, String toUid, String toGroupName, String toGid) {


        return """
        groupmod -g $toGid -n $toGroupName $fromGroupName
        usermod -u $toUid -l $toUserName -g $toGid $fromUserName
        #Ignoring return code from chown, as it will be != 0 because some files where skipped
        chown -R --from=$fromUid:$fromGid $toUserName:$toGroupName / || true
        """.stripIndent()


    }

    ImageSummaryDS replaceDockerUser(String fromUserName, String fromUid, String fromGroupName, String fromGid, String toUserName, String toUid, String toGroupName, String toGid, String tag = "") {

        String newTag =  (tag == "" ? this.repoTags.first() +"-"+toUserName : tag)

        File tempDir = File.createTempDir("dockerBuild")
        tempDir.deleteOnExit()
        String dockerFileBody =  """FROM ${this.repoDigests.first()}
        USER root

        RUN groupmod -g $toGid -n $toGroupName $fromGroupName
        RUN usermod -u $toUid -l $toUserName -g $toGid $fromUserName
        #Ignoring return code from chown, as it will be != 0 because some files where skipped
        RUN chown -R --from=$fromUid:$fromGid $toUserName:$toGroupName / || true
        USER $toUserName
        """.stripIndent()

        File dockerFile = new File(tempDir, "Dockerfile")
        dockerFile.text = dockerFileBody

        dockerClient.build(
                {
                    log.info("Building:" + it.toString())
                },
                Duration.of(3, ChronoUnit.MINUTES),
               newTag,
                newBuildContext(tempDir)
        )

        return  new ImageSummaryDS(dockerClient, dockerClient.images().content.find { it.repoTags.any {it == newTag}})


    }

    static InputStream newBuildContext(File baseDirectory) {
        File buildContext = File.createTempFile("buildContext", ".tar")
        buildContext.deleteOnExit()
        BuildContextBuilder.archiveTarFilesRecursively(baseDirectory, buildContext)
        return new FileInputStream(buildContext)
    }



}
