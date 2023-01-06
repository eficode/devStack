package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.NginxContainer
import com.eficode.devstack.deployment.Deployment
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A simple NGINX file server for anonymous uploads and retrievals
 *
 * Use setPort() to define a custom port, defaults to 80.
 *
 * The constructor lets you bind a directory on the Docker Engine to the directory used by NGINX for storage
 *
 */

class NginxFileServer implements Deployment {

    Logger log = LoggerFactory.getLogger(this.class)
    String friendlyName = "Nginx File Server"
    ArrayList<Container> containers = []

    private String getFileServerConfig() {

        return """
        server {
            listen       ${container.containerMainPort};
            listen  [::]:${container.containerMainPort};
            server_name  localhost;
            client_max_body_size 2048M;
        
            location ~ "/([0-9a-zA-Z-.]*)\$" {
                alias     /usr/share/nginx/html/\$1;
                client_body_temp_path  /tmp/;
                autoindex on;
                dav_methods  PUT DELETE MKCOL COPY MOVE;
                create_full_put_path   on;
                dav_access             group:rw  all:r;
            }
            
        }
        """

    }

    NginxContainer getContainer() {
        return containers.isEmpty() ? null : containers.first() as NginxContainer
    }

    void setPort(String port) {
        assert !container?.created : "Cant set port on container that has been created"

        container.containerMainPort = port
        container.setCustomConfig(fileServerConfig ) //Update config
    }

    /**
     * A Simple HTTP file server for anonymous uploads and retrievals
     * @param bindToEnginePath A directory on the docker engine where NGINX fill write/get files (Optional)
     * @param dockerHost
     * @param dockerCertPath
     */
    NginxFileServer(String bindToEnginePath = "", String dockerHost = "", String dockerCertPath = "") {
        NginxContainer newContainer = new NginxContainer(dockerHost, dockerCertPath)
        newContainer.containerName = friendlyName.replace(" ", "-")
        bindToEnginePath ? newContainer.bindHtmlRoot(bindToEnginePath, false) : null //Bind Nginx file directory to Docker Engine directory
        containers = [newContainer]

        //If container already has been created, don't update file on instantiation of new object representation
        if (!container.created) {
            newContainer.setCustomConfig(fileServerConfig )
        }


    }



    @Override
    boolean setupDeployment() {
        return  containers.first().createContainer() != null
    }
}
