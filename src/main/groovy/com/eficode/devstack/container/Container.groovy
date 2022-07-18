package com.eficode.devstack.container

import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.engine.EngineResponse
import de.gesellix.docker.remote.api.IdResponse
import de.gesellix.docker.remote.api.core.ClientException
import de.gesellix.docker.remote.api.core.Frame
import de.gesellix.docker.remote.api.core.StreamCallback
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.utils.IOUtils
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

trait Container {

    static Logger log = LoggerFactory.getLogger(Container.class)
    static DockerClientImpl dockerClient = new DockerClientImpl()
    abstract String containerName
    abstract String containerMainPort
    String containerId


    abstract String createContainer()






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
        return ping()

    }


    boolean ping() {
        try {
            return dockerClient.ping().content as String == "OK"
        }catch(SocketException ex) {
            log.warn("Failed to ping Docker engine:" + ex.message)
            return false
        }

    }




    boolean isCreated(){

        ArrayList<Map> content = dockerClient.ps().content
        ArrayList<String> containerNames = content.collect {it.Names}.flatten()
        return containerNames.find{ it == "/" + getContainerName()} != null

    }

    String getId() {
        return containerId
    }

    String getContainerId() {

        if (containerId) {
            return containerId
        }
        log.info("\tResolving container ID for: $containerName")

        ArrayList<Map> content = dockerClient.ps().content

        Map container = content.find { it.Names.first() == "/" + containerName }
        this.containerId = container?.Id
        log.info("\tGot:" + this.containerId)

        return containerId
    }

    boolean startContainer() {

        dockerClient.startContainer(containerId)

        return dockerClient.inspectContainer(containerId).content.state.running
    }

    boolean stopAndRemoveContainer() {

        dockerClient.stop(containerId, 240000)
        dockerClient.wait(containerId)
        dockerClient.rm(containerId)


        try {
            dockerClient.inspectContainer(containerId)
        } catch (ClientException ex) {

            if (ex.response.message == "Not Found") {
                return true
            }
        }
        return false


    }

    boolean stopContainer() {
        log.info("Stopping container:" + containerId)
        dockerClient.stop(containerId, 240000)
        if (dockerClient.inspectContainer(containerId).content.state.running) {
            log.warn("\tFailed to stop container" + containerId)
            return false
        }else {
            log.info("\tContainer stopped")
            return true
        }
    }




    static File createTar(ArrayList<String> filePaths, String outputPath) {


        File outputFile = new File(outputPath)
        TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(outputFile.toPath()))

        filePaths.each { filePath ->
            File newEntryFile = new File(filePath)
            assert newEntryFile.isFile() && newEntryFile.canRead(), "Error creating TAR cant read file:" + filePath


            TarArchiveEntry entry = new TarArchiveEntry(newEntryFile, newEntryFile.name)
            entry.setSize(newEntryFile.size())
            out.putArchiveEntry(entry)
            out.write(newEntryFile.bytes)

            out.closeArchiveEntry()
        }

        out.finish()

        return outputFile


    }

    static ArrayList<File> extractTar(File tarFile, String outputPath) {



        log.info("Extracting: " + tarFile.path + " (${tarFile.size()/1024}kB)")
        log.info("\tTo:" + outputPath)
        assert outputPath[-1] == "/", "outputPath must end with /"
        ArrayList<File>outFiles = []
        TarArchiveInputStream i = new TarArchiveInputStream(tarFile.newInputStream())

        ArchiveEntry entry = null

        while ((entry = i.getNextEntry()) != null) {
            String outName = outputPath + entry.name
            log.debug("\tExtracting compressed file:" + entry.name + ", to:" + outputPath)

            File outFile = new File(outName)

            if (entry.isDirectory()) {
                assert outFile.mkdirs(), "Error creating directory:" + outFile.path
            }else {
                outFile.parentFile.mkdirs()
                OutputStream o = Files.newOutputStream(outFile.toPath())
                long output = IOUtils.copy(i,o)
                log.trace("\t\tExtracted ${(output/1024).round(1)}kB")
                o.close()
            }
            outFiles.add(outFile)
        }

        return outFiles
    }


    /**
     * Copy files from a container
     * @param containerPath can be a file or a path (ending in /)
     * @param destinationPath
     * @return
     */
    ArrayList<File> copyFilesFromContainer(String containerPath, String destinationPath) {

        //containerPath can be both a directory or a file
        EngineResponse<InputStream> response = dockerClient.getArchive(containerId, containerPath)


        Path tempFile = Files.createTempFile("docker_download", ".tar")
        FileUtils.copyInputStreamToFile(response.content, tempFile.toFile())

        ArrayList<File> outFiles = extractTar(tempFile.toFile(), destinationPath)

        Files.delete(tempFile)

        return outFiles

    }

    boolean copyFileToContainer(String srcFilePath, String destinationDirectory) {


        File tarFile = createTar([srcFilePath], Files.createTempFile("docker_upload", ".tar").toString())
        dockerClient.putArchive(containerId, destinationDirectory, tarFile.newDataInputStream())

        return tarFile.delete()
    }





    static class ContainerCallback<T> implements StreamCallback<T> {

        ArrayList<String> output = []

        @Override
        void onNext(Object o) {
            if (o instanceof Frame) {
                output.add(o.payloadAsString)
            }else {
                output.add(o.toString())
            }

        }
    }


    ArrayList<String> runBashCommandInContainer(String command, long timeoutS=10) {


        ContainerCallback callBack = new ContainerCallback()
        EngineResponse<IdResponse> response = dockerClient.exec(containerId, ["/bin/bash", "-c", command],callBack , Duration.ofSeconds(timeoutS))



        return callBack.output
    }



}