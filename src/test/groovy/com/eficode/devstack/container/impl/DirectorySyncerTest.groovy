package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.util.DirectorySyncer
import com.eficode.devstack.util.ImageSummaryDS
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.MountPoint
import de.gesellix.docker.remote.api.Volume
import org.slf4j.LoggerFactory


class DirectorySyncerTest extends DevStackSpec {


    def setupSpec() {

        DevStackSpec.log = LoggerFactory.getLogger(this.class)

        cleanupContainerNames = ["DirectorySyncer", "DirectorySyncer1", "DirectorySyncer2", "DirectorySyncer-companion", "DirectorySyncer1-companion"]
        cleanupContainerPorts = []

        disableCleanup = false


    }


    Boolean volumeExists(String volumeName) {

        Boolean result = dockerClient.volumes().content?.volumes?.any { it.name == volumeName }
        return result

    }

    def "Test create duplicate Syncer"() {

        log.info("Testing that createSyncToVolume detects a duplicate container and returns it in stead of creating a new one")
        File srcDir1 = File.createTempDir("srcDir1")
        log.debug("\tCreated Engine local temp dir:" + srcDir1.canonicalPath)
        File srcDir2 = File.createTempDir("srcDir2")
        log.debug("\tCreated Engine local temp dir:" + srcDir2.canonicalPath)

        String uniqueVolumeName = "syncVolume" + System.currentTimeMillis().toString().takeRight(3)
        !volumeExists(uniqueVolumeName) ?: dockerClient.rmVolume(uniqueVolumeName)
        log.debug("\tWill use sync to Docker volume:" + uniqueVolumeName)

        DirectorySyncer firstSyncer = DirectorySyncer.createSyncToVolume([srcDir1.canonicalPath, srcDir2.canonicalPath], uniqueVolumeName, "DirSyncer", "-avh --delete", dockerRemoteHost, dockerCertPath )
        log.info("\tCreated first sync container: ${firstSyncer.containerName} (${firstSyncer.shortId})")
        Integer containersAfterFirst = firstSyncer.dockerClient.ps(true).content.size()
        log.info("\t\tDocker engine now has a total of ${containersAfterFirst} contianers")

        when: "Creating second sync container"
        DirectorySyncer secondSyncer = DirectorySyncer.createSyncToVolume([srcDir1.canonicalPath, srcDir2.canonicalPath], uniqueVolumeName, "DirSyncer","-avh --delete", dockerRemoteHost, dockerCertPath )
        log.info("\tCreated second sync container: ${secondSyncer.containerName} (${secondSyncer.shortId})")

        then: "They should have the same ID"
        assert firstSyncer.id == secondSyncer.id : "The second container doesnt have the same id"
        assert containersAfterFirst == secondSyncer.dockerClient.ps(true).content.size() : "The number of containers changed after creating the second one"
        assert secondSyncer.running
        log.info("\tA duplicate container was not created, instead the first one was returned")

        when: "Stopping the sync container, and creating another duplicate"
        firstSyncer.stopContainer()
        assert !firstSyncer.running
        secondSyncer = DirectorySyncer.createSyncToVolume([srcDir1.canonicalPath, srcDir2.canonicalPath], uniqueVolumeName, "DirSyncer","-avh --delete", dockerRemoteHost, dockerCertPath )

        then:"The duplicate should have been automatically started"
        secondSyncer.running


        cleanup:
        assert srcDir1.deleteDir()
        assert srcDir2.deleteDir()
        assert firstSyncer.stopAndRemoveContainer()
        dockerClient.rmVolume(uniqueVolumeName)



    }


    def "Test create createSyncVolumeToVolume"() {

        setup:
        log.info("Testing createSyncVolumeToVolume")
        Volume srcVolume = dockerClient.getOrCreateVolume("srcVolume" + System.nanoTime().toString().takeRight(3))
        Volume destVolume = dockerClient.getOrCreateVolume("destVolume" + System.nanoTime().toString().takeRight(3))
        log.info("\tWill use src volume:" + srcVolume)
        log.info("\tWill use dest volume:" + destVolume)


        when: "Creating two containers with two different default users"
        UbuntuContainer srcContainer = new UbuntuContainer()
        srcContainer.containerName = "SrcContainer"
        srcContainer.prepareVolumeMount(srcVolume.name, "/mnt/volume", false)
        srcContainer.user = "1001:1001"
        srcContainer.createSleepyContainer()
        srcContainer.startContainer()
        srcContainer.runBashCommandInContainer(ImageSummaryDS.getReplaceUserScriptBody("ubuntu", "1000", "ubuntu", "1000", "ubuntusrc", "1001", "ubuntusrc", "1001"),10, "root" )
        srcContainer.runBashCommandInContainer("chown 1001:1001 -R /mnt/volume", 5, "root")


        UbuntuContainer destContainer = new UbuntuContainer()
        destContainer.containerName = "DestContainer"
        destContainer.prepareVolumeMount(destVolume.name, "/mnt/volume", false)
        destContainer.user = "1002:1002"
        destContainer.createSleepyContainer()
        destContainer.startContainer()
        destContainer.runBashCommandInContainer(ImageSummaryDS.getReplaceUserScriptBody("ubuntu", "1000", "ubuntu", "1000", "ubuntudest", "1002", "ubuntudest", "1002"),10, "root" )
        destContainer.runBashCommandInContainer("chown 1002:1002 -R /mnt/volume", 5, "root")

        then: "When checking user id, they should be different"
        srcContainer.runBashCommandInContainer("id -u").contains("1001")
        destContainer.runBashCommandInContainer("id -u").contains("1002")


        when: "Creating the syncer"

        DirectorySyncer syncer = DirectorySyncer.syncBetweenVolumesAndUsers(srcVolume.name, destVolume.name, "1002:1002")
        srcContainer.runBashCommandInContainer("touch /mnt/volume/createdInSource")
        sleep(1000)
        then:
        destContainer.runBashCommandInContainer("ls -l /mnt/volume/createdInSource && echo Status: \$?").contains("Status: 0")
        destContainer.runBashCommandInContainer("echo edited >> /mnt/volume/createdInSource && echo Status: \$?").contains("Status: 0")
        destContainer.runBashCommandInContainer("rm /mnt/volume/createdInSource && echo Status: \$?").contains("Status: 0")


        cleanup:
        srcContainer.stopAndRemoveContainer()
        destContainer.stopAndRemoveContainer()
        syncer.stopAndRemoveContainer()
        dockerClient.manageVolume.rmVolume(srcVolume.name)
        dockerClient.manageVolume.rmVolume(destVolume.name)

    }

    def "Test createSyncToVolume"() {

        setup:
        log.info("Testing createSyncToVolume")
        File srcDir1 = File.createTempDir("srcDir1")
        log.debug("\tCreated Engine local temp dir:" + srcDir1.canonicalPath)
        File srcDir2 = File.createTempDir("srcDir2")
        log.debug("\tCreated Engine local temp dir:" + srcDir2.canonicalPath)

        String uniqueVolumeName = "syncVolume" + System.currentTimeMillis().toString().takeRight(3)
        !volumeExists(uniqueVolumeName) ?: dockerClient.rmVolume(uniqueVolumeName)
        log.debug("\tWill use sync to Docker volume:" + uniqueVolumeName)


        when: "When creating syncer"

        assert !volumeExists(uniqueVolumeName): "Destination volume already exists"
        DirectorySyncer syncer = DirectorySyncer.createSyncToVolume([srcDir1.canonicalPath, srcDir2.canonicalPath], uniqueVolumeName, "DirSyncer", "-avh --delete", dockerRemoteHost, dockerCertPath )
        log.info("\tCreated sync container: ${syncer.containerName} (${syncer.shortId})")
        ContainerInspectResponse containerInspect = syncer.inspectContainer()


        then: "I should have the two bind mounts and one volume mount"
        assert syncer.running: "Syncer container is not running"
        log.debug("\tContainer is running")
        assert containerInspect.mounts.any { it.destination == "/mnt/src/${srcDir1.name}".toString() && it.RW == false }
        log.debug("\tContainer has mounted the first src dir")
        assert containerInspect.mounts.any { it.destination == "/mnt/src/${srcDir2.name}".toString() && it.RW == false }
        log.debug("\tContainer has mounted the second src dir")
        assert containerInspect.mounts.any { it.type == MountPoint.Type.Volume && it.RW }
        assert dockerClient.getVolumesWithName(uniqueVolumeName).size(): "Destination volume was not created"
        log.debug("\tContainer has mounted the expected destination volume")

        when: "Creating files in src directories"
        File srcFile1 = File.createTempFile("srcFile1", "temp", srcDir1)
        srcFile1.text = System.currentTimeMillis()
        log.debug("\tCreated file \"${srcFile1.name}\" in first src dir")
        File srcFile2 = File.createTempFile("srcFile2", "temp", srcDir2)
        srcFile2.text = System.currentTimeMillis() + new Random().nextInt()
        log.debug("\tCreated file \"${srcFile2.name}\" in second src dir")

        then: "The sync container should see new source files, and sync them"
        syncer.runBashCommandInContainer("cat /mnt/src/${srcDir1.name}/${srcFile1.name}").toString().contains(srcFile1.text)
        syncer.runBashCommandInContainer("cat /mnt/src/${srcDir2.name}/${srcFile2.name}").toString().contains(srcFile2.text)
        log.debug("\tContainer sees the source files")
        sleep(2000)//Wait for sync
        syncer.runBashCommandInContainer("cat /mnt/dest/${srcFile1.name}").toString().contains(srcFile1.text)
        syncer.runBashCommandInContainer("cat /mnt/dest/${srcFile2.name}").toString().contains(srcFile2.text)
        log.debug("\tContainer successfully synced the files to destination dir")

        when: "Creating a recursive file"
        File recursiveFile = new File(srcDir1.canonicalPath + "/subDir/subFile.temp").createParentDirectories()
        recursiveFile.createNewFile()
        recursiveFile.text = System.nanoTime()
        log.info("\tCreate recursive file:" + recursiveFile.canonicalPath)

        then: "The sync container should see the new source file, and sync it to a new recursive dir"
        sleep(2000)
        syncer.runBashCommandInContainer("cat /mnt/dest/subDir/subFile.temp").toString().contains(recursiveFile.text)
        log.info("\t\tFile was successfully synced")


        /**
         inotify does not appear to successfully detect deletions
         Files will however be deleted once a create/update is detected
         */
        when: "Deleting first source file and updating second source file"
        assert srcFile1.delete(): "Error deleting source file:" + srcFile1.canonicalPath
        srcFile2.text = "UPDATED FILE"
        log.debug("\tUpdating AND deleting src files")

        then: "The file should be removed from destination dir"
        sleep(2000)
        !syncer.runBashCommandInContainer("cat /mnt/dest/${srcFile1.name} && echo Status: \$?").toString().containsIgnoreCase("Status: 0")
        syncer.runBashCommandInContainer("cat /mnt/dest/${srcFile2.name}").toString().containsIgnoreCase(srcFile2.text)
        log.debug("\t\tContainer successfully synced the changes")

        when:"Creating a new container and attaching it to the synced volume"
        AlpineContainer secondContainer = new AlpineContainer(dockerRemoteHost, dockerCertPath)
        secondContainer.containerName = syncer.containerName  + "-companion"
        secondContainer.prepareVolumeMount(uniqueVolumeName, "/mnt/syncDir", false)
        secondContainer.createSleepyContainer()


        then:"The second container should see the still remaining synced files"
        assert secondContainer.startContainer() : "Error creating/staring second container"
        log.info("\tCreated an started second container ${secondContainer.shortId}")
        assert secondContainer.mounts.any { it.type == MountPoint.Type.Volume && it.RW == true} : "Second container did not mount the shared volume"
        log.info("\tSecond container was attached to volume:" + uniqueVolumeName)
        log.info("\tChecking that second container can access synced file:" + " /mnt/syncDir/${srcFile2.name}" )
        assert secondContainer.runBashCommandInContainer("cat /mnt/syncDir/${srcFile2.name}").toString().containsIgnoreCase(srcFile2.text) : "Error reading synced file in second container:" + " /mnt/syncDir/${srcFile2.name}"

        log.info("\tChecking that second container can access recursive synced file")
        assert secondContainer.runBashCommandInContainer("cat /mnt/syncDir/subDir/subFile.temp").toString().contains(recursiveFile.text)
        log.info("\t\tContainer can access that file")
        cleanup:
        assert syncer.stopAndRemoveContainer()
        assert secondContainer?.stopAndRemoveContainer()
        srcDir1.deleteDir()
        srcDir2.deleteDir()
        dockerClient.rmVolume(containerInspect.mounts.find { it.type == MountPoint.Type.Volume }.name)

    }


}
