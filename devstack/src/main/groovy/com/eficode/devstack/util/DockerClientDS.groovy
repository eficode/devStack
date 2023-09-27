package com.eficode.devstack.util

import com.eficode.devstack.container.impl.UbuntuContainer
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.engine.EngineResponse
import de.gesellix.docker.remote.api.ContainerSummary
import de.gesellix.docker.remote.api.ExecConfig
import de.gesellix.docker.remote.api.ExecStartConfig
import de.gesellix.docker.remote.api.IdResponse
import de.gesellix.docker.remote.api.SystemInfo
import de.gesellix.docker.remote.api.Volume
import de.gesellix.docker.remote.api.VolumeCreateOptions
import de.gesellix.docker.remote.api.VolumeListResponse
import de.gesellix.docker.remote.api.core.Frame
import de.gesellix.docker.remote.api.core.StreamCallback
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration

import static java.net.Proxy.NO_PROXY

class DockerClientDS extends DockerClientImpl {

    private final Logger log = LoggerFactory.getLogger(DockerClientDS)


    DockerClientDS() {
        super()
    }


    DockerClientDS(String dockerHost) {
        super(dockerHost)
    }

    DockerClientDS(DockerEnv env, Proxy proxy = NO_PROXY) {
        super(env, proxy)
    }

    DockerClientDS(DockerClientConfig dockerClientConfig, Proxy proxy = NO_PROXY) {
        super(dockerClientConfig, proxy)
    }

    String getHost() {
        return this.getEnv().dockerHost
    }

    String getCertPath() {
        return this.getEnv().certPath
    }


    /**
     * Returns the CPU architecture of the Docker engine
     * @return ex: x86_64, aarch64 (arm)
     */
    String getEngineArch() {

        SystemInfo info = info().content

        return info.architecture

    }


    ArrayList<Volume> getVolumesWithLabel(String label) {
        EngineResponseContent<VolumeListResponse> response = volumes("{\"label\":[\"$label\"]}")

        return response?.content?.volumes
    }


    ArrayList<Volume> getVolumesWithName(String name) {
        EngineResponseContent<VolumeListResponse> response = volumes("{\"name\":[\"$name\"]}")

        return response?.content?.volumes
    }


    ArrayList<ContainerSummary> getContainersUsingVolume(Volume volume) {


        EngineResponse response = ps(true, 1000, true, " {\"volume\":[\"${volume.name}\"]}")


        ArrayList<ContainerSummary> containers = response.content
        return containers


    }


    EngineResponseContent<Volume> createVolume(String name = null, Map<String, String> labels = null, Map<String, String> driverOpts = null) {
        VolumeCreateOptions volumeOptions = new VolumeCreateOptions()

        //Transform Gstring to regular string
        labels = labels?.collectEntries { [(it.key.toString()): it.value.toString()] }
        driverOpts = driverOpts?.collectEntries { [(it.key.toString()): it.value.toString()] }

        volumeOptions.with { vol ->
            vol.labels = labels
            vol.name = name
            vol.driverOpts = driverOpts
        }


        return createVolume(volumeOptions)


    }


    /**
     * Clone a volume
     * Clone is performed with a simple cp command, will fail if exotic file system is used
     * The src volume must not be mounted to a container currently running
     * @param srcVolumeName Name of the volume to copy
     * @param destVolumeName Name of the destination volume. Must be unique and currently not created
     * @param labels Labels of the new volume, null will leave them empty, [] will inherit from src
     */
    Volume cloneVolume(String srcVolumeName, String destVolumeName, Map<String, Object> labels = null) {

        log.info("Cloning volume:" + srcVolumeName)

        ArrayList<Volume> srcVolumes = getVolumesWithName(srcVolumeName).findAll { it.name == srcVolumeName }
        assert srcVolumes.size() == 1: "Error finding source volume:" + srcVolumeName

        Volume srcVolume = srcVolumes.first()

        log.debug("\tSuccessfully identified volume to clone")

        ArrayList<ContainerSummary> containersUsingSrc = getContainersUsingVolume(srcVolume)

        assert containersUsingSrc.empty || containersUsingSrc.findAll { it.state == "running" }.isEmpty(): "Source volume is currently connected to a running container"


        Map destLabels

        switch (labels) {

            case null:
                destLabels = null
                break
            case []:
                destLabels = srcVolume.labels
                break
            default:
                destLabels = labels
                break
        }


        Volume destVolume = getVolumesWithName(destVolumeName).find { it.name == destVolumeName }

        assert destVolume == null: "Destination name is already in use"

        destVolume = createVolume(destVolumeName, destLabels, srcVolume.options)?.content

        assert destVolume: "Error creating Destination Volume"
        log.info("\tCreated destination volume:" + destVolume.name)


        UbuntuContainer ubuntuC = new UbuntuContainer(host, certPath)

        ubuntuC.prepareVolumeMount(srcVolume.name, "/srcVolume")
        ubuntuC.prepareVolumeMount(destVolume.name, "/destVolume", false)

        ubuntuC.createSleepyContainer()
        ubuntuC.startContainer()
        ArrayList<String> cmdOut = ubuntuC.runBashCommandInContainer("cp -av /srcVolume/* /destVolume/ && echo status: \$?")
        ubuntuC.stopAndRemoveContainer()
        assert cmdOut.any { it == "status: 0" }: "Error copying data to cloned volume"
        log.info("\tSuccessfully copied data to volume")

        return destVolume


    }


    EngineResponseContent<IdResponse> exec(String containerId, List<String> command, StreamCallback<Frame> callback, Duration timeout, ExecConfig execConfig) {


        log.info("docker exec '${containerId}' '${command}'")


        EngineResponseContent<IdResponse> execCreateResult = createExec(containerId, execConfig)
        String execId = execCreateResult.content.id
        ExecStartConfig execStartConfig = new ExecStartConfig(
                (execConfig.detachKeys ?: false) as Boolean,
                execConfig.tty
        )
        startExec(execId, execStartConfig, callback, timeout)
        return execCreateResult

    }

}
