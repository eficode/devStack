package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.AllureContainer
import com.eficode.devstack.container.impl.JsmContainer
import com.eficode.devstack.deployment.Deployment
import com.eficode.devstack.util.DirectorySyncer
import com.eficode.devstack.util.DockerClientDS
import de.gesellix.docker.client.DockerClient
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.Volume
import org.slf4j.Logger
import org.slf4j.LoggerFactory


//TODO override stop and remove
//TODO override stop and remove
class JsmDevDeployment implements Deployment {

    Logger log = LoggerFactory.getLogger(this.class)
    String friendlyName = "JSM Development Platform"
    String deploymentNetworkName = "jsmdev"
    ArrayList<Deployment> subDeployments = []

    DirectorySyncer srcSyncer
    ArrayList<String> srcCodePaths
    Volume srcCodeVolume

    AllureContainer allureContainer
    Volume allureReportVolume

    DockerClientDS dockerClient

    //Used when naming various Docker components
    String componentsPrefix

    JsmDevDeployment(String jiraBaseUrl, String dockerHost, String dockerCertPath, ArrayList<String> srcCodePaths) {

        componentsPrefix = AllureContainer.extractDomainFromUrl(jiraBaseUrl)

        allureContainer = new AllureContainer(dockerHost, dockerCertPath)
        allureContainer.containerName = componentsPrefix + "-reporter"
        dockerClient = allureContainer.dockerClient

        allureReportVolume = getOrCreateVolume( componentsPrefix+ "-reports")
        allureContainer.prepareCustomEnvVar(["CHECK_RESULTS_EVERY_SECONDS=3", "KEEP_HISTORY=1", "KEEP_HISTORY_LATEST=30"])
        allureContainer.prepareVolumeMount(allureReportVolume.name, "/app/allure-results", false)


        srcCodeVolume = getOrCreateVolume(componentsPrefix + "-code")
        this.srcCodePaths = srcCodePaths

        subDeployments.add(new JsmH2Deployment(jiraBaseUrl, dockerHost, dockerCertPath))
        jsmDeployment.jsmContainer.prepareVolumeMount(srcCodeVolume.name, "/var/atlassian/application-data/jira/scripts/", false)
        jsmDeployment.jsmContainer.prepareVolumeMount(allureReportVolume.name, "/var/atlassian/application-data/jira/allure-results/", false)


    }

    JsmH2Deployment getJsmDeployment() {
        return subDeployments.find { it instanceof JsmH2Deployment } as JsmH2Deployment
    }

    JsmContainer getJsmContainer() {
        return jsmDeployment.jsmContainer
    }


    @Override
    ArrayList<Container> getContainers() {
        return [srcSyncer, allureContainer, jsmContainer]
    }

    @Override
    void setContainers(ArrayList<Container> containers) {
        throw new InputMismatchException("Not implemented")
    }

    Volume getOrCreateVolume(String volumeName) {
        Volume volume = dockerClient.getVolumesWithName(volumeName).find { true }

        if (volume) {
            log.debug("\tFound existing volume:" + volume.name)
        } else {
            log.debug("\tCreating new volume $volumeName")
            EngineResponseContent<Volume> volumeResponse = dockerClient.createVolume(volumeName)
            volume = volumeResponse?.content
            assert volume: "Error creating volume $volumeName, " + volumeResponse?.getStatus()?.text
            log.debug("\t\tCreated volume:" + volume.name)
        }

        return volume
    }

    @Override
    boolean setupDeployment() {


        srcSyncer = DirectorySyncer.createSyncToVolume(srcCodePaths, srcCodeVolume.name, "-avh --chown=2001:2001")
        allureContainer.created ?: allureContainer.createContainer()
        allureContainer.startContainer()

        jsmDeployment.setupDeployment(true, false)


    }

    public static class Builder {

        private String jsmLicense
        private String jsmBaseUrl
        private String jsmVersion = "latest"
        private String jsmJvmDebugPort = "5005"
        private Boolean enableJsmDooD = false

        private Map<String, String> appsToInstall = [:]

        private String dockerHost
        private String dockerCertPath

        private ArrayList<String> srcCodePaths

        Builder(String baseUrl, String jsmLicense, ArrayList<String> srcCodePaths) {
            this.jsmLicense = jsmLicense
            this.jsmBaseUrl = baseUrl
            this.srcCodePaths = srcCodePaths
        }

        Builder setJsmVersion(String version) {
            this.jsmVersion = version
            return this
        }

        Builder setJsmJvmDebugPort(String port) {
            this.jsmJvmDebugPort = port
            return this
        }

        /**
         * Enable Docker Outside of Docker by mounting "/var/run/docker.sock" in to jsm container
         * @return
         */
        Builder enableJsmDood() {
            this.enableJsmDooD = true
            return this
        }


        Builder addAppToInstall(String appUrl, String appLicense = "") {
            this.appsToInstall.put(appUrl, appLicense)
            return this
        }

        Builder setDockerHost(String host) {
            this.dockerHost = host
            return this
        }

        Builder setDockerCertPath(String certPath) {
            this.dockerCertPath = certPath
            return this
        }


        JsmDevDeployment build() {

            JsmDevDeployment devDeployment = new JsmDevDeployment(jsmBaseUrl, dockerHost, dockerCertPath, srcCodePaths)

            devDeployment.jsmDeployment.jsmContainer.containerImageTag = this.jsmVersion
            devDeployment.jsmDeployment.jsmContainer.created ?: devDeployment.jsmDeployment.jsmContainer.enableJvmDebug(this.jsmJvmDebugPort)
            devDeployment.jsmDeployment.setJiraLicense(this.jsmLicense)
            devDeployment.jsmDeployment.jsmContainer.enableAppUpload()
            if (enableJsmDooD) {
                devDeployment.jsmDeployment.jsmContainer.prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock", false)
            }
            devDeployment.jsmDeployment.appsToInstall = this.appsToInstall


            return devDeployment

        }

    }


}


