package com.eficode.devstack.container

import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.client.network.ManageNetworkClient
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.engine.EngineResponse
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.ContainerState
import de.gesellix.docker.remote.api.IdResponse
import de.gesellix.docker.remote.api.Mount
import de.gesellix.docker.remote.api.Network
import de.gesellix.docker.remote.api.NetworkCreateRequest
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

trait Container {

    static Logger log = LoggerFactory.getLogger(Container.class)
    static DockerClientImpl dockerClient = new DockerClientImpl()
    static ManageNetworkClient networkClient = dockerClient.getManageNetwork() as ManageNetworkClient
    abstract String containerName
    abstract String containerMainPort
    String containerId
    ArrayList<Mount> mounts = []


    void prepareBindMount(String sourceAbs, String target, boolean readOnly = true) {
        assert !isCreated(): "Bind mounts cant be prepared for already created container"

        this.mounts.add(
                new Mount().tap { m ->
                    m.source = sourceAbs
                    m.target = target
                    m.readOnly = readOnly
                    m.type = Mount.Type.Bind
                }
        )
    }


    abstract String createContainer()

    abstract boolean runOnFirstStartup()


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
        dockerClient = new DockerClientImpl(dockerConfig)
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

        ArrayList<Map> content = dockerClient.ps().content
        ArrayList<String> containerNames = content.collect { it.Names }.flatten()
        return containerNames.find { it == "/" + self.containerName } != null

    }

    String getId() {
        return containerId
    }

    def getSelf() {
        return this
    }

    String getContainerId() {

        if (containerId) {
            return containerId
        }
        log.info("\tResolving container ID for:" + self.containerName)



        ArrayList<Map> content = dockerClient.ps().content

        Map container = content.find { it.Names.first() == "/" + self.containerName }
        this.containerId = container?.Id
        log.info("\tGot:" + this.containerId)

        return containerId
    }

    boolean startContainer() {


        boolean firstStartup = hasNeverBeenStarted()
        dockerClient.startContainer(self.containerId)

        if (firstStartup) {
            assert runOnFirstStartup(): "Error running initial startup commands inside of the container"
        }


        return isRunning()
    }

    ContainerInspectResponse inspect() {
        return self.containerId ? dockerClient.inspectContainer(self.containerId).content : null
    }

    boolean isRunning() {
        return inspect().state.running
    }


    /**
     * Returns true if the container has been created but never started
     * @return
     */
    boolean hasNeverBeenStarted() {

        ContainerState.Status status = inspect()?.state?.status

        if (status == ContainerState.Status.Created ) {
            return true //Created but not started
        }else if (status == null) {
            return true //Not even created
        }else {
            return false
        }

    }

    String getIp() {
        inspect().networkSettings.ipAddress
    }

    boolean stopAndRemoveContainer() {

        if (self.containerId) {
            dockerClient.stop(self.containerId, 240000)
            dockerClient.wait(self.containerId)
            dockerClient.rm(self.containerId)


            try {
                inspect()
            } catch (ClientException ex) {

                if (ex.response.message == "Not Found") {
                    containerId = null
                    return true
                }
            }
            return false
        } else {
            return false
        }


    }

    boolean stopContainer() {
        log.info("Stopping container:" + self.containerId)
        dockerClient.stop(self.containerId, 240000)
        if (running) {
            log.warn("\tFailed to stop container" + self.containerId)
            return false
        } else {
            log.info("\tContainer stopped")
            return true
        }
    }


    static File createTar(ArrayList<String> filePaths, String outputPath) {


        log.info("Creating tar file:" + outputPath)
        log.debug("\tUsing source paths:")
        filePaths.each { log.debug("\t\t$it") }


        File outputFile = new File(outputPath)
        TarArchiveOutputStream tarArchive = new TarArchiveOutputStream(Files.newOutputStream(outputFile.toPath()))

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
                    TarArchiveEntry entry = new TarArchiveEntry(subFile, path)
                    entry.setSize(subFile.size())
                    tarArchive.putArchiveEntry(entry)
                    tarArchive.write(subFile.bytes)
                    tarArchive.closeArchiveEntry()
                    log.trace("\t" * 5 + "Added to archive")
                }
            } else {
                log.trace("\t" * 4 + "Processing file:" + newEntryFile.name)
                TarArchiveEntry entry = new TarArchiveEntry(newEntryFile, newEntryFile.name)
                entry.setSize(newEntryFile.size())
                tarArchive.putArchiveEntry(entry)
                tarArchive.write(newEntryFile.bytes)
                tarArchive.closeArchiveEntry()
                log.trace("\t" * 5 + "Added to archive")

            }


        }

        tarArchive.finish()

        return outputFile


    }

    static ArrayList<File> extractTar(File tarFile, String outputPath) {


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
     * Creates a network of the type bridge, or returns an existing one if one with the same name exists
     * @param networkName name of the network
     * @return the created/existing network
     */
    static Network createBridgeNetwork(def networkName) {

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


    static Boolean removeBridgeNetwork(String networkId) {


        log.info("Removing bridge network:" + networkId)

        Network network = networkClient.networks().content.find { it.id == networkId && it.driver == "bridge" }
        assert network : "Could not find a bridge network with ID:" + networkId
        networkClient.rmNetwork(network.id)

        return networkClient.networks([filters: [id: [network.id]]])?.content?.isEmpty()

    }

    /**
     * Gets a bridge network based on name, note there might be multiple networks with the same name
     * @param networkName
     * @return null or one of the matching networks
     */
    static Network getBridgeNetwork(String networkName) {


        Network network = networkClient.networks().content.find { it.name == networkName && it.driver == "bridge" }

        return network
    }

    boolean addContainerToNetwork(String networkId) {


        networkClient.connectNetwork(networkId, containerId)


        def here = networkClient.inspectNetwork(networkId).content.containers

        return false


    }




    /**
     * Copy files from a container
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

    boolean copyFileToContainer(String srcFilePath, String destinationDirectory) {


        File tarFile = createTar([srcFilePath], Files.createTempFile("docker_upload", ".tar").toString())
        dockerClient.putArchive(self.containerId, destinationDirectory, tarFile.newDataInputStream())

        return tarFile.delete()
    }


    static class ContainerCallback<T> implements StreamCallback<T> {

        ArrayList<String> output = []

        @Override
        void onNext(Object o) {
            if (o instanceof Frame) {
                output.add(o.payloadAsString)
            } else {
                output.add(o.toString())
            }

        }
    }


    ArrayList<String> runBashCommandInContainer(String command, long timeoutS = 10) {

        log.info("Executing bash command in container:")
        log.info("\tContainer:" + self.containerName + " (${self.containerId})")
        log.info("\tCommand:" + command)
        log.info("\tTimeout:" + timeoutS)
        if (log.isTraceEnabled()) {
            log.trace("\tDocker ping:" + dockerClient.ping().content as String)
        }


        ContainerCallback callBack = new ContainerCallback()
        EngineResponse<IdResponse> response = dockerClient.exec(self.containerId, ["/bin/bash", "-c", command], callBack, Duration.ofSeconds(timeoutS))


        return callBack.output
    }


}