package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.PortBinding
import de.gesellix.docker.remote.api.client.BuildInfoExtensionsKt
import de.gesellix.docker.remote.api.core.Frame
import de.gesellix.docker.remote.api.core.StreamCallback
import groovy.io.FileType
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.codehaus.groovy.runtime.ResourceGroovyMethods
import org.eclipse.jgit.lib.Repository
import org.slf4j.LoggerFactory
import oshi.SystemInfo

import java.nio.file.Files

import java.time.Duration
import java.text.SimpleDateFormat
import de.gesellix.docker.remote.api.BuildInfo

import java.util.concurrent.CountDownLatch


class JsmContainer implements Container {

    String containerMainPort = "8080"
    String containerImage = "atlassian/jira-servicemanagement"
    String containerImageTag = "4.22.2"
    long jvmMaxRam = 6000
    String jsmVersion = "4.22.2"



    static String containerName = "JSM"
    static String srcUrl = "https://bitbucket.org/atlassian-docker/docker-atlassian-jira.git"
    static File repoTargetDir = new File("jsm-repo")
    static String tarOutputPath = "./jsm-docker.tar"

    JsmContainer() {}

    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    JsmContainer(String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
    }


    String createContainer() {

        containerId = createJsmContainer()
        return containerId

    }

    static boolean isAppleSilicon(){
        return  new SystemInfo().hardware.processor.processorIdentifier.name ==~ /.*Apple M1.*/
    }



    String createJsmContainer(String jsmContainerName = containerName, String imageName = containerImage, String imageTag = containerImageTag, long jsmMaxRamMB = jvmMaxRam, String jsmPort = containerMainPort) {

        assert dockerClient.ping().content as String == "OK", "Error Connecting to docker service"

        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = imageName + ":" + imageTag
            c.env = ["JVM_MAXIMUM_MEMORY=" + jsmMaxRamMB.toString() + "m", "JVM_MINIMUM_MEMORY=" + ((jsmMaxRamMB / 2) as String) + "m"]
            c.exposedPorts = [(jsmPort + "/tcp"): [:]]
            c.hostConfig = new HostConfig().tap { h ->
                h.portBindings = [(jsmPort + "/tcp"): [new PortBinding("0.0.0.0", (jsmPort.toString()))]]
            }
        }

        if (isAppleSilicon()) {
            log.info("It is Apple M1 Processor..")
            log.info("Cloning repository from ${srcUrl}")
            Repository jsmRepo=cloneRepository(srcUrl, repoTargetDir.getAbsolutePath())
            log.info("Repository Cloned: ${jsmRepo}")
            log.info("****** Creating TAR file ******")
            File tarFile = createTar([repoTargetDir.getAbsolutePath()], tarOutputPath)
            log.debug("Tar file : "+tarFile.name)

            Duration duration = Duration.ofMinutes(5)

            StreamCallback<BuildInfo> callback = new StreamCallback<BuildInfo>() {
                def latch = new CountDownLatch(10)
                List<BuildInfo> infos = []

                @Override
                void onNext(BuildInfo element) {
                    infos.add(element)
                }

                @Override
                void onFailed(Exception e) {
                    log.error("Build failed", e)
                    latch.countDown()
                }

                @Override
                void onFinished() {
                    latch.countDown()
                }
            }

           log.info("DOCKER BUILD INITIATED!!!!")
           String jsmImageTag = imageTag + ":jsm"

           dockerClient.build(callback, duration, "", jsmImageTag, false, false, "", true, $/{"JIRA_VERSION":"${jsmVersion}", "ARTEFACT_NAME":"atlassian-servicedesk"}/$, "", "", "", tarFile.newInputStream())

            def imageId = BuildInfoExtensionsKt.getImageId(callback.infos)
            log.debug("IMAGE ID: " + imageId)
            String imageStream = callback.infos["stream"].last().toString()
            String m1ImageName = imageStream.replace("Successfully tagged ", '').trim()
            log.debug("Image Name: " + m1ImageName)

            containerCreateRequest.image=m1ImageName
            log.debug("Image Passed to RUN command: "+containerCreateRequest.image.toString())

            //Cleanup Repo and tar file
            log.info("Cleaning up Repo and tar file.")
            assert repoTargetDir.deleteDir() : "Error Deleting the repository folder."
            assert tarFile.delete() : "Error Deleting the Tar file."
        }

            log.info("CREATING DOCKER CONTAINER...")
            EngineResponseContent response = dockerClient.createContainer(containerCreateRequest, jsmContainerName)
            assert response.content.warnings.isEmpty(): "Error when creating $jsmContainerName container:" + response.content.warnings.join(",")

            containerId = response.content.id
            return containerId

        }

}
