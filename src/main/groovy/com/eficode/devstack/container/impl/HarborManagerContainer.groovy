package com.eficode.devstack.container.impl

import groovy.yaml.YamlBuilder
import groovy.yaml.YamlSlurper
import org.apache.groovy.json.internal.LazyMap

import java.nio.file.Files
import java.nio.file.Path

class HarborManagerContainer extends DoodContainer {

    String harborVersion = "v2.6.0"
    String harborBaseUrl
    String basePath

    /**
     * HarborManagerContainer setups a container that runs the Harbor installation scripts, which in turn creates all the "real"
     * harbor containers
     * @param baseUrl The url where Harbor should be reachable on
     * @param harborVersion The version of harbor to install
     * @param baseDir The dir <b> On the Docker Engine </b> where harbor data and installation files will be kept.
     *          <br>This directory must already exist before creating this container
     *          <br>$baseDir/$harborHostname/data will contain harbor data ("data_volume")
     *          <br>$baseDir/$harborHostname/install will contain harbor data ("data_volume")
     *
     */
    HarborManagerContainer(String baseUrl, String harborVersion, String baseDir = "/opt/", String dockerHost = "", String dockerCertPath = "") {

        this.harborBaseUrl = baseUrl
        this.harborVersion = harborVersion
        this.containerName = host + "-manager"
        this.basePath =   baseDir[-1] == "/" ? baseDir + host : baseDir + "/"  + host

        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }

        prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock") // Mount docker socket
        prepareBindMount(baseDir , baseDir, false) //Mount data dir, data in this dir needs to be accessible by both engine and manager-container using the same path

    }


    String getInstallPath() {
        return basePath + "/install"
    }

    String getDataPath() {
        return basePath + "/data"
    }

    String getHost() {

        extractDomainFromUrl(harborBaseUrl)
    }

    String getPort(){
        extractPortFromUrl( harborBaseUrl)
    }




    @Override
    boolean runAfterDockerSetup() {

        log.info("Setting up Harbor")

        //Make sure basePath is empty or does not exist
        ArrayList<String> cmdOutput = runBashCommandInContainer("""ls "$basePath" | wc -l""", 5)
        assert cmdOutput == ["0"] || cmdOutput.any{it.startsWith("ls: cannot access")} : "Harbor base path is not empty: $basePath"



        cmdOutput = runBashCommandInContainer("apt install -y wget; echo status: \$?", 100)
        assert cmdOutput.last() == "status: 0": "Error installing harbor dependencies:" + cmdOutput.join("\n")
        log.info("\tFinished installing dependencies")


        cmdOutput = runBashCommandInContainer("""
            mkdir -p "${installPath}" && \\ 
            cd "${installPath}" && \\
            wget https://github.com/goharbor/harbor/releases/download/$harborVersion/harbor-online-installer-${harborVersion}.tgz &&  \\
            tar xzvf harbor-online-installer-${harborVersion}.tgz &&  \\
            cd harbor &&  \\
            ls -la &&  \\
            echo status: \$?
        """, 100)
        assert cmdOutput.last() == "status: 0": "Error downloading and extracting harbor:" + cmdOutput.join("\n")
        log.info("\tFinished downloading and extracting Harbor")


        /**
         * Fetch template config file, update it and put it back in the manager container
         */

        Path tmpDir= Files.createTempDirectory("harbor-conf")
        String tmpDirPath = tmpDir.toFile().absolutePath


        ArrayList<File> files = copyFilesFromContainer("${installPath}/harbor/harbor.yml.tmpl", tmpDirPath + "/")

        assert files.size() == 1 && files.first().name == "harbor.yml.tmpl" : "Error, could not find template config file"
        File yamlFile = files.first()


        LazyMap yaml = new YamlSlurper().parse(yamlFile) as LazyMap
        LazyMap modifiedYaml = modifyHarborYml(yaml)

        YamlBuilder yamlBuilder = new YamlBuilder()
        yamlBuilder(modifiedYaml)

        File modifiedYamlFile = new File(tmpDirPath + "/harbor.yml")
        modifiedYamlFile.createNewFile()
        modifiedYamlFile.write(yamlBuilder.toString().replaceAll("\"", ""))

        assert copyFileToContainer(modifiedYamlFile.absolutePath, installPath + "/harbor/") : "Error copying updated YAML file to container"
        tmpDir.deleteDir()

        log.info("\tFinished customizing installation configuration")

        log.info("\tStarting  installation")
        cmdOutput = runBashCommandInContainer(installPath + "/harbor/install.sh ; echo status: \$?", 400 )
        assert cmdOutput.last().contains("status: 0"): "Error installing harbor:" + cmdOutput.join("\n")



        return true

    }

    LazyMap modifyHarborYml(LazyMap originalYml) {

        LazyMap modifiedYml = originalYml

        modifiedYml.hostname = host
        modifiedYml.http.port = port
        modifiedYml.remove("https")
        modifiedYml.data_volume = dataPath


        return modifiedYml

    }
}
