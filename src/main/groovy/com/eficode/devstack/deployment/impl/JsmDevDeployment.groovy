package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.AllureContainer
import com.eficode.devstack.container.impl.JsmContainer
import com.eficode.devstack.deployment.Deployment
import com.eficode.devstack.util.DirectorySyncer
import com.eficode.devstack.util.DockerClientDS
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
    Volume jiraReportVolume
    Volume allureReportVolume
    DirectorySyncer reportSyncer

    DockerClientDS dockerClient

    //Used when naming various Docker components
    String componentsPrefix

    JsmDevDeployment(String jiraBaseUrl, String dockerHost, String dockerCertPath, ArrayList<String> srcCodePaths) {

        componentsPrefix = AllureContainer.extractDomainFromUrl(jiraBaseUrl)

        allureContainer = new AllureContainer(dockerHost, dockerCertPath)
        allureContainer.containerName = componentsPrefix + "-reporter"
        dockerClient = allureContainer.dockerClient


        allureReportVolume = dockerClient.getOrCreateVolume(componentsPrefix + "-allureReports")
        jiraReportVolume = dockerClient.getOrCreateVolume(componentsPrefix + "-jiraReports")
        allureContainer.prepareCustomEnvVar(["CHECK_RESULTS_EVERY_SECONDS=3", "KEEP_HISTORY=1", "KEEP_HISTORY_LATEST=30"])
        allureContainer.setResultsVolume(allureReportVolume.name)


        srcCodeVolume = dockerClient.getOrCreateVolume(componentsPrefix + "-code")
        srcSyncer = DirectorySyncer.getDuplicateContainer(dockerClient, "SrcSyncer")
        this.srcCodePaths = srcCodePaths

        if (srcSyncer?.created) {
            log.warn("Old SrcSyncer container exists, removing it before proceeding")
            srcSyncer.stopAndRemoveContainer()
            srcSyncer = null
        }


        subDeployments.add(new JsmH2Deployment(jiraBaseUrl, dockerHost, dockerCertPath))
        jsmDeployment.jsmContainer.prepareVolumeMount(srcCodeVolume.name, "/var/atlassian/application-data/jira/scripts/", false)
        jsmDeployment.jsmContainer.prepareVolumeMount(jiraReportVolume.name, "/var/atlassian/application-data/jira/allure-results/", false)


    }

    JsmH2Deployment getJsmDeployment() {
        return subDeployments.find { it instanceof JsmH2Deployment } as JsmH2Deployment
    }

    JsmContainer getJsmContainer() {
        return jsmDeployment.jsmContainer
    }


    @Override
    ArrayList<Container> getContainers() {
        return [srcSyncer, allureContainer, jsmContainer, reportSyncer]
    }

    @Override
    void setContainers(ArrayList<Container> containers) {
        throw new InputMismatchException("Not implemented")
    }

    @Override
    boolean stopAndRemoveDeployment() {

        Volume jsmSnapshotVolume

        try {
            jsmSnapshotVolume = jsmContainer.getSnapshotVolume()
        }catch (ignored){}


        Boolean success = Deployment.super.stopAndRemoveDeployment()
        if (jiraReportVolume) {
            dockerClient.rmVolume(jiraReportVolume.name)
        }
        if (allureReportVolume) {
            dockerClient.rmVolume(allureReportVolume.name)
        }

        if (srcCodeVolume) {
            dockerClient.rmVolume(srcCodeVolume.name)
        }

        if (jsmSnapshotVolume) {
            dockerClient.rmVolume(jsmSnapshotVolume.name)
        }

        return success

    }

    @Override
    boolean setupDeployment() {


        srcSyncer = DirectorySyncer.createSyncToVolume(srcCodePaths, srcCodeVolume.name, "SrcSyncer", "-avh --chown=2001:2001")


        reportSyncer = DirectorySyncer.syncBetweenVolumesAndUsers(jiraReportVolume.name, allureReportVolume.name, "1000:1000", "ReportSyncer")


        allureContainer.created ?: allureContainer.createContainer()
        allureContainer.startContainer()


        jsmDeployment.setupDeployment(true, true)
        //Change owner of the mounted volume
        jsmContainer.runBashCommandInContainer("chown -R jira:jira /var/atlassian/application-data/jira/allure-results", 10, "root")

        if (jsmDeployment.jiraRest.scriptRunnerIsInstalled()) {
            jsmDeployment.jiraRest.deploySpockEndpoint(['com.riadalabs.jira.plugins.insight' ])
        }

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


