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


    NginxContainer() {}

    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost  ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    NginxContainer(String dockerHost, String dockerCertPath) {
        assert setupSecureRemoteConnection(dockerHost, dockerCertPath) : "Error setting up secure remote docker connection"
    }


    /**
     * Bind local dir to nginx default root dir /usr/share/nginx/html
     * Must be called before creating container
     * @param sourceAbs absolut path to local dir
     * @param readOnly
     */
    void bindHtmlRoot(String sourceAbs, boolean readOnly = true) {
        prepareBindMount(sourceAbs,"/usr/share/nginx/html" , readOnly)
    }

}
