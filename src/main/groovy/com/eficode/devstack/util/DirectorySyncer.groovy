package com.eficode.devstack.util

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerSummary
import de.gesellix.docker.remote.api.Volume
import org.slf4j.Logger

import javax.naming.NameNotFoundException


class DirectorySyncer implements Container {

    String containerName = "DirectorySyncer"
    String containerMainPort = null
    String containerImage = "alpine"
    String containerImageTag = "latest"
    String defaultShell = "/bin/sh"

    ArrayList<String> srcPaths = []
    String destPath


    final String setupCommands = """
    apk update
    apk add inotify-tools rsync tini
    """.stripIndent()

    /*
    @Override
    boolean runOnFirstStartup() {
        log.info("Installing sync dependencies")
        runBashCommandInContainer(setupCommands, 60, "root")
    }

     */

    DirectorySyncer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }

    static String getSyncScript() {

        return """
        
        apk update
        apk add inotify-tools rsync tini
        #apt update
        #apt install -y inotify-tools rsync tini

        if [ -z "\$(which inotifywait)" ]; then
            echo "inotifywait not installed."
            echo "In most distros, it is available in the inotify-tools package."
            exit 1
        fi
        
        
        function execute() {
            eval "\$@"
                rsync -avh --delete /mnt/src/*/ /mnt/dest/ 
        }
        
        execute""
        
        inotifywait --recursive --monitor --format "%e %w%f" \\
        --event modify,create,delete,moved_from,close_write /mnt/src \\
        | while read changed; do
            echo "\$changed"
            execute "\$@"
        done
        """.stripIndent()

    }

    String getAvailableContainerName(String prefix = "DirectorySyncer") {

        Integer suffixNr = null
        String availableName = null

        ArrayList<ContainerSummary> containers = dockerClient.ps().content


        while (!availableName) {

            String containerName = prefix + (suffixNr ?: "")
            if (!containers.any { container -> container.names.any { name -> name.equalsIgnoreCase("/" + containerName) } }) {
                availableName = containerName
            } else if (suffixNr > 100) {
                throw new NameNotFoundException("Could not find avaialble name for container, last test was: $containerName")
            } else {
                suffixNr ? suffixNr++ : (suffixNr = 1)
            }
        }

        return availableName

    }

    static DirectorySyncer createSyncToVolume(ArrayList<String> hostAbsSourcePaths, String destVolumeName, String dockerHost = "", String dockerCertPath = "") {

        DirectorySyncer container = new DirectorySyncer(dockerHost, dockerCertPath)
        Logger log = container.log

        container.containerName = container.getAvailableContainerName()
        container.prepareCustomEnvVar(["syncScript=${syncScript}"])

        Volume volume = container.dockerClient.getVolumesWithName(destVolumeName).find { true }

        if (volume) {
            log.debug("\tFound existing volume:" + volume.name)
        } else {
            log.debug("\tCreating new volume $destVolumeName")
            EngineResponseContent<Volume> volumeResponse = container.dockerClient.createVolume(destVolumeName)
            volume = volumeResponse?.content
            assert volume: "Error creating volume $destVolumeName, " + volumeResponse?.getStatus()?.text
            log.debug("\t\tCreated volume:" + volume.name)
        }

        container.prepareVolumeMount(volume.name, "/mnt/dest/", false)

        hostAbsSourcePaths.each { srcPath ->
            String srcDirName = srcPath.substring(srcPath.lastIndexOf("/") + 1)
            container.prepareBindMount(srcPath, "/mnt/src/$srcDirName", true)
        }


        //container.createSleepyContainer()
        //container.createContainer(["echo \"$syncScript\" > /syncScript.sh", "sleep 10"], ["/sbin/tini", "--"])
        container.createContainer(["/bin/sh", "-c", "echo \"\$syncScript\" > /syncScript.sh && /bin/sh syncScript.sh"], [])
        //container.createContainer(["whoami"], [])
        //container.createContainer(["/bin/sh", "-c", "echo \"$syncScript\" > /syncScript.sh && /bin/sh syncScript.sh"], [])
        //container.createContainer(["echo", "hej"], ["/sbin/tini", "--"])
        container.startContainer()

        return container
    }


}
