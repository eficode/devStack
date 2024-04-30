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

    DirectorySyncer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }

    static String getSyncScript(String rsyncOptions = "-avh") {

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
                rsync $rsyncOptions /mnt/src/*/ /mnt/dest/ 
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

    /**
     * <pre>
     * Creates a Util container:
     *  1. Listens for file changes in one or more docker engine src paths (hostAbsSourcePaths)
     *  2. If changes are detected rsync is triggered
     *  3. Rsync detects changes and sync them to destVolumeName
     *
     *  The root of all the srcPaths will be combined and synced to destVolume,
     *  ex:
     *      ../srcPath1/file1.txt
     *      ../srcPath2/file2.txt
     *      ../srcPath2/subdir/file3.txt
     *      Will give:
     *      destVolume/file1.txt
     *      destVolume/file2.txt
     *      destVolume/subdir/file3.txt
     *  </pre>
     *
     *  <b>Known Issues</b>
     *  <pre>
     *  Delete events are not properly detected and triggered on,
     *  thus any such actions will only be reflected after
     *  subsequent create/update events.
     *  </pre>
     * @param hostAbsSourcePaths A list of one or more src dirs to sync from
     * @param destVolumeName A docker volume to sync to, if it does not exist it will be created
     * @param rsyncOptions Options to use when running rsync, ie: rsync $rsyncOptions /mnt/src/*\/ /mnt/dest/<p>
     *      example: -avh --delete
     * @param dockerHost Docker host to run on
     * @param dockerCertPath Docker certs to use
     * @return
     */
    static DirectorySyncer createSyncToVolume(ArrayList<String> hostAbsSourcePaths, String destVolumeName, String rsyncOptions = "-avh", String dockerHost = "", String dockerCertPath = "") {

        DirectorySyncer container = new DirectorySyncer(dockerHost, dockerCertPath)
        Logger log = container.log

        container.containerName = container.getAvailableContainerName()
        container.prepareCustomEnvVar(["syncScript=${getSyncScript(rsyncOptions)}"])

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

        container.createContainer(["/bin/sh", "-c", "echo \"\$syncScript\" > /syncScript.sh && /bin/sh syncScript.sh"], [])
        container.startContainer()

        return container
    }


}
