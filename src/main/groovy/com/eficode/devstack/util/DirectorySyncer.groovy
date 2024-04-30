package com.eficode.devstack.util

import com.eficode.devstack.container.Container
import com.fasterxml.jackson.databind.ObjectMapper
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerSummary
import de.gesellix.docker.remote.api.Mount
import de.gesellix.docker.remote.api.MountPoint
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

    /**
     * Create a DirectorySyncer based on existing container
     * @param dockerClient
     * @param summary
     */
    DirectorySyncer (DockerClientDS dockerClient, ContainerSummary summary) {

        DirectorySyncer syncer = new DirectorySyncer(dockerClient.host, dockerClient.certPath)
        syncer.containerName = summary.names.first().replaceFirst("/", "")


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
     * Checks if there already exists a DirectorySyncer container with the same mount points,
     * if found will return that container
     * @return
     */
    DirectorySyncer getDuplicateContainer() {

        Map filterMap = [name: ["DirectorySyncer.*"], "volume": this.preparedMounts.collect { it.target }]
        String filterString = new ObjectMapper().writeValueAsString(filterMap)
        ArrayList<ContainerSummary> looselyMatchingContainers = dockerClient.ps(true, null, false, filterString).content
        ArrayList<ContainerSummary> matchingContainers = []
        ArrayList<String> myMounts = this.preparedMounts.target
        myMounts += this.preparedMounts.findAll {it.type == Mount.Type.Volume}.source
        if (looselyMatchingContainers) {
            matchingContainers = looselyMatchingContainers.findAll { matchingContainer ->

                ArrayList<String> matchingMounts = matchingContainer.mounts.destination
                matchingMounts += matchingContainer.mounts.findAll {it.type == MountPoint.Type.Volume}.name
                //Handles the fact the mount points arent always given with a trailing /
                Boolean mountsMatch = myMounts.every { myMount ->
                    matchingMounts.any { it.equalsIgnoreCase(myMount) } ||
                            matchingMounts.collect { it + "/" }.any { it.equalsIgnoreCase(myMount) }
                }

                return mountsMatch

            }
        }

        if (matchingContainers.size() > 1) {
            throw new InputMismatchException("Found multiple potential duplicate DirectorySyncer´s: " + matchingContainers.collect { it.id }.join(","))
        } else if (matchingContainers.size() == 1) {
            return new DirectorySyncer(dockerClient, matchingContainers.first())
        } else {
            return null
        }

    }

    /**
     * <pre>
     * This UtilContainer is intended to be used to sync one or several docker engine local dirs
     * to a Docker volume continuously
     *
     * If a DirectorySyncer with the same mount points exists, it will be started and returned instead
     *
     * The container will :
     *  1. Listen for file changes in one or more docker engine src paths recursively (hostAbsSourcePaths)
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

        hostAbsSourcePaths.eachWithIndex { srcPath, index ->

            String srcDirName = srcPath.substring(srcPath.lastIndexOf("/") + 1)
            String targetPath = "/mnt/src/$srcDirName"
            if (container.preparedMounts.any { it.target == targetPath }) {
                targetPath = targetPath + index //Make sure target path is unique
            }
            container.prepareBindMount(srcPath, targetPath, true)
        }

        DirectorySyncer duplicate = container.getDuplicateContainer()
        if (duplicate) {
            log.info("\tFound an existing DirectorySyncer with same mount points:" + duplicate.shortId)
            if (!duplicate.running) {
                log.debug("\t" * 2 + "Duplicate is not running, starting it")
                duplicate.startContainer()
            }
            log.info("\t" * 2 + "Returning duplicate instead of creating a new one")
            return duplicate
        }

        container.createContainer(["/bin/sh", "-c", "echo \"\$syncScript\" > /syncScript.sh && /bin/sh syncScript.sh"], [])
        container.startContainer()

        return container
    }


}
