package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.util.ImageBuilder
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.ImageSummary
import de.gesellix.docker.remote.api.MountPoint
import de.gesellix.docker.remote.api.PortBinding
import de.gesellix.docker.remote.api.Volume
import kong.unirest.HttpResponse
import kong.unirest.JsonResponse
import kong.unirest.Unirest
import kong.unirest.UnirestInstance

class JsmContainer implements Container {

    String containerName = "JSM"
    String containerMainPort = "8080"
    ArrayList<String> customEnvVar = []
    String containerImage = "atlassian/jira-servicemanagement"
    String containerImageTag = "latest"
    long jvmMaxRam = 6000

    private String debugPort //Contains the port used for JVM debug
    ArrayList<String> jvmSupportRecommendedArgs = [] //Used for setting application properties: https://confluence.atlassian.com/adminjiraserver/setting-properties-and-options-on-startup-938847831.html

    JsmContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }

    /**
     * Enables JVM debug of JIRA for port portNr
     * @param portNr
     */
    void enableJvmDebug(String portNr = "5005") {

        assert !created: "Error, cant enable JVM Debug for a container that has already been created"
        debugPort = portNr
        jvmSupportRecommendedArgs += ["-Xdebug", "-Xrunjdwp:transport=dt_socket,address=*:${debugPort},server=y,suspend=n"]
    }

    /**
     * Enables upload of Apps so that not only Marketplace apps can be installed
     * See: https://jira.atlassian.com/browse/JRASERVER-77129
     */
    void enableAppUpload() {
        assert !created: "Error, cant enable App Upload for a container that has already been created"
        jvmSupportRecommendedArgs += ["-Dupm.plugin.upload.enabled=true"]
    }

    /**
     * Gets the latest version number from Atlassian Marketplace
     * @return ex: 5.6.0
     */
    static String getLatestJsmVersion() {

        UnirestInstance unirest = Unirest.spawnInstance()

        HttpResponse<JsonResponse> response = unirest.get("https://marketplace.atlassian.com/rest/2/products/key/jira-servicedesk/versions/latest").asJson() as HttpResponse<JsonResponse>
        assert response.success: "Error getting latest JSM version from marketplace"
        String version = response?.body?.object?.get("name")
        assert version: "Error parsing latest JSM version from marketplace response"

        unirest.shutDown()

        return version
    }

    @Override
    ContainerCreateRequest setupContainerCreateRequest() {

        String image = containerImage + ":" + containerImageTag

        log.debug("Setting up container create request for JSM container")
        if (dockerClient.engineArch != "x86_64") {
            log.debug("\tDocker engine is not x86, building custom JSM docker image")

            ImageBuilder imageBuilder = new ImageBuilder(dockerClient.host, dockerClient.certPath)
            String jsmVersion = containerImageTag
            if (jsmVersion == "latest") {
                log.debug("\tCurrent image tag is set to \"latest\", need to resolve latest version number from Atlassian Marketplace in order to build custom image")
                jsmVersion = getLatestJsmVersion()
            }
            log.debug("\tStarting building of Docker Image for JSM verion $jsmVersion")
            ImageSummary newImage = imageBuilder.buildJsm(jsmVersion)
            log.debug("\tFinished building custom image:" + newImage.repoTags.join(","))

            image = newImage.repoTags.first()
        }

        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = image
            c.hostname = containerName


            c.exposedPorts = [(containerMainPort + "/tcp"): [:]]
            c.hostConfig = new HostConfig().tap { h ->
                h.portBindings = [(containerMainPort + "/tcp"): [new PortBinding("0.0.0.0", (containerMainPort))]]

                if (debugPort) {
                    h.portBindings.put((debugPort + "/tcp"), [new PortBinding("0.0.0.0", (debugPort))])
                    c.exposedPorts.put((debugPort + "/tcp"), [:])
                }


                h.mounts = this.preparedMounts
            }

            c.env = ["JVM_MAXIMUM_MEMORY=" + jvmMaxRam + "m", "JVM_MINIMUM_MEMORY=" + ((jvmMaxRam / 2) as String) + "m", "ATL_TOMCAT_PORT=" + containerMainPort] + customEnvVar


        }

        if (jvmSupportRecommendedArgs) {
            containerCreateRequest.env.add("JVM_SUPPORT_RECOMMENDED_ARGS=" + jvmSupportRecommendedArgs.join(" "))
        }

        return containerCreateRequest

    }


    /**
     * Returnes the mount point used for JIRA Home
     * @return
     */
    MountPoint getJiraHomeMountPoint() {
        return getMounts().find {it.destination == "/var/atlassian/application-data/jira"}
    }


    /**
     * Restores JIRA home using a previoulsy made Snapshot/volume
     * NOTE container will be stopped (and then restarted) if running
     * @param snapshotName (Optional), defaults to $ShortID-clone but any volume name should work,
     * @return True on success
     */
    boolean restoreJiraHomeSnapshot(String snapshotName = "") {

        boolean wasRunning = running
        stopContainer()
        snapshotName = snapshotName ?: shortId + "-clone"

        boolean success =  dockerClient.overwriteVolume(snapshotName, jiraHomeMountPoint.name)
        if (wasRunning) {
            startContainer()
        }


        return success

    }

    Volume getSnapshotVolume(String snapshotName = "") {

        if (!created) {
            return null
        }
        snapshotName = snapshotName ?: shortId + "-clone"

        ArrayList<Volume> volumes = dockerClient.getVolumesWithName(snapshotName)

        if (volumes.size() == 1) {
            return volumes.first()
        }else if (volumes.isEmpty()) {
            return null
        }else {
            throw new InputMismatchException("Error finding snapshot volume:" + snapshotName)
        }

    }


    /**
     * Snapshot JIRA home directory
     * JIRA will be stopped if running, and started once snapshot is done
     * @param snapshotName Name of the snapshot, will be deleted if already exists, will default to shortId + "-clone"
     * @return the new Volume object
     */
    Volume snapshotJiraHome(String snapshotName = "") {
        boolean wasRunning = running

        stopContainer(120)


        snapshotName = snapshotName ?: shortId + "-clone"

        ArrayList<Volume> existingVolumes = dockerClient.getVolumesWithName(snapshotName)
        existingVolumes.each {existingVolume ->
            log.debug("\tRemoving existing snapshot volume:" + existingVolume.name)
            dockerClient.manageVolume.rmVolume(existingVolume.name)
        }

        Volume newClone = cloneJiraHome(snapshotName)

        if (wasRunning) {
            startContainer()
        }

        return newClone
    }

    /**
     * Clone JIRA home volume
     * Container must be stopped
     * @param newVolumeName must be unique
     * @param labels, optional labels to add to the new volume
     * @return
     */
    Volume cloneJiraHome(String newVolumeName = "", Map<String, Object> labels = null) {

        newVolumeName = newVolumeName ?: shortId + "-clone"

        labels = labels ?: [
                srcContainerId : getId(),
                created : System.currentTimeSeconds()
        ] as Map

        Volume newVolume = dockerClient.cloneVolume(jiraHomeMountPoint.name, newVolumeName, labels)

        return newVolume

    }

    boolean runOnFirstStartup() {

        ArrayList<String> initialOut = runBashCommandInContainer("echo END")
        initialOut.remove { "END" }
        if (initialOut) {
            log.warn("StdOut contains unexpected output on initial startup:")
            initialOut.each { log.warn("\t" + it) }
        }
        log.debug("\tCreating folders needed for running Spoc tests with ScriptRunner")
        assert runBashCommandInContainer("mkdir  /opt/atlassian/jira/surefire-reports ; chown jira:jira  /opt/atlassian/jira/surefire-reports").empty
        log.debug("\tUpdating apt and installing dependencies")
        assert runBashCommandInContainer("apt update; apt install -y htop nano inetutils-ping; echo status: \$?", 300).any { it.contains("status: 0") }

        return true


    }


}
