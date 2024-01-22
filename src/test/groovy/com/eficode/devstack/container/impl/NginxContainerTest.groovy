package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.deployment.impl.NginxFileServer
import de.gesellix.docker.remote.api.ContainerInspectResponse
import kong.unirest.Unirest
import org.slf4j.LoggerFactory

class NginxContainerTest extends DevStackSpec {


    def setupSpec() {

        //dockerRemoteHost = "https://docker.domain.se:2376"
        //dockerCertPath = "~/.docker/"


        DevStackSpec.log = LoggerFactory.getLogger(this.class)

        cleanupContainerNames = ["Nginx", "Nginx-File-Server"]
        cleanupContainerPorts = [80]

        disableCleanup = false


    }


    def "test default setup"(String dockerHost, String certPath) {

        setup:

        NginxContainer nginxC = new NginxContainer(dockerHost, certPath)

        expect:
        nginxC.createContainer()
        nginxC.startContainer()

        where:
        dockerHost       | certPath
        ""               | ""
        dockerRemoteHost | dockerCertPath

    }

    def "Test NginxFileServer"(String dockerHost, String certPath, String fileSize, String port) {

        setup:

        String engineDir = "/tmp"
        String fileName = "Size-$fileSize-Port-${port}.temp"

        NginxFileServer nginxC = new NginxFileServer(engineDir, dockerHost, certPath)
        nginxC.stopAndRemoveDeployment()

        port ? nginxC.port = port : null

        nginxC.setupDeployment()
        nginxC.startDeployment()


        when: "Uploading from another container"

        String alpineLocalFilePath = "/tmp/$fileName"

        String uploadScript = "" +
                "head -c $fileSize /dev/urandom > $alpineLocalFilePath && " +
                "sha256sum $alpineLocalFilePath > ${alpineLocalFilePath}.sha256 && " +
                "apk add curl && " +
                "curl -T \"/tmp/$fileName\" ${nginxC.container.ips.first()}:$port && " +
                "cd /tmp/out && " +
                "echo \"\$(cat ${alpineLocalFilePath}.sha256)\" | sha256sum -c -s && " +
                "cat ${alpineLocalFilePath}.sha256 && " +
                "echo Hash status:\$?"


        ArrayList<String> alpineUploadLogs = AlpineContainer.runCmdAndRm(uploadScript, 15000, [[src: engineDir, target: "/tmp/out", readOnly: false]], dockerHost, certPath)


        then: "The uploaded file and local file should have the same hash"
        alpineUploadLogs.last() == "Hash status:0"

        when: "Downloading from another container"

        String downloadScript = "" +
                "apk add curl && " +
                "curl ${nginxC.container.ips.first()}:$port/$fileName --output $alpineLocalFilePath && " +
                "cd /tmp/ && " +
                "sha256sum $alpineLocalFilePath && " +
                "echo \"${alpineUploadLogs[-2]}\" | sha256sum -c -s && " +
                "echo Hash status:\$?"

        ArrayList<String> alpineDownloadLogs = AlpineContainer.runCmdAndRm(downloadScript, 15000, [], dockerHost, certPath)

        then:
        alpineDownloadLogs.last() == "Hash status:0" //Hash was calculated by Alpine as the same
        alpineDownloadLogs[-2] == alpineUploadLogs[-2] //Confirmed Hash was the same locally

        where:
        dockerHost       | certPath | fileSize | port
        dockerRemoteHost | dockerCertPath | "120m"   | "80"
        dockerRemoteHost | dockerCertPath | "20m"    | "90"

    }

    def "test setting nginx root bind dir"(String dockerHost, String certPath, String baseUrl) {

        setup:
        String localNginxRoot = "/tmp/"
        DevStackSpec.log.info("Testing setting nginx root dir")
        DevStackSpec.log.info("\tWill use dir:" + localNginxRoot)

        NginxContainer nginxC = new NginxContainer(dockerHost, certPath)
        //stopAndRemoveContainer([nginxC.containerName])

        when: "bindHtmlRoot should add a mount to the mounts array"
        nginxC.bindHtmlRoot(localNginxRoot, false)

        then:
        nginxC.preparedMounts.size() == 1

        when: "After creating the container, the inspect result should confirm the mount"
        String containerId = nginxC.createContainer()
        DevStackSpec.log.info("\tCreated container:" + containerId)
        assert nginxC.startContainer(): "Error starting container"
        ContainerInspectResponse inspectResponse = dockerClient.inspectContainer(nginxC.id).getContent()
        DevStackSpec.log.info("\tContainer created")

        then:
        inspectResponse.hostConfig.mounts.find { it.source == localNginxRoot }
        DevStackSpec.log.info("\tDocker API confirms mount was created")

        when: "Creating a file in the mounted dir"
        ArrayList<String> cmdOutput = nginxC.runBashCommandInContainer("mkdir -p /usr/share/nginx/html/nginxTest && touch /usr/share/nginx/html/nginxTest/a.file && echo status: \$?")


        then: "Nginx should return the expected files"
        cmdOutput.last().contains("status: 0")
        Unirest.get(baseUrl + "/nginxTest/a.file").asEmpty().status == 200
        Unirest.get(baseUrl + "/MISSINGFILE").asEmpty().status == 404


        cleanup:
        nginxC.runBashCommandInContainer("rm -rf /usr/share/nginx/html/nginxTest")


        where:
        dockerHost       | certPath       | baseUrl
        ""               | ""             | "http://localhost"


    }


}