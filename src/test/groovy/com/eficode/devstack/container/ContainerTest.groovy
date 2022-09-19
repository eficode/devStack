package com.eficode.devstack.container

import com.eficode.devstack.container.impl.AlpineContainer
import de.gesellix.docker.remote.api.Network
import groovy.io.FileType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

class ContainerTest extends Specification {

    static Logger log = LoggerFactory.getLogger(ContainerTest.class)

    String dockerHost = "https://docker.domain.se:2376"
    String dockerCertPath = "./resources/dockerCert"


    class ContainerImpl implements Container {

        String containerName = "spoc"
        String containerMainPort = "666"

        String createContainer() {
            return ""
        }

        String createContainer(ArrayList<String> cmd, ArrayList<String> entrypoint) {

            return ""
        }

        @Override
        boolean runOnFirstStartup() {
            return true
        }
    }


    def testPing() {

        setup:
        ContainerImpl container = new ContainerImpl()
        container.setupSecureRemoteConnection(dockerHost, dockerCertPath)

        expect:
        container.ping()

    }


    def testNetworking() {

        //TODO continue this

        setup:
        String networkName = "spock-network"
        log.info("Testing CRUD of networks")

        AlpineContainer alpine1 = new AlpineContainer(dockerHost, dockerCertPath)
        alpine1.containerName = "spock-alpine1"

        if (alpine1.created) {
            assert alpine1.stopAndRemoveContainer(0)
        }
        alpine1.createSleepyContainer()

        AlpineContainer alpine2 = new AlpineContainer(dockerHost, dockerCertPath)
        alpine2.containerName = "spock-alpine2"
        if (alpine2.created) {
            assert alpine2.stopAndRemoveContainer(0)
        }
        alpine2.createSleepyContainer()


        log.info("\tCreated SPOCK container:" + alpine1.id)

        Network spockNetwork = alpine1.getBridgeNetwork(networkName)
        Network removedNetwork = alpine1.getBridgeNetwork(networkName + "-removed")
        if (spockNetwork) {

            assert alpine1.removeNetwork(spockNetwork): "Error removing pre-existing SPOCK network"
            log.info("\tRemoved pre-existing spoc-network")
        }

        if (removedNetwork) {
            assert alpine1.removeNetwork(removedNetwork): "Error removing pre-existing SPOCK network"
            log.info("\tRemoved pre-existing spoc-network")
        }


        when: "Creating two new networks, and deleting one of them"
        spockNetwork = alpine1.createBridgeNetwork(networkName)
        log.info("\tCreated spock network: ${spockNetwork.name} (${spockNetwork.id})")
        removedNetwork = alpine1.createBridgeNetwork(networkName + "-removed")
        log.info("\tCreated spock network: ${removedNetwork.name} (${removedNetwork.id})")
        alpine1.removeNetwork(removedNetwork)
        log.info("\tRemoved spock network:" + removedNetwork?.id)


        then: "Methods should be able to find the non-deleted network"

        spockNetwork != null
        removedNetwork != null

        alpine1.getBridgeNetwork(networkName)
        alpine1.getBridgeNetwork(spockNetwork.id)
        alpine1.getBridgeNetwork(networkName).name == networkName
        alpine1.getBridgeNetwork(networkName).id == spockNetwork.id
        alpine1.getBridgeNetwork(networkName).containers.isEmpty()
        alpine1.getBridgeNetwork(networkName).driver == "bridge"
        log.info("\tCreation of networks was tested successfully")


        alpine1.removeNetwork(removedNetwork)
        alpine1.getNetwork(removedNetwork.id) == null
        alpine1.getNetwork(removedNetwork.name) == null
        log.info("\tRemoval of networks was tested successfully")


        expect:
        alpine1.getContainerBridgeNetworks().size() == 1
        alpine1.connectContainerToNetwork(spockNetwork)
        alpine1.getContainerBridgeNetworks().size() == 2
        alpine1.getContainerBridgeNetworks().find { it.id == spockNetwork.id }

        when:
        alpine1.connectContainerToNetwork(removedNetwork) //The API doesn't verfy if a network is valid when connecting, this is done when container starts

        then:
        InputMismatchException ex = thrown(InputMismatchException)
        ex.message.contains("Network is not valid")

        when: "Setting the container network to a single network"
        alpine1.setContainerNetworks([spockNetwork])

        then: "The container default network should be disconnected and replace by the new network"
        alpine1.getContainerBridgeNetworks() == [spockNetwork]
        alpine1.getContainerBridgeNetworks().size() == 1


        when: "Setting the container network to a removed network"
        alpine1.setContainerNetworks([removedNetwork])

        then: "An error should be thrown"
        InputMismatchException ex2 = thrown(InputMismatchException)
        ex2.message.contains("Network is not valid")


        when: "Starting both containers in the same network"
        alpine1.setContainerNetworks([spockNetwork])
        alpine2.setContainerNetworks([spockNetwork])
        alpine1.startContainer()
        alpine2.startContainer()

        then: "They should both be able to ping each other using containerName and ip"
        alpine1.runBashCommandInContainer("ping -c 1 " + alpine2.containerName).any {it.contains("0% packet loss")}
        alpine2.runBashCommandInContainer("ping -c 1 " + alpine1.containerName).any {it.contains("0% packet loss")}
        alpine1.runBashCommandInContainer("ping -c 1 " + alpine2.ips.first()).any {it.contains("0% packet loss")}
        alpine2.runBashCommandInContainer("ping -c 1 " + alpine1.ips.first()).any {it.contains("0% packet loss")}
        alpine1.runBashCommandInContainer("ping -c 1 foobar").any {it.contains("bad address")}



    }

    def testCreateTar() {

        setup:
        File tarOutDir = File.createTempDir("tarOut")
        File tarSourceDir = File.createTempDir("tarSourceDir")
        File tarSourceSubDir = new File(tarSourceDir.path + "/subDir")
        AlpineContainer alpineContainer = new AlpineContainer()
        assert tarSourceSubDir.mkdir()

        ArrayList<File> tarSourceRootFiles = []
        ArrayList<File> tarSourceSubFiles = []
        (0..9).each { i ->


            File newRootFile = new File(tarSourceDir.absolutePath + "/tarRootFile${i}.txt")
            newRootFile.createNewFile()
            newRootFile.write("SPOC content for root file index: $i")
            tarSourceRootFiles.add(newRootFile)


            File newSubFile = new File(tarSourceSubDir.absolutePath + "/tarSubFile${i}.txt")
            newSubFile.createNewFile()
            newSubFile.write("SPOC content for sub file index: $i")
            tarSourceSubFiles.add(newSubFile)


        }

        log.info("\tCreated test files and directories:")
        log.info("\t\tTar root directory:" + tarSourceDir.absolutePath)
        log.info("\t\tTar sub directory:" + tarSourceSubDir.absolutePath)
        log.info("\t\tTar out directory:" + tarOutDir.absolutePath)
        log.info("\t\tTar root files:" + tarSourceRootFiles.name.join(","))
        log.info("\t\tTar sub files:" + tarSourceSubFiles.name.join(","))

        when:
        File tarFile = alpineContainer.createTar([tarSourceDir.absolutePath], tarOutDir.absolutePath + "/tarFile.tar")

        then:
        tarOutDir.exists()

        when:
        ArrayList<File> extractedFiles = alpineContainer.extractTar(tarFile, tarOutDir.absolutePath + "/")
        ArrayList<File> allSourceFiles = tarSourceRootFiles + tarSourceSubFiles

        then:
        tarOutDir.eachFileRecurse(FileType.FILES) { extractedFile ->

            if (extractedFile.name != tarFile.name) {
                File matchingSourceFile = allSourceFiles.find { it.name == extractedFile.name }

                assert matchingSourceFile: "Could not find matching source file, for file found in tar:" + extractedFile.name
                assert matchingSourceFile.text == extractedFile.text
                assert matchingSourceFile.relativePath(tarSourceDir) == extractedFile.relativePath(tarOutDir)

            }

        }


        cleanup:
        tarSourceDir.deleteDir() ?: log.error("Error deleting temp files:" + tarSourceDir.absolutePath)
        tarOutDir.deleteDir() ?: log.error("Error deleting temp files:" + tarOutDir.absolutePath)

    }


}
