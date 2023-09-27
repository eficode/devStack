package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container
import de.gesellix.docker.client.EngineResponseContent
import de.gesellix.docker.remote.api.ContainerCreateRequest
import de.gesellix.docker.remote.api.HostConfig
import de.gesellix.docker.remote.api.Mount
import de.gesellix.docker.remote.api.PortBinding

class NginxContainer implements Container{

    String containerName = "Nginx"
    String containerMainPort= "80"
    String containerImage = "nginx"
    String containerImageTag ="alpine"
    String defaultShell = "/bin/sh"

    private String customConfigCached



    NginxContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }


    /**
     * Replaces /etc/nginx/conf.d/default.conf and restarts the nginx daemon if needed
     * @param customConfig Contents of a new default.conf file
     */
    void setCustomConfig(String customConfig) {
        customConfigCached = customConfig

        if (running) {
            replaceFileInContainer(customConfigCached, "/etc/nginx/conf.d/default.conf")
            assert runBashCommandInContainer("nginx -s reload")?.first()?.contains("process started") : "Error reloading nginx service after updating config"
        }
    }

    @Override
    boolean runOnFirstStartup() {

        if (customConfigCached) {
            replaceFileInContainer(customConfigCached, "/etc/nginx/conf.d/default.conf")
            assert runBashCommandInContainer("nginx -s reload")?.first()?.contains("process started") : "Error reloading nginx service after updating config"

        }

        return true
    }


    /**
     * Bind docker engine local dir to nginx default root dir /usr/share/nginx/html
     * Must be called before creating container
     * @param sourceAbs absolut path to local dir
     * @param readOnly
     */
    void bindHtmlRoot(String sourceAbs, boolean readOnly = true) {
        prepareBindMount(sourceAbs,"/usr/share/nginx/html" , readOnly)
    }

}
