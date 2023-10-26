package com.eficode.devstack.container.impl


import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.apache.groovy.json.internal.LazyMap

import java.nio.file.Files
import java.nio.file.Path
import java.util.Map.Entry

class HarborManagerContainer extends DoodContainer {

    String harborVersion = "v2.6.0"
    String harborBaseUrl
    String basePath

    ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory())

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
        this.basePath = baseDir[-1] == "/" ? baseDir + host : baseDir + "/" + host

        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }

        prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock") // Mount docker socket
        prepareBindMount(baseDir, baseDir, false) //Mount data dir, data in this dir needs to be accessible by both engine and manager-container using the same path

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

    String getPort() {
        extractPortFromUrl(harborBaseUrl)
    }


    @Override
    boolean runAfterDockerSetup() {

        log.info("Setting up Harbor")

        //Make sure basePath is empty or does not exist
        ArrayList<String> cmdOutput = runBashCommandInContainer("""ls "$basePath" | wc -l""", 5)
        assert cmdOutput == ["0"] || cmdOutput.any { it.startsWith("ls: cannot access") }: "Harbor base path is not empty: $basePath"


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
        """, 200)
        assert cmdOutput.last() == "status: 0": "Error downloading and extracting harbor:" + cmdOutput.join("\n")
        log.info("\tFinished downloading and extracting Harbor")


        assert modifyInstallYml(): "Error updating Harbor install config file: harbor.yml"

        log.info("\tStarting  installation")
        cmdOutput = runBashCommandInContainer(installPath + "/harbor/install.sh ; echo status: \$?", 400)
        if (!cmdOutput.last().contains("status: 0")) {
            log.warn("\tThere where problems during setup of harbor, potentially because dockerCompose has yet to mo modified, will modify and try again")
        }

        cmdOutput = runBashCommandInContainer("cd " + installPath + "/harbor && docker-compose stop && echo status: \$?", 80)
        assert cmdOutput.last().contains("status: 0"): "Error stopping harbor before modifying docker-compose file:" + cmdOutput.join("\n")

        assert modifyDockerCompose(): "Error modifying Harbors docker-compose file"


        sleep(5000)
        cmdOutput = runBashCommandInContainer("cd " + installPath + "/harbor && docker-compose up -d && echo status: \$?", 80)
        if (cmdOutput.last() != "status: 0" || cmdOutput.toString().contains("error")) {
            log.warn("\tThere was an error starting harbor after docker compose modification, this is common and a second attempt will be made")
            sleep(5000)
            cmdOutput = runBashCommandInContainer("cd " + installPath + "/harbor && docker-compose up -d && echo status: \$?", 120)
        }
        assert cmdOutput.last().contains("status: 0"): "Error applying the modified docker-compose file:" + cmdOutput.join("\n")

        return true

    }


    /**
     * Modifies the default docker-compose file so that all pods connect to the correct network (containerDefaultNetworks)
     * @return
     */
    boolean modifyDockerCompose() {

        log.info("\tCustomizing Harbor docker compose")
        Path tmpDir = Files.createTempDirectory("harbor-compose")
        String tmpDirPath = tmpDir.toFile().absolutePath


        ArrayList<File> files = copyFilesFromContainer("${installPath}/harbor/docker-compose.yml", tmpDirPath + "/")

        assert files.size() == 1 && files.first().name == "docker-compose.yml": "Error, could not find docker-compose.yml file"
        File yamlFile = files.first()
        log.debug("\t\tRetried docker compose file from container:" + yamlFile.absolutePath)


        //LazyMap originalYml = new YamlSlurper().parse(yamlFile) as LazyMap
        //LazyMap modifiedYml = new YamlSlurper().parse(yamlFile) as LazyMap
        LazyMap originalYml = objectMapper.readValue(yamlFile, LazyMap.class)
        LazyMap modifiedYml = objectMapper.readValue(yamlFile, LazyMap.class)


        modifiedYml.services.each { Entry<String, LazyMap> service ->
            log.debug("\t" * 3 + "Customising Docker Service:" + service.key)

            service.value.networks = containerDefaultNetworks
            log.trace("\t" * 4 + "Set networks to:" + service.value.networks)
            log.trace("\t" * 4 + "Used to be:" + originalYml.services.get(service.key).networks)

        }

        log.debug("\t" * 3 + "Customising Docker Network")
        modifiedYml.remove("networks")

        Map<String, LazyMap> networks = [:]
        containerDefaultNetworks.each { networkName ->
            networks.put(networkName as String, ["external": true, "name": networkName] as LazyMap)
        }
        modifiedYml.put("networks", networks)
        //modifiedYml.put("networks", ["default": [external: [name: networkName]]])

        log.trace("\t" * 4 + "Set networks to:" + modifiedYml.networks)
        log.trace("\t" * 4 + "Used to be:" + originalYml.networks)


        //Change the user of log container to root https://github.com/goharbor/harbor/issues/16669
        (modifiedYml.services.find { Entry<String, LazyMap> service -> service.key == "log" } as Entry<String, Map>).value.put("user", "root")

        //YamlBuilder yamlBuilder = new YamlBuilder()
        //yamlBuilder(modifiedYml)

        //yamlFile.write(yamlBuilder.toString())
        yamlFile.write(  objectMapper.writeValueAsString(modifiedYml))

        assert copyFileToContainer(yamlFile.absolutePath, installPath + "/harbor/"): "Error copying updated YAML file to container"
        tmpDir.deleteDir()

        log.info("\tFinished customizing docker-compose file")

        return true

    }

    /**
     * Fetch template config file, update it and put it back in the manager container
     */
    boolean modifyInstallYml() {


        Path tmpDir = Files.createTempDirectory("harbor-conf")
        String tmpDirPath = tmpDir.toFile().absolutePath


        ArrayList<File> files = copyFilesFromContainer("${installPath}/harbor/harbor.yml.tmpl", tmpDirPath + "/")

        assert files.size() == 1 && files.first().name == "harbor.yml.tmpl": "Error, could not find template config file"
        File yamlFile = files.first()


        //LazyMap originalYml = new YamlSlurper().parse(yamlFile) as LazyMap
        LazyMap originalYml = objectMapper.readValue(yamlFile, LazyMap.class)



        LazyMap modifiedYml = originalYml
        modifiedYml.hostname = host
        modifiedYml.http.port = port
        modifiedYml.remove("https")
        modifiedYml.data_volume = dataPath



        //YamlBuilder yamlBuilder = new YamlBuilder()
        //yamlBuilder(modifiedYml)

        File modifiedYamlFile = new File(tmpDirPath + "/harbor.yml")
        modifiedYamlFile.createNewFile()
        //modifiedYamlFile.write(yamlBuilder.toString().replaceAll("\"", ""))
        modifiedYamlFile.write(objectMapper.writeValueAsString(originalYml).replaceAll("\"", ""))

        assert copyFileToContainer(modifiedYamlFile.absolutePath, installPath + "/harbor/"): "Error copying updated YAML file to container"
        tmpDir.deleteDir()

        log.info("\tFinished customizing installation configuration")

        return true

    }
}
