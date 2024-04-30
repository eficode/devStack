package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.util.DirectorySyncer
import de.gesellix.docker.remote.api.ContainerInspectResponse
import de.gesellix.docker.remote.api.MountPoint
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
        DirectorySyncer syncer = DirectorySyncer.createSyncToVolume([srcDir1.canonicalPath, srcDir2.canonicalPath], uniqueVolumeName, dockerRemoteHost, dockerCertPath )
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
