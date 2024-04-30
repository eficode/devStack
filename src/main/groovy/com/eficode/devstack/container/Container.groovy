package com.eficode.devstack.container

import com.eficode.devstack.util.DockerClientDS
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.client.network.ManageNetworkClient
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.engine.EngineResponse
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.ContainerSummary
import de.gesellix.docker.remote.api.DeviceMapping
import de.gesellix.docker.remote.api.EndpointSettings
import de.gesellix.docker.remote.api.ExecConfig
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.Mount
import de.gesellix.docker.remote.api.MountPoint
import de.gesellix.docker.remote.api.Network
import de.gesellix.docker.remote.api.NetworkCreateRequest
import de.gesellix.docker.remote.api.PortBinding
import de.gesellix.docker.remote.api.core.ClientException
import de.gesellix.docker.remote.api.core.Frame
import de.gesellix.docker.remote.api.core.StreamCallback
import groovy.io.FileType
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.utils.IOUtils
import org.apache.commons.io.FileUtils
import org.codehaus.groovy.runtime.ResourceGroovyMethods
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.regex.Matcher
import java.util.regex.Pattern

trait Container {

    Logger log = LoggerFactory.getLogger(self.class)
    DockerClientDS dockerClient = new DockerClientDS()
    ManageNetworkClient networkClient = dockerClient.getManageNetwork() as ManageNetworkClient
    abstract String containerName
    abstract String containerMainPort
    abstract String containerImage
    abstract String containerImageTag
    ArrayList<String> containerDefaultNetworks = ["bridge"]
    ArrayList<String> customEnvVar = []

    String defaultShell = "/bin/bash"
    String containerId
    ArrayList<Mount> preparedMounts = [] //Mounts that will be added at creation
    private ArrayList<DeviceMapping> preparedDevices = []
    private ArrayList<String> preparedCapabilities = []


    /**
     * Prior to create a container, prepare mount-points
     * @param sourceAbs The source directory in the docker engine
     * @param target The target directory inside the container
     * @param readOnly
     */
    void prepareBindMount(String sourceAbs, String target, boolean readOnly = true) {

        Mount newMount = new Mount().tap { m ->
            m.source = sourceAbs
            m.target = target
            m.readOnly = readOnly
            m.type = Mount.Type.Bind
        }

        if (!self.preparedMounts.find { it.source == sourceAbs && it.target == target }) {
            self.preparedMounts.add(newMount)
        }
    }

    /**
     * Prepare mounting of an existing or new volume
     * @param volumeName The name of the volume to create, or an existing one to mount
     * @param target Where to mount it in the container
     * @param readOnly If it should be read only or not
     */
    void prepareVolumeMount(String volumeName, String target, boolean readOnly = true) {

        Mount newMount = new Mount().tap { m ->
            m.source = volumeName
            m.target = target
            m.readOnly = readOnly
            m.type = Mount.Type.Volume
        }

        if (!self.preparedMounts.find { it.source == volumeName && it.target == target }) {
            self.preparedMounts.add(newMount)
        }
    }

    /**
     * This device mapping will be added to containers when they are being created
     * https://docs.docker.com/reference/cli/docker/container/run/#device
     * @param hostPath source path on host
     * @param containerPath destination path in container, if null the same path as hostPath will be used
     * @param permissions CgroupPermissions ex: rwm
     */
    void prepareDevice(String hostPath, String containerPath = null, String permissions = "rwm") {

        DeviceMapping deviceMapping = new DeviceMapping()
        deviceMapping.setPathInContainer(containerPath ?: hostPath)
        deviceMapping.setPathOnHost(hostPath)
        deviceMapping.setCgroupPermissions(permissions)

        prepareDevice(deviceMapping)
    }
    /**
     * This device mapping will be added to containers when they are being created
     * https://docs.docker.com/reference/cli/docker/container/run/#device
     * @param deviceMapping
     */
    void prepareDevice(DeviceMapping deviceMapping) {


        log.info("Adding device: ${deviceMapping.pathOnHost} to container: ${self.shortId}:" + deviceMapping.pathInContainer)
        if (!self.created) {
            preparedDevices.add(deviceMapping)
            log.debug("\tDevice prepared and will be added when container is created")
        } else if (hasDevice(deviceMapping)) {
            log.info("\tContainer already has device")
        } else {
            throw new InputMismatchException("Cant add new device to already existing container")
        }

    }
    /**
     * Checks if Container has device
     * @param device
     * @return true if found
     */
    boolean hasDevice(DeviceMapping deviceMapping) {
        ArrayList<DeviceMapping> deviceMappings = inspectContainer().hostConfig.devices

        return deviceMappings.any { it == deviceMapping }

    }

    /**
     * This capability will be added to containers when they are being created
     * @param capability, ex: SYS_ADMIN
     */
    void prepareCapability(String capability) {


        log.info("Adding capability: ${capability} to container: ${self.shortId}")
        if (!self.created) {
            preparedCapabilities.add(capability)
            log.debug("\tCapability prepared and will be added when container is created")
        } else if (hasCapability(capability)) {
            log.info("\tContainer already has capability")
        } else {
            throw new InputMismatchException("Cant add new capability to already existing container")
        }
    }

/**
 * Checks if Container has capability
 * @param capability
 * @return true if found
 */
    boolean hasCapability(String capability) {
        ArrayList<String> capabilities = inspectContainer().hostConfig.getCapAdd()

        return capabilities.any { it == capability }

    }


/**
 * Get MountPoints currently attached to container
 * @return
 */
    ArrayList<MountPoint> getMounts() {

        ContainerInspectResponse response = inspectContainer()
        return response?.mounts
    }

    ContainerCreateRequest setupContainerCreateRequest() {

        ContainerCreateRequest containerCreateRequest = new ContainerCreateRequest().tap { c ->

            c.image = self.containerImage + ":" + self.containerImageTag

            if (self.containerMainPort) {
                c.exposedPorts = [(self.containerMainPort + "/tcp"): [:]]
            }


            c.hostConfig = new HostConfig().tap { h ->
                if (self.containerMainPort) {
                    h.portBindings = [(self.containerMainPort + "/tcp"): [new PortBinding("0.0.0.0", (self.containerMainPort))]]
                }
                h.mounts = self.preparedMounts

                h.setDevices(preparedDevices)
                h.setCapAdd(preparedCapabilities)

            }
            c.hostname = self.containerName
            c.env = self.customEnvVar


        }

        return customizeContainerCreateRequest(containerCreateRequest)

    }

/**
 * Helper method that allows you to easily customize the ContainerCreateRequest by overriding just this method
 * @param containerCreateRequest
 * @return
 */
    ContainerCreateRequest customizeContainerCreateRequest(ContainerCreateRequest containerCreateRequest) {
        return containerCreateRequest
    }

/**
 * Create container and override default docker cmd and entrypoint
 * @param cmd :
 * @param entrypoint ex: ["tail", "-f", "/dev/null"]
 * @return container id
 */
    String createContainer(ArrayList<String> cmd = [], ArrayList<String> entrypoint = []) {

        assert ping(): "Error connecting to docker engine"

        ContainerCreateRequest containerCreateRequest = setupContainerCreateRequest()

        if (cmd.size()) {
            containerCreateRequest.cmd = cmd.collect { it.toString() }
        }

        if (entrypoint.size()) {
            containerCreateRequest.entrypoint = entrypoint
        }

        EngineResponseContent response = dockerClient.createContainer(containerCreateRequest, self.containerName)
        assert response.content.warnings.isEmpty(): "Error when creating ${self.containerName} container:" + response.content.warnings.join(",")

        ArrayList<Network> networks = containerDefaultNetworks.collect { createBridgeNetwork(it) }
        assert setContainerNetworks(networks): "Error setting container networks to:" + containerDefaultNetworks

        containerId = response.content.id

        long createTimeoutMs = 10000
        long createWaitStart = System.currentTimeMillis()

        while (!created && (createWaitStart + createTimeoutMs) > System.currentTimeMillis()) {
            sleep(1000)
        }
        if (!created) {
            log.warn("Timed out waiting for container to be created: " + containerId)
        }

        return containerId

    }


/**
 * Will create a Container that will sleep indefinitely, ie wont shut of once entrypoint has finished executing
 * @return container id
 */
    String createSleepyContainer() {
        return createContainer([], ["tail", "-f", "/dev/null"])
    }


    boolean runOnFirstStartup() {
        return true
    }


/**
 * Replaced the default docker connection (local) with a remote, secure one
 * @param host ex: "https://docker.domain.se:2376"
 * @param certPath folder containing ca.pem, cert.pem, key.pem
 */
    boolean setupSecureRemoteConnection(String host, String certPath) {

        DockerClientConfig dockerConfig = new DockerClientConfig(host)
        DockerEnv dockerEnv = new DockerEnv(host)
        dockerEnv.setCertPath(certPath)
        dockerEnv.setTlsVerify("1")
        dockerConfig.apply(dockerEnv)
        dockerClient = new DockerClientDS(dockerConfig)
        networkClient = dockerClient.getManageNetwork() as ManageNetworkClient


        return ping()

    }


    boolean ping() {
        try {
            return dockerClient.ping().content as String == "OK"
        } catch (SocketException ex) {
            log.warn("Failed to ping Docker engine:" + ex.message)
            return false
        }

    }


    boolean isCreated() {

        try {
            ArrayList<ContainerSummary> content = dockerClient.ps().content
            ArrayList<String> containerNames = content.collect { it.names }.flatten()
            return containerNames.find { it == "/" + self.containerName } != null
        } catch (ignored) {
            return false
        }

    }

/**
 * Returnes the common short form of the container ID
 * @return
 */
    String getShortId() {
        return getContainerId()?.substring(0, 12)
    }

    String getId() {
        return getContainerId()
    }

    Container getSelf() {
        return this
    }

    String getContainerId() {

        if (containerId) {
            return containerId
        }
        log.info("\tResolving container ID for:" + self.containerName)


        ArrayList<ContainerSummary> containers = dockerClient.ps().content

        ContainerSummary matchingContainer = containers.find { it.names.first() == "/" + self.containerName }
        this.containerId = matchingContainer?.id
        log.info("\tGot:" + this.containerId)

        return containerId
    }

    boolean startContainer() {


        log.info("Preparing to start container: ${self.containerName} (${self.containerId})")

        if (status() == ContainerState.Status.Running) {
            log.info("\tContainer is already running")
            return true
        }

        boolean firstStartup = hasNeverBeenStarted()


        dockerClient.startContainer(self.containerId)


        if (firstStartup) {

            log.debug("\tThis is the first time container starts, running one time startup tasks")
            assert runOnFirstStartup(): "Error running initial startup commands inside of the container"
            log.trace("\t\tFinished running first time startup tasks")
        }


        return isRunning()
    }

    ContainerInspectResponse inspectContainer() {
        return self.containerId ? dockerClient.inspectContainer(self.containerId).content : null
    }

    boolean isRunning() {
        return inspectContainer()?.state?.running
    }


/**
 * Returns true if the container has been created but never started
 * @return
 */
    boolean hasNeverBeenStarted() {

        ContainerState.Status status = inspectContainer()?.state?.status

        if (status == ContainerState.Status.Created) {
            return true //Created but not started
        } else {
            return status == null
        }

    }

    ArrayList<String> getIps() {
        ContainerInspectResponse inspectResponse = inspectContainer()
        ArrayList<String> ips = inspectResponse.networkSettings.networks.values().ipAddress

        if (inspectResponse.networkSettings.ipAddress != null) {
            ips.add(inspectResponse.networkSettings.ipAddress)
            ips.unique(true)
        }


        return ips
    }

    ContainerState.Status status() {
        return inspectContainer()?.state?.status
    }

    boolean stopAndRemoveContainer(Integer timeoutS = 5) {

        log.info("Stopping and removing container")
        log.info("\tContainer: ${self.containerName} (${self.containerId})")

        if (self.containerId) {


            if (self.isRunning()) {
                dockerClient.stop(self.containerId, timeoutS)
                if (self.isRunning()) {
                    dockerClient.kill(self.containerId)
                }
            }

            dockerClient.rm(self.containerId)


            try {
                inspectContainer()
            } catch (ClientException ex) {

                if (ex.response.message == "Not Found") {
                    containerId = null
                    return true
                } else {
                    throw new InputMismatchException("Error stopping and removing container")
                }
            }
            return false
        } else {
            log.info("\tContainer not setup, nothing to remove")
            return true
        }


    }

    boolean stopContainer(Integer timeoutS = 15) {
        log.info("Stopping container:" + self.containerId)
        long start = System.currentTimeSeconds()
        running ? dockerClient.stop(self.containerId, timeoutS) : ""

        if (running) {
            log.warn("\tFailed to stop container" + self.containerId)
            log.warn("Gave up waiting to shutdown ${shortId} after ${System.currentTimeSeconds() - start} seconds")
            return false
        } else {
            log.info("\tContainer stopped")
            log.debug("\t\tContainer ${shortId} stopped after ${System.currentTimeSeconds() - start} seconds")
            return true
        }
    }


    File createTar(ArrayList<String> filePaths, String outputPath, ArrayList<String> ignorePaths = []) {


        log.info("Creating tar file:" + outputPath)
        log.debug("\tUsing source paths:")
        filePaths.each { log.debug("\t\t$it") }

        File outputFile = new File(outputPath)
        TarArchiveOutputStream tarArchive = new TarArchiveOutputStream(Files.newOutputStream(outputFile.toPath()))
        tarArchive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

        log.info("\tProcessing files")
        filePaths.each { filePath ->
            log.debug("\t\tEvaluating:" + filePath)

            File newEntryFile = new File(filePath)

            assert (newEntryFile.isDirectory() || newEntryFile.isFile()) && newEntryFile.canRead(), "Error creating TAR cant read file:" + filePath
            log.trace("\t" * 3 + "Can read file/dir")

            if (newEntryFile.isDirectory()) {
                log.trace("\t" * 3 + "File is actually directory, processing sub files")
                newEntryFile.eachFileRecurse(FileType.FILES) { subFile ->

                    String path = ResourceGroovyMethods.relativePath(newEntryFile, subFile)
                    log.trace("\t" * 4 + "Processing sub file:" + path)
                    if (ignorePaths.any { subFile.absolutePath.matches(it) }) {
                        log.trace("\t" * 5 + "File matches a path that is to be ignored, will not process further")
                    } else {
                        TarArchiveEntry entry = new TarArchiveEntry(subFile, path)
                        entry.setSize(subFile.size())
                        tarArchive.putArchiveEntry(entry)
                        tarArchive.write(subFile.bytes)
                        tarArchive.closeArchiveEntry()
                        log.trace("\t" * 5 + "Added to archive")
                    }


                }
            } else {
                log.trace("\t" * 4 + "Processing file:" + newEntryFile.name)

                if (ignorePaths.any { newEntryFile.absolutePath.matches(it) }) {
                    log.trace("\t" * 5 + "File matches a path that is to be ignored, will not process further")
                } else {
                    TarArchiveEntry entry = new TarArchiveEntry(newEntryFile, newEntryFile.name)
                    entry.setSize(newEntryFile.size())
                    tarArchive.putArchiveEntry(entry)
                    tarArchive.write(newEntryFile.bytes)
                    tarArchive.closeArchiveEntry()
                    log.trace("\t" * 5 + "Added to archive")
                }
            }


        }

        tarArchive.finish()
        log.info("\tFinished creating TAR file:" + outputFile.absolutePath)
        log.debug("\t\t" + (outputFile.size() / (1024 * 1024)).round() + "MB")

        return outputFile


    }

    ArrayList<File> extractTar(File tarFile, String outputPath) {


        log.info("Extracting: " + tarFile.path + " (${tarFile.size() / 1024}kB)")
        log.info("\tTo:" + outputPath)
        assert outputPath[-1] == "/", "outputPath must end with /"
        ArrayList<File> outFiles = []
        TarArchiveInputStream i = new TarArchiveInputStream(tarFile.newInputStream())

        ArchiveEntry entry = null

        while ((entry = i.getNextEntry()) != null) {
            String outName = outputPath + entry.name
            log.debug("\tExtracting compressed file:" + entry.name + ", to:" + outputPath)

            File outFile = new File(outName)

            if (entry.isDirectory()) {
                assert outFile.mkdirs(), "Error creating directory:" + outFile.path
            } else {
                outFile.parentFile.mkdirs()
                OutputStream o = Files.newOutputStream(outFile.toPath())
                long output = IOUtils.copy(i, o)
                log.trace("\t\tExtracted ${(output / 1024).round(1)}kB")
                o.close()
            }
            outFiles.add(outFile)
        }

        return outFiles
    }


/**
 * Gets the home path for the containers default user
 * @return ex: /home/user
 */
    String getHomePath() {
        runBashCommandInContainer("pwd").find { true }
    }


/**
 * Creates a network of the type bridge, or returns an existing one if one with the same name exists
 * @param networkName name of the network
 * @return the created/existing network
 */
    Network createBridgeNetwork(String networkName) {

        log.info("Creating network:" + networkName)


        Network existingNetwork = getBridgeNetwork(networkName)

        if (existingNetwork) {
            log.info("\tNetwork already exists (${existingNetwork.id}), returning that.")
            return existingNetwork
        }


        NetworkCreateRequest createRequest = new NetworkCreateRequest(networkName, false, "bridge", null, null, null, null, null, [:], null)


        String networkId = networkClient.createNetwork(createRequest).content.id
        assert networkId: "Error creating network:" + networkName
        log.info("\tCreated:" + networkId)

        Network newNetwork = networkClient.networks([filters: [id: [networkId]]])?.content?.first()
        assert newNetwork: "Error finding newly created network $networkName with id: " + networkId

        return newNetwork
    }


    boolean removeNetwork(Network network) {


        log.info("Removing network:" + network.name)

        networkClient.rmNetwork(network.id)

        return networkClient.networks([filters: [id: [network.id]]])?.content?.isEmpty()

    }

/**
 * Gets a bridge network based on name or id, note there might be multiple networks with the same name
 * @param networkNameOrId
 * @return null or one of the matching networks
 */
    Network getBridgeNetwork(String networkNameOrId) {


        Network network = networkClient.networks().content.find { (it.name == networkNameOrId || it.id == networkNameOrId) && it.driver == "bridge" }

        return network
    }


/**
 * Gets a network based on name or id, note there might be multiple networks with the same name
 * @param networkNameOrId
 * @return Network if found, null if not
 */
    Network getDockerNetwork(String networkNameOrId) {


        Network network = networkClient.networks().content.find { it.name == networkNameOrId || it.id == networkNameOrId }

        return network
    }
/**
 * Gets  networks based on name or id, note there might be multiple networks with the same name
 * @param networkNameOrIds
 * @return Networks if found, null if not
 */
    ArrayList<Network> getDockerNetworks(ArrayList<String> networkNameOrIds) {

        ArrayList<Network> networks = networkClient.networks().content.findAll { it.name in networkNameOrIds || it.id in networkNameOrIds }

        return networks
    }

    boolean networkIsValid(Network network) {
        return getDockerNetwork(network.id) != null
    }

/**
 * Get the networks that this container is connected too
 * @return
 */
    ArrayList<Network> getConnectedContainerNetworks() {
        Map<String, EndpointSettings> rawResponse = inspectContainer().networkSettings.networks

        ArrayList<Network> networks = []
        rawResponse.keySet().each { networkId ->
            Network network = getDockerNetwork(networkId)

            if (network != null) {
                networks.add(network)
            } else if (networkId) {
                //Handle networks that the container is attached to but that have been deleted
                Network deletedNetwork = new Network()
                deletedNetwork.id = networkId
                deletedNetwork.driver = "deleted"
                networks.add(deletedNetwork)
            }
        }

        return networks
    }

    ArrayList<Network> getContainerBridgeNetworks() {


        return getConnectedContainerNetworks().findAll { it.driver == "bridge" }

    }

/**
 * Connect container to an existing network.
 * Note:
 *  A container will by default already belong to a network, so this method might connect the container to a second.
 * @param network
 * @return true on success
 */
    boolean connectContainerToNetwork(Network network) throws InputMismatchException {


        log.info("Connecting container $containerId to network:" + network.name)

        if (!networkIsValid(network)) {
            throw new InputMismatchException("Error connecting container (${containerName}) to network: ${network.name} (${network.id}). Network is not valid")
        }

        networkClient.connectNetwork(network.id, containerId)
        log.trace("\tVerifying container was added to network")


        if (connectedContainerNetworks.find { it.id == network.id } != null) {
            log.info("\tContainer was successfully added to network")
            return true
        }
        log.error("\tContainer failed to be added to network")
        throw new InputMismatchException("Error connecting container (${containerName}) to network: ${network.name} (${network.id})")


    }

    boolean disconnectContainerFromNetwork(Network network) {

        log.info("Disconnecting container $containerId from network:" + network.name)

        networkClient.disconnectNetwork(network.id, containerId)
        log.trace("\tVerifying container was disconnected from network")

        if (!connectedContainerNetworks.find { it.id == network.id }) {
            log.info("\tContainer was successfully disconnected from network")
            return true
        }

        return false


    }

/**
 * Sets networks for the container, disconnecting the container from any networks not in the list
 * @param newNetworks A list of the networks that the container should be connected to
 * @return true on success
 */
    boolean setContainerNetworks(ArrayList<Network> newNetworks) throws InputMismatchException, AssertionError {

        log.info("Setting container networks")
        log.info("\tBeginning by disconnecting any networks it should no longer be connected to")
        connectedContainerNetworks.each { connectedNetwork ->

            if (newNetworks.id.find { newNetworkId -> newNetworkId != connectedNetwork.id }) {
                assert disconnectContainerFromNetwork(connectedNetwork): "Error disconnecting container (${containerName}) from network: ${connectedNetwork.name} (${connectedNetwork.id})"
                log.info("\t\tDisconnected container from network:" + connectedNetwork.name)
            }

        }
        log.info("\tFinished disconnecting container from unwanted networks, now connecting to new networks")

        ArrayList<Network> connectedNetworks = connectedContainerNetworks
        newNetworks.each { wantedNetwork ->

            if (connectedNetworks.id.find { wantedNetwork.id }) {
                log.info("\t\tContainer already connected to ${wantedNetwork.name} (${wantedNetwork.id})")
            } else {
                assert connectContainerToNetwork(wantedNetwork): "Error connecting container (${containerName}) to network: ${wantedNetwork.name} (${wantedNetwork.id})"
                log.info("\t\tConnected container to network:" + wantedNetwork.name)
            }

        }

    }


/**
 * Replaces text content of a file in the container
 * @param content The new content that should be in the file
 * @param filePath Path to the file in the container
 * @param verify If true will read back content of file and verify, might five false negatives in case of special chars
 * @return
 */
    boolean replaceFileInContainer(String content, String filePath, boolean verify = false) {
        ArrayList<String> out = runBashCommandInContainer("cat > $filePath <<- 'EOF'\n" + content + "\nEOF")

        assert out.isEmpty(): "Unexpected output when replacing file $filePath: " + out.join("\n")

        if (verify) {
            ArrayList<String> rawOut = runBashCommandInContainer("cat " + filePath)
            String readOut = rawOut.join()
            assert readOut.trim() == content.trim(): "Error when verifying that the file $filePath was replaced"
            return true
        }

        return true
    }

/**
 * Copy files from a container to local machine
 * @param containerPath can be a file or a path (ending in /)
 * @param destinationPath
 * @return
 */
    ArrayList<File> copyFilesFromContainer(String containerPath, String destinationPath) {

        //containerPath can be both a directory or a file
        EngineResponse<InputStream> response = dockerClient.getArchive(self.containerId, containerPath)


        Path tempFile = Files.createTempFile("docker_download", ".tar")
        FileUtils.copyInputStreamToFile(response.content, tempFile.toFile())

        ArrayList<File> outFiles = extractTar(tempFile.toFile(), destinationPath)

        Files.delete(tempFile)

        return outFiles

    }

/**
 * Creates a temporary tar, copies it to the container and extracts it there
 * @param srcFilePath Local path to copy, will find directories/files recursively
 * @param destinationDirectory The destination path in the container, must already exist and be absolut
 * @param ignorePaths If these regex patterns matches the path/name of a file it wont be copied over.
 *          ex: [".*\\.git.*"]
 * @return true on success
 */
    boolean copyFileToContainer(String srcFilePath, String destinationDirectory, ArrayList<String> ignorePaths = []) {


        File tarFile = createTar([srcFilePath], Files.createTempFile("docker_upload", ".tar").toString(), ignorePaths)
        dockerClient.putArchive(self.containerId, destinationDirectory, tarFile.newDataInputStream())

        return tarFile.delete()
    }


    static class ContainerCallback<T> implements StreamCallback<T> {


        Logger log = LoggerFactory.getLogger(ContainerCallback.class)
        ArrayList<String> output = []

        @Override
        void onNext(Object o) {
            if (o instanceof Frame) {
                output.add(o.payloadAsString)
            } else {
                output.add(o.toString())
            }
            log.debug(output.last())

        }
    }


//Format is one of: `user`, `user:group`, `uid`, or `uid:gid`
    ArrayList<String> runCommandInContainer(String containerId, ArrayList<String> commands, long timeoutS = 10, String userGroup = null, String workingDir = null) {

        log.info("Executing bash command in container:")
        log.info("\tContainer:" + self.containerName + " (${self.containerId})")
        log.info("\tCommand:" + commands)
        log.info("\tTimeout:" + timeoutS)

        long cmdStart = System.currentTimeMillis()

        ExecConfig execConfig = new ExecConfig()
        execConfig.with { ex ->
            ex.attachStdin = false
            ex.attachStdout = true
            ex.attachStderr = true
            ex.detachKeys = null
            ex.tty = false
            ex.env = null
            ex.cmd = commands
            ex.privileged = null
            ex.user = userGroup ?: null
            ex.workingDir = workingDir ?: null
        }

        ContainerCallback callBack = new ContainerCallback()
        dockerClient.exec(containerId, commands, callBack, Duration.ofSeconds(timeoutS), execConfig)

        log.trace("\tCommand finished after:" + ((System.currentTimeMillis() - cmdStart) / 1000).round() + "s")

        return callBack.output

    }


    ArrayList<String> runBashCommandInContainer(String command, long timeoutS = 10, String user = null) {

        return runCommandInContainer(self.containerId, [self.defaultShell, "-c", command], timeoutS, user)


    }

/**
 * Creates an temporary container, runs a command, exits and removes container
 * @param cmd A string that will be passed as a command to /bin/sh -c, ex: echo start;sleep 5
 * @param timeoutMs
 *      0 don't wait, return an array with the container ID immediately,
 *      timeoutMs > 0 Wait for container to stop, if it takes longer than timeoutMs an exception will be thrown
 * @param mounts bind mounts that the container should have:
 *      readOnly is optional and defaults to true
 *      ex:[[src: "/tmp/engine/test", target: "/tmp/container/test", readOnly :true]
 * @param dockerHost
 * @param dockerCertPath
 * @return An array of the container logs, or just an array containing container id if timeoutMs == 0
 */
    static ArrayList<String> runCmdAndRm(String cmd, long timeoutMs, ArrayList<Map> mounts = [], String dockerHost = "", String dockerCertPath = "") {

        return this.runCmdAndRm(["/bin/sh", "-c", cmd], timeoutMs, mounts, dockerHost, dockerCertPath)
    }


/**
 * Creates a temporary container, runs a command, exits and removes container
 * @param container a container object that hasnt yet been created
 * @param cmd An array of commands to run, ex: [ "/bin/sh", "-c", "echo start;sleep 5"]
 * @param timeoutMs
 *      0 don't wait, return an array with the container ID immediately,
 *      timeoutMs > 0 Wait for container to stop, if it takes longer than timeoutMs an exception will be thrown
 * @param mounts bind mounts that the container should have:
 *      readOnly is optional and defaults to true
 *      ex:[[src: "/tmp/engine/test", target: "/tmp/container/test", readOnly :true]
 * @param dockerHost
 * @param dockerCertPath
 * @return An array of the container logs, or just an array containing container id if timeoutMs == 0
 */
    static ArrayList<String> runCmdAndRm(ArrayList<String> cmd, long timeoutMs, ArrayList<Map> mounts = [], String dockerHost = "", String dockerCertPath = "") {


        Container container = this.getConstructor(String, String).newInstance(dockerHost, dockerCertPath)

        Logger log = LoggerFactory.getLogger(this.class)


        log.info("Creating a $container.class.simpleName and running:")
        log.info("\tCmd:" + cmd)


        try {

            container.containerName = container.containerName + "-cmd-" + System.currentTimeMillis().toString()[-5..-1]

            mounts.each {
                log.info("\tPreparing Bind mount:")
                container.prepareBindMount(it.src as String, it.target as String, it.containsKey("readOnly") ? it.readOnly as Boolean : true)
            }


            container.createContainer(cmd)
            log.info("\tCreated container: " + container.id)


            log.info("\tStarted container: " + container.startContainer())
            assert !container.hasNeverBeenStarted(): "Error starting CMD container"

            if (timeoutMs == 0) {
                log.info("\tNo Timeout set, returning container id")
                return [container.id]
            }

            long start = System.currentTimeMillis()

            while (start + timeoutMs > System.currentTimeMillis() && container.running) {

                log.info("\tWaited ${System.currentTimeMillis() - start}ms for container to stop")
                sleep(1000)

            }
            log.info("\tContainer finisehd or timed out after ${System.currentTimeMillis() - start}ms")

            if (container.running) {
                log.info("\t" * 2 + "Stopping container forcefully.")
                ArrayList<String> containerOut = container.containerLogs
                assert container.stopAndRemoveContainer(1): "Error stopping and removing CMD container after it timed out"

                throw new TimeoutException("CMD container timed out, was forcefully stopped and removed. Container logs:" + containerOut?.join("\n"))
            }


            ArrayList<String> containerOut = container.containerLogs

            log.info("\tReturning ${containerOut.size()} log lines")

            assert container.stopAndRemoveContainer(): "Error removing Container:" + container.id
            log.info("\tRemoved container:" + container.id)

            return containerOut
        } catch (ex) {


            try {
                container.stopAndRemoveContainer(1)
            } catch (ignored) {
            }


            throw ex

        }


    }


/**
 * Gets the port from a URL
 * @param url
 * @return
 */
    static String extractPortFromUrl(String url) {
        Pattern pattern = Pattern.compile(".*?:(\\d+)")

        Matcher matcher = pattern.matcher(url)
        if (matcher.find() && matcher.groupCount() > 0) {
            return matcher.group(1)
        } else if (url.startsWith("https")) {
            return "443"
        } else {
            return "80"
        }

    }

    static String extractDomainFromUrl(String url) {
        String out = url.replaceFirst(/^https?:\/\//, "") //Remove protocol
        out = out.replaceFirst(/:\d+\\/?.*/, "") //Remove Port and anything after
        out = out.replaceFirst(/\/.*/, "") //Remove subdomain
        return out
    }

/**
 * Prepare custom environmental variables. Must be set before creating container
 * @param keyVar Ex: ["key=value", "PATH=/user/local/sbin"]
 */
    void prepareCustomEnvVar(ArrayList<String> keyVar) {

        assert hasNeverBeenStarted(): "Error, cant set custom environment variables after creating container"

        self.customEnvVar.addAll(keyVar.collect {it.toString()})
    }


    ArrayList<String> getContainerLogs() {

        if (!self.containerId) {
            return null
        }

        ContainerCallback callBack = new ContainerCallback()
        dockerClient.manageContainer.logs(self.containerId, [follow: false], callBack, Duration.ofMillis(500))

        return callBack.output
    }

}