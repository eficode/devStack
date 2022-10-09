package com.eficode.devstack.container.impl

import groovy.yaml.YamlBuilder
import groovy.yaml.YamlSlurper
import org.apache.groovy.json.internal.LazyMap

import java.nio.file.Files
import java.nio.file.Path

class HarborManagerContainer extends DoodContainer {

    String harborVersion = "v2.6.0"
    String harborBaseUrl



    HarborManagerContainer(String baseUrl, String harborVersion) {

        this.harborBaseUrl = baseUrl
        this.harborVersion = harborVersion
        this.containerName = host + "-manager"
        prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock")
        prepareBindMount("/data/" , "/data/", false)

    }

    /**
     * Setup a secure connection to a remote docker
     * @param dockerHost ex: https://docker.domain.com:2376
     * @param dockerCertPath ex: src/test/resources/dockerCert
     */
    HarborManagerContainer(String baseUrl, String harborVersion, String dockerHost, String dockerCertPath) {

        assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        this.harborBaseUrl = baseUrl
        this.harborVersion = harborVersion
        this.containerName = host + "-manager"


        prepareBindMount("/var/run/docker.sock", "/var/run/docker.sock")
        prepareBindMount("/data/" , "/data/", false)
    }

    String getHost() {

        extractDomainFromUrl(harborBaseUrl)
    }

    String getPort(){
        extractPortFromUrl( harborBaseUrl)
    }



    @Override
    boolean runAfterDockerSetup() {


        String installPath = "/data/${host}/install"
        ArrayList<String> cmdOutput = runBashCommandInContainer("apt install -y wget; echo status: \$?", 100)
        assert cmdOutput.last() == "status: 0": "Error installing harbor dependencies:" + cmdOutput.join("\n")


        cmdOutput = runBashCommandInContainer("""
            rm -rf ${installPath}
            mkdir -p ${installPath} ; \\ 
            cd ${installPath} ; \\
            wget https://github.com/goharbor/harbor/releases/download/$harborVersion/harbor-online-installer-${harborVersion}.tgz ; \\
            tar xzvf harbor-online-installer-${harborVersion}.tgz ; \\
            cd harbor ; \\
            ls -la ; \\
            echo status: \$?
        """, 100)
        assert cmdOutput.last() == "status: 0": "Error downloading and extracting harbor:" + cmdOutput.join("\n")



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


        long start = System.currentTimeMillis()
        cmdOutput = runBashCommandInContainer(installPath + "/harbor/install.sh", 400 )

        log.info("DURATTION:" + (System.currentTimeMillis() - start))


        return true

    }

    LazyMap modifyHarborYml(LazyMap originalYml) {

        LazyMap modifiedYml = originalYml

        modifiedYml.hostname = host
        modifiedYml.http.port = port
        modifiedYml.remove("https")
        modifiedYml.data_volume = "/data/${host}/data"

        return modifiedYml

    }
}
