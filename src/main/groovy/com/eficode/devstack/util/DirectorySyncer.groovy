package com.eficode.devstack.util

import com.eficode.devstack.container.Container
import com.fasterxml.jackson.databind.ObjectMapper
import de.gesellix.docker.client.network.ManageNetworkClient
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

    /**
     * Create a DirectorySyncer based on existing container
     * @param dockerClient
     * @param summary
     */
    DirectorySyncer (DockerClientDS dockerClient, ContainerSummary summary) {

        this.dockerClient = dockerClient
        this.networkClient = dockerClient.getManageNetwork() as ManageNetworkClient
        this.containerName = summary.names.first().replaceFirst("/", "")


    }

    static String getPollBasedSyncScript(String rsyncOptions = "-avh", String rsyncSrc = "/mnt/src/", String rsyncDest = "/mnt/dest/", Double intervalS = 1.5) {

        return """
        apk update
        apk add rsync
        watch -n $intervalS "rsync $rsyncOptions ${rsyncSrc.replace(" ", "\\ ")} ${rsyncDest.replace(" ", "\\ ")}"
        
        """.stripIndent()

    }


    //Has problems with recursive dirs added after start
    static String getEventBasedSyncScript(String rsyncOptions = "-avh", String rsyncSrc = " /mnt/src/", String rsyncDest = " /mnt/dest/") {

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
        
        echo inotifywait is installed
        
        function execute() {
            eval "\$@"
                rsync $rsyncOptions $rsyncSrc $rsyncDest
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

    static DirectorySyncer getDuplicateContainer(DockerClientDS dockerClientDS, String containerName) {
        DirectorySyncer syncer = new DirectorySyncer(dockerClientDS.host, dockerClientDS.certPath)
        syncer.containerName = containerName

        return syncer.getDuplicateContainer()
    }



    /**
     * Checks if there already exists a DirectorySyncer container with the same mount points,
     * if found will return that container
     * @return
     */
    DirectorySyncer getDuplicateContainer() {


        Map filterMap = [name: [this.containerName]]
        String filterString = new ObjectMapper().writeValueAsString(filterMap)
        ArrayList<ContainerSummary> matchingContainers = dockerClient.ps(true, null, false, filterString).content
        if (matchingContainers.size() > 1){
            throw new InputMismatchException("Error determining duplicate container based on name:" + this.containerName)
        }else if (matchingContainers.size() == 0) {
            return null
        }else {
            return new DirectorySyncer(dockerClient, matchingContainers.first())
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
     * @param containerName Optional, if not given, one will be made up.
     * @param dockerHost Docker host to run on
     * @param dockerCertPath Docker certs to use
     * @return
     */
    static DirectorySyncer createSyncToVolume(ArrayList<String> hostAbsSourcePaths, String destVolumeName, String containerName, String rsyncOptions = "-avh",  String dockerHost = "", String dockerCertPath = "") {

        DirectorySyncer container = new DirectorySyncer(dockerHost, dockerCertPath)
        Logger log = container.log

        container.containerName = containerName ?: container.getAvailableContainerName()
        container.prepareCustomEnvVar(["syncScript=${getPollBasedSyncScript(rsyncOptions, "/mnt/src/*/")}"])

        Volume volume = container.dockerClient.getOrCreateVolume(destVolumeName)

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


        container.createContainer([container.defaultShell, "-c", "echo \"\$syncScript\" > /syncScript.sh && ${container.defaultShell} syncScript.sh"], [])
        container.startContainer()

        return container
    }

    /**
     * Creates a DirectorySyncer intended to sync files between two volumes and replacing the owner of the synced files so that
     * the destination container user has access to.
     * @param srcVolumeName The volume to sync from (the root of this will be synced)
     * @param destVolumeName The destination volume where files should be synced to, and where the owner will be changed
     * @param destUser The destination user and group that the file owner will be changed to, ex: 1001:1001
     * @return
     */
    static DirectorySyncer syncBetweenVolumesAndUsers(String srcVolumeName, String destVolumeName, String destUser, String containerName = "") {

        DirectorySyncer syncer =  createSyncVolumeToVolume(srcVolumeName, destVolumeName, "-avhog --chown $destUser", containerName)

        return syncer
    }


    /**
     * Creates a DirectorySyncer which synces files between the roots of two docker volumes
     * @param srcVolumeName The source volume to sync from
     * @param destVolumeName The destination volume to sync to
     * @param rsyncOptions Options to pass to rsync, default: -avh
     * @param containerName Name of the sync container
     * @param dockerHost
     * @param dockerCertPath
     * @return
     */
    static DirectorySyncer createSyncVolumeToVolume(String srcVolumeName, String destVolumeName, String rsyncOptions = "-avh",  String containerName = "",String dockerHost = "", String dockerCertPath = "") {

        DirectorySyncer container = new DirectorySyncer(dockerHost, dockerCertPath)
        Logger log = container.log

        container.containerName = containerName ?: container.getAvailableContainerName()
        container.prepareCustomEnvVar(["syncScript=${getPollBasedSyncScript(rsyncOptions)}"])

        Volume destVolume = container.dockerClient.getOrCreateVolume(destVolumeName)
        Volume srcVolume = container.dockerClient.getOrCreateVolume(srcVolumeName)

        container.prepareVolumeMount(srcVolume.name, "/mnt/src/", false)
        container.prepareVolumeMount(destVolume.name, "/mnt/dest/", false)


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
