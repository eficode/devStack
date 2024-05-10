package com.eficode.devstack.util

import com.eficode.devstack.container.impl.AbstractContainer
import de.gesellix.docker.builder.BuildContextBuilder
import de.gesellix.docker.remote.api.ImageInspect
import de.gesellix.docker.remote.api.ImageSummary
import groovy.io.FileType
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.codehaus.groovy.runtime.ResourceGroovyMethods
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException

class ImageSummaryDS {


    Logger log = LoggerFactory.getLogger(ImageSummaryDS.class)
    String id
    String parentId
    int created
    long propertySize
    long sharedSize
    int containers
    List<String> repoTags
    List<String> repoDigests
    Long virtualSize
    Map<String, String> labels

    DockerClientDS dockerClient

    ImageSummaryDS(DockerClientDS dockerClient, ImageSummary imageSummary) {
        id = imageSummary.id
        parentId = imageSummary.parentId
        created = imageSummary.created
        propertySize = imageSummary.propertySize
        sharedSize = imageSummary.sharedSize
        containers = imageSummary.containers
        repoTags = imageSummary.repoTags
        repoDigests = imageSummary.repoDigests
        virtualSize = imageSummary.virtualSize
        labels = imageSummary.labels
        this.dockerClient = dockerClient
    }

    ImageInspect inspect() {
        return dockerClient.inspectImage(this.id).content
    }

    String getName() {
        return repoTags.find { true }?.split(":")?.first()
    }

    String getTag() {
        return repoTags.find { true }?.split(":")?.last()
    }

    String toString() {
        return "$name:$tag"
    }


    /**
     * Creates a container with this image with entrypoint and cmd, returns container console output on exit
     * @param entrypoint eg ["/bin/sh", "-c"]
     * @param cmd
     * @param timeoutS
     * @return
     */
    ArrayList<String> runAndExit(ArrayList<String> entrypoint, ArrayList<String> cmd, Integer timeoutS = 1 * 60000) {

        AbstractContainer container = new AbstractContainer(System.nanoTime().toString().takeRight(5), "", name, tag, "/bin/sh")
        container.createContainer(cmd, entrypoint)
        container.startContainer()

        long started = System.currentTimeMillis()
        while (container.running) {
            sleep(100)
            if (System.currentTimeMillis() - started > timeoutS) {
                container.stopAndRemoveContainer()
                throw new TimeoutException("Error waiting for container exit")
            }
        }

        ArrayList<String> out = container.containerLogs
        container.stopAndRemoveContainer()

        return out
    }

    /**
     * Gets the effective default user name
     * Starts a container using the image, runs a command in it with the default user to determine username
     * @return username if available, null if not
     */
    String getDefaultUserName() {


        ArrayList<String> out = runAndExit(["/bin/sh", "-c"], ["id -un"], 10000)

        if (out.toString().contains("cannot find name for user") || out.size() != 1) {
            return null
        }

        return out.first()
    }

    /**
     * Gets the effective default user id
     * Starts a container using the image, runs a command in it with the default user to determine username
     * @return id if available, null if not
     */
    String getDefaultUserId() {


        ArrayList<String> out = runAndExit(["/bin/sh", "-c"], ["id -u"], 10000)

        if (out.toString().contains("cannot find name for user") || out.size() != 1) {
            return null
        }

        return out.first()
    }


    /**
     * Attempts to allow you to inject/prepend a new startup script that will be executed before the
     * images default entrypoint/cmd
     * @param shellScriptBody The script body that should be executed
     * @param tag tag tag of the new image, defaults to $oldTag-$toUserName
     * @return the new ImageSummaryDS
     */
    ImageSummaryDS prependStartupScript(String shellScriptBody, String tag = "") {


        log.info("Prepending startup script of $this")
        ImageInspect srcInspect = inspect()

        String newTag = (tag == "" ? this.repoTags.first() + "-prepend" : tag)


        String imageUserId = getDefaultUserId()

        String oldEntrypoint = srcInspect.config.entrypoint?.join(" ") ?: "" // ?: "/bin/sh -c"
        log.debug("\tThe old entrypoint is: " + oldEntrypoint)

        String oldCmd = srcInspect.config.cmd?.collect { "\"$it\"" }?.join(" ")
        log.debug("\tThe old cmd is: " + oldCmd)

        String newScriptBody = shellScriptBody
        newScriptBody += """
        eval '${oldEntrypoint ? "$oldEntrypoint " : "" }${oldCmd ? "$oldCmd" : ""}'
        """.stripIndent()


        File tempDir = File.createTempDir("prependStartup")
        tempDir.deleteOnExit()

        File newStartupScript = new File(tempDir, "newEntry.sh")

        newStartupScript.text = newScriptBody


        String dockerFileBody = """FROM ${this.repoTags.first()}
        USER root
        ADD newEntry.sh /newEntry.sh
        RUN chmod +x /newEntry.sh
        RUN chown $imageUserId /newEntry.sh
        ENTRYPOINT ["/bin/sh" , "-c"]
        CMD ["/newEntry.sh"]
        USER $imageUserId
        """.stripIndent()

        File dockerFile = new File(tempDir, "Dockerfile")
        dockerFile.text = dockerFileBody

        ImageSummaryDS image = dockerClient.build(tempDir, newTag, 5)

        return image

    }


    /**
     * <pre>
     * Returns a script that is intended to replace a user and the users primary group id and name
     * The script it intended for ubuntu/debian and presumes the following to be available usermod, groupmod, chown
     * Known limitations:
     *  * Cant rename root
     *  * Volume mount owners arent changed
     * The Script:
     *  * Changes a groups name and id
     *  * Changes a user name, id and primary group
     *  * Moves the users current home to a dir corresponding ot the new username
     *  * Finds files owned by old user/group and changes to the new user/group
     * @param fromUserName the username to switch from
     * @param fromUid the uid to switch from
     * @param fromGroupName the group name to switch from
     * @param fromGid the the gid to switch from
     * @param toUserName the username to switch to
     * @param toUid the uid to switch to
     * @param toGroupName the group name to switch to
     * @param toGid the gid to switch the
     * @return the script body
     */
    static String getReplaceUserScriptBody(String fromUserName, String fromUid, String fromGroupName, String fromGid, String toUserName, String toUid, String toGroupName, String toGid) {

        /*
        # WIP - Doesnt work on alpine, chown is missing functionality
        #Identifies package manager and attempts to install dependencies
         APK_CMD=\$(which apk)
        APT_GET_CMD=\$(which apt-get)

        if [ ! -z \$APK_CMD ]; then
            apk add  coreutils passwd
            echo "stop"
         elif [ ! -z \$APT_GET_CMD ]; then
            apt update
            apt-get install -y coreutils passwd
            apt-get clean
            rm -rf /var/lib/apt/lists/*
         else
            echo "error can't identify package manager"
            exit 1;
         fi


         */

        return """
            eval NEW_HOME=~$fromUserName
            NEW_HOME=\${NEW_HOME%/*}/$toUserName
    
            echo Updating group $fromGroupName to $toGroupName $toGid
            groupmod -g $toGid -n $toGroupName $fromGroupName
            echo Updating user $fromUserName to $toUserName $toUid
            usermod -u $toUid -l $toUserName -g $toGid $fromUserName 
            echo Moving user home to \$NEW_HOME
            usermod --home \$NEW_HOME --move-home $toUserName
            #Ignoring return code from chown, as it will be != 0 because some files where skipped
            echo Changing file permissions from $fromUid:$fromGid to $toUserName:$toGroupName
            
            chown -R --from=$fromUid $toUserName /  || true
            chown -R --from=:$fromGid :$toGid /  || true
    
            echo Finished replacing user
        """.stripIndent()


    }


    /**
     * <pre>
     * This will build a new version of the image where a user and the users primary group, id and name have been altered
     * and files owned by the user/group updated accordingly
     * The method it intended for ubuntu/debian based images and presumes the following to be available usermod, groupmod, chown
     * Known limitations:
     *  * Cant rename root
     *  * Volume mount owners arent changed
     * The method:
     *  * Changes a groups name and id
     *  * Changes a user name, id and primary group
     *  * Moves the users current home to a dir corresponding ot the new username
     *  * Finds files owned by old user/group and changes to the new user/group
     *  * If the previous default docker user was $fromUserName, it will be replaced with $toUserName
     *  </pre>
     * @param fromUserName the username to switch from
     * @param fromUid the uid to switch from
     * @param fromGroupName the group name to switch from
     * @param fromGid the the gid to switch from
     * @param toUserName the username to switch to
     * @param toUid the uid to switch to
     * @param toGroupName the group name to switch to
     * @param toGid the gid to switch the
     * @return the script body
     * @param tag tag of the new image, defaults to $oldTag-$toUserName
     * @return ImageSummaryDS of the new image
     */
    ImageSummaryDS replaceDockerUser(String fromUserName, String fromUid, String fromGroupName, String fromGid, String toUserName, String toUid, String toGroupName, String toGid, String tag = "") {

        String newTag = (tag == "" ? this.repoTags.first() + "-" + toUserName : tag)

        File tempDir = File.createTempDir("dockerBuild")
        tempDir.deleteOnExit()

        File scriptFile = new File(tempDir, "replaceUser.sh")
        scriptFile.text = getReplaceUserScriptBody(fromUserName, fromUid, fromGroupName, fromGid, toUserName, toUid, toGroupName, toGid)

        String dockerFileUser = inspect().config.user ?: "root"

        if (dockerFileUser == fromUserName) {
            dockerFileUser = toUserName
        }

        String dockerFileBody = """FROM ${this.repoTags.first()}
        USER root
        ADD replaceUser.sh /replaceUser.sh
        RUN chmod +x /replaceUser.sh
        RUN /replaceUser.sh
        RUN rm /replaceUser.sh
        USER $dockerFileUser
        """.stripIndent()

        File dockerFile = new File(tempDir, "Dockerfile")
        dockerFile.text = dockerFileBody


        ImageSummaryDS newImage = dockerClient.build(tempDir, newTag)

        return newImage


    }


}
