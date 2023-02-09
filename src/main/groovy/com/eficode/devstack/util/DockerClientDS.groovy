package com.eficode.devstack.util

import com.eficode.devstack.util.DockerClientDS
import de.gesellix.docker.client.DockerClientImpl
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.engine.DockerClientConfig
import de.gesellix.docker.engine.DockerEnv
import de.gesellix.docker.remote.api.ExecConfig
import de.gesellix.docker.remote.api.ExecStartConfig
import de.gesellix.docker.remote.api.IdResponse
import de.gesellix.docker.remote.api.SystemInfo
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
