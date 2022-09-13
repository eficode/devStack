package com.eficode.devstack.container

import com.eficode.devstack.container.impl.NginxContainer
import groovy.io.FileType
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.HiddenFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import org.eclipse.jgit.lib.Repository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

class ContainerTest extends Specification {

    static Logger log = LoggerFactory.getLogger(ContainerTest.class)

    String dockerHost = "https://docker.domain.se:2376"
    String dockerCertPath = "./resources/dockerCert"


    class ContainerImpl implements Container {

        String containerName = "spoc"
        String containerMainPort = "666"

        String createContainer() {}
    }



    def testPing() {

        setup:
        ContainerImpl container = new ContainerImpl()
        container.setupSecureRemoteConnection(dockerHost, dockerCertPath)

        expect:
        container.ping()

    }

    def "Test clone repository" () {

        setup:
        File gitOutDir = File.createTempDir("gitOut")
        String sampleRepoHttpUrl = "https://github.com/eficode/devStack.git"
        log.info("Testing cloning of repo to temp dir")
        log.info("\tRepo url:" + sampleRepoHttpUrl)
        log.info("\tTemp dir:" + gitOutDir)

        when: "Checking out using http to empty dir"

        NginxContainer.cloneRepository(sampleRepoHttpUrl, gitOutDir.absolutePath)
        log.info("Got repo:")
        log.info("\tSize: " + FileUtils.byteCountToDisplaySize(gitOutDir.size()))
        log.info("\tSub files and folders:" + FileUtils.listFiles(gitOutDir,  HiddenFileFilter.VISIBLE, DirectoryFileFilter.DIRECTORY).size())


        then: "Several bytes and files should be found in the out dir"
        gitOutDir.size() > 100
        FileUtils.listFiles(gitOutDir,  HiddenFileFilter.VISIBLE, DirectoryFileFilter.DIRECTORY).size() > 10

        when: "Checking out to a directory with content"
        NginxContainer.cloneRepository(sampleRepoHttpUrl, gitOutDir.absolutePath)

        then: "Exception should be thrown"
        thrown(FileAlreadyExistsException)


        cleanup:
        assert gitOutDir.deleteDir() : "Error deleting gitOut dir:" + gitOutDir.absolutePath


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
