package com.eficode.devstack.container.impl

import com.eficode.devstack.container.Container

/**
 * This created a container intended for "Docker outside of Docker (Dood)
 * https://shisho.dev/blog/posts/docker-in-docker/
 *
 * It has the docker cli client installed and setup to by default communicate with he parent docker nodes engine
 */

class DoodContainer implements Container {

    String containerName = "Dood"
    String containerMainPort = ""
    String containerImage = "ubuntu"
    String containerImageTag = "latest"



    DoodContainer(String dockerHost = "", String dockerCertPath = "") {

        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
        prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock")
    }

    @Override
    boolean runOnFirstStartup() {

        ArrayList<String> cmdOutput = runBashCommandInContainer("apt-get update && apt upgrade -y && apt-get install -y locales htop nano inetutils-ping net-tools && localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8; echo status: \$?", 260)
        assert cmdOutput.last() == "status: 0": "Error installing basic dependencies:" + cmdOutput.join("\n")


        cmdOutput = runBashCommandInContainer("apt install -y ca-certificates curl gnupg lsb-release; echo status: \$?", 100)
        assert cmdOutput.last() == "status: 0": "Error installing docker dependencies:" + cmdOutput.join("\n")



        String setupRepoCmd = """
            mkdir -p /etc/apt/keyrings && curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \\
            echo \\
            "deb [arch=\$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \\
            \$(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null\\
            ; echo status: \$?
        """
        cmdOutput = runBashCommandInContainer(setupRepoCmd, 10)
        assert cmdOutput.last() == "status: 0": "Error adding docker repo:" + cmdOutput.join("\n")

        cmdOutput = runBashCommandInContainer("apt-get update && apt install -y docker-ce-cli docker-compose ; echo status: \$?", 120)
        assert cmdOutput.last() == "status: 0": "Error installing docker client:" + cmdOutput.join("\n")

        cmdOutput = runBashCommandInContainer("docker info | grep ID:")
        assert  cmdOutput.any {it.contains(dockerClient.info().content.getID() )} : "Error, child container can not communicate with parent docker node"

        return runAfterDockerSetup()
    }

    /**
     * This is run once after the docker client has been installed and verified that it can talk with parent docker node
     * @return true on success
     */
    boolean runAfterDockerSetup() {
        return true
    }

}
