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


        setup:
        String networkName = "spock-network"
        log.info("Testing CRUD of networks")
        AlpineContainer alpine = new AlpineContainer(dockerHost, dockerCertPath)
        alpine.containerName = "spock-alpine"
        alpine.stopAndRemoveContainer()
        //alpine.createContainer()

        log.info("\tCreated SPOCK container:" + alpine.id)

        Network spocNetwork = AlpineContainer.getBridgeNetwork()
        if (spocNetwork) {

            assert AlpineContainer.removeBridgeNetwork(spocNetwork.id) : "Error removing pre-existing SPOCK network"
            log.info("\tRemoved pre-existing spoc-network")
        }

        when:
        log.info("\tTesting removing network that should not exist")
        AlpineContainer.removeBridgeNetwork(networkName)
        then:
        AssertionError ex = thrown(AssertionError)
        ex.message.startsWith("Could not find")
        log.info("\t\tSuccess, error was thrown:" + ex.message)


        when:
        Network newNetwork = AlpineContainer.createBridgeNetwork(networkName)
        log.info("\tCreated spock network:" + newNetwork?.id)

        then:
        newNetwork != null



        //alpine.deleteBridgeNetwork("hejhej")
        //assert AlpineContainer.deleteBridgeNetwork("host") == null : "Error the spock network already exists"



    }

    def testCreateTar() {

        setup:
        File tarOutDir = File.createTempDir("tarOut")
        File tarSourceDir = File.createTempDir("tarSourceDir")
        File tarSourceSubDir = new File(tarSourceDir.path + "/subDir")
        assert tarSourceSubDir.mkdir()

        ArrayList<File> tarSourceRootFiles = []
        ArrayList<File> tarSourceSubFiles = []
        (0..9).each { i ->


            File newRootFile = new File(tarSourceDir.absolutePath +  "/tarRootFile${i}.txt")
            newRootFile.createNewFile()
            newRootFile.write("SPOC content for root file index: $i")
            tarSourceRootFiles.add(newRootFile)


            File newSubFile = new File(tarSourceSubDir.absolutePath +  "/tarSubFile${i}.txt")
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
        File tarFile = ContainerImpl.createTar([tarSourceDir.absolutePath],tarOutDir.absolutePath + "/tarFile.tar" )

        then:
        tarOutDir.exists()

        when:
        ArrayList<File> extractedFiles = ContainerImpl.extractTar(tarFile, tarOutDir.absolutePath + "/")
        ArrayList<File> allSourceFiles = tarSourceRootFiles + tarSourceSubFiles

        then:
        tarOutDir.eachFileRecurse(FileType.FILES) {extractedFile ->

            if (extractedFile.name != tarFile.name ) {
                File matchingSourceFile = allSourceFiles.find {it.name == extractedFile.name}

                assert matchingSourceFile : "Could not find matching source file, for file found in tar:" + extractedFile.name
                assert matchingSourceFile.text == extractedFile.text
                assert matchingSourceFile.relativePath(tarSourceDir)  == extractedFile.relativePath(tarOutDir)

            }

        }



        cleanup:
        tarSourceDir.deleteDir() ?: log.error("Error deleting temp files:" + tarSourceDir.absolutePath)
        tarOutDir.deleteDir() ?: log.error("Error deleting temp files:" + tarOutDir.absolutePath)

    }




}
