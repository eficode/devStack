package com.eficode.devstack.container.impl
import com.eficode.devstack.container.Container

import java.util.regex.Matcher
import java.util.regex.Pattern


class GroovyContainer implements Container{

    String containerName = "groovy-container"
    String containerMainPort = ""
    String containerImage = "groovy"
    String containerImageTag = "latest"

    boolean installMavenOnStartup
    boolean installGitOnStartup
    static final String mvnUrl = "https://dlcdn.apache.org/maven/maven-3/3.9.4/binaries/apache-maven-3.9.4-bin.tar.gz"
    static  final String mavenNameAndVersion = mvnUrl.substring(mvnUrl.lastIndexOf("/") + 1,mvnUrl.lastIndexOf("-bin.tar.gz")) //ex: apache-maven-3.9.0


    GroovyContainer(String dockerHost = "", String dockerCertPath = "") {
        if (dockerHost && dockerCertPath) {
            assert setupSecureRemoteConnection(dockerHost, dockerCertPath): "Error setting up secure remote docker connection"
        }
    }

    /**
     * Runs a groovy script in the container
     * @param scriptText Text of the script that should be run
     * @param options Optional options to pass to the groovy process: ex: -Dorg.slf4j.simpleLogger.defaultLogLevel=trace
     * @param arguments Optional arguments to pass to the script
     * @param timeOutS
     * @param containerUser What user to run the script as
     * @return An arraylist containing console output from the script
     */
    ArrayList<String> runScriptInContainer(String scriptText,String options ="",  String arguments = "", long timeOutS = 120, String containerUser = "groovy") {
        assert replaceFileInContainer(scriptText, "/home/groovy/userScript.groovy") : "Error uploading script to container $id"
        return runBashCommandInContainer("groovy $options /home/groovy/userScript.groovy $arguments 2>&1 | tee /var/log/userScript.log"  , timeOutS, containerUser)

    }

    /**
     * Creates a container that wont automatically terminate immediately
     * @return container id
     */
    String createSleepyContainer() {
        return createContainer([],["/bin/bash" ,"-c" ,"trap \"exit\" SIGINT SIGTERM && tail -F /var/log/*  /var/log/userScript.log"])
    }

    /**
     * Creates a container intended for building Groovy maven projects
     * @return Container id
     */
    String createGroovyBuildContainer() {


        prepareCustomEnvVar(["PATH=/opt/$mavenNameAndVersion/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".toString()])
        installMavenOnStartup = true
        installGitOnStartup = true
        return createContainer([],["/bin/bash" ,"-c" ,"trap \"exit\" SIGINT SIGTERM && tail -F /var/log/*  /var/log/userScript.log"])
    }

    void useGroovy3() {
        this.containerImageTag = "3.0-jdk11-jammy"
    }

    void setGroovyVersion(String version) {

        if (version == "latest") {
            this.containerImageTag = version
        }else {
            this.containerImageTag = "$version-jdk11-jammy"
        }

    }


    /**
     * Used to mount the local m2 cache, usefull for speeding up maven dependency fetghing
     * Will mount container path /home/groovy/.m2/repository
     * <b>NOTE</b> will mount readOnly = false
     * @param srcPath, defaults to $USERHOME/.m2/repository
     */
    void setUseLocalM2Cache(String srcPath = "") {

        String sourcePath = srcPath ?: System.getProperty("user.home") + "/.m2/repository"
        prepareBindMount(sourcePath, "/home/groovy/.m2/repository", false)
    }


    boolean installMaven() {
        String mvnInstallScript = ""+
                "wget -q $mvnUrl && " +
                "tar -xvf ${mvnUrl.substring(mvnUrl.lastIndexOf("/") + 1)} && " +
                "mv $mavenNameAndVersion /opt/ && echo Status:\$?"

        ArrayList<String> cmdOut = runBashCommandInContainer(mvnInstallScript, 60000, "root")
        assert cmdOut.contains("Status:0") : "Error installing maven"


        assert runBashCommandInContainer("mvn --version" ).any {it.contains("/opt/$mavenNameAndVersion")} : "Error running maven after install"
        assert runBashCommandInContainer("chown -R groovy:groovy /home/groovy && echo Status:\$?", 10000, "root").toString().contains("Status:0") : "Error changing groovy home owner"

        return true
    }

    boolean installGit() {

        String installGitScript = "" +
                "apt update && " +
                "apt install -y git && echo Status:\$?"

        assert runBashCommandInContainer(installGitScript, 30000, "root").toString().contains("Status:0") : "Error install git"

        assert runBashCommandInContainer("git --version && echo Status:\$?").toString().contains("Status:0") : "Error confirming git was installed"

        return true

    }

    boolean isGitInstalled() {
        return runBashCommandInContainer("which git && echo Status:\$?").toString().contains("Status:0")
    }

    /**
     * Checks out a git repo
     * @param gitUrl SSH or HTTP url to check out
     * @param branch (optional) The branch to check out, if null the default one will be used
     * @return The containers filepath to the checked out repo
     */
    String cloneGitRepo(String gitUrl, String branch=null) {

        log.info("Cloning Git Repo")
        log.info("\tCloning Git Repo:" + gitUrl)
        log.info("\tUsing Git Branch:" + (branch ? branch : "(default)"))


        if (!gitInstalled) {
            log.info("\tGit is not installed, installing it now")
            assert installGit() : "Error installing git"
            log.debug("\t"*2 + "Finished installing git")
        }

        String repoName = gitUrl.substring(gitUrl.lastIndexOf("/")+1)
        repoName = repoName.endsWith(".git") ? repoName[0..-5]  : repoName

        String gitCloneCmd = "rm -rf $repoName && git clone ${branch ? "-b $branch " : ""}$gitUrl $repoName && echo Status:\$?"


        log.info("\tStarting git clone")
        log.debug("\t"*2 + "Using Git Cmd:" + gitCloneCmd)
        long start = System.currentTimeSeconds()
        ArrayList<String> cloneOutput = runBashCommandInContainer(gitCloneCmd, 120)
        assert cloneOutput.toString().contains("Status:0") : "Error cloning Git Repo:" + cloneOutput.join("\n")
        log.debug("\t"*2 + "Finished clone after:" + (System.currentTimeSeconds() - start))
        String outputDir =  runBashCommandInContainer("cd $repoName && pwd").find {true}
        assert outputDir : "Error determining git output dir"
        log.info("\t"*2 + "Checked out repo to:" + outputDir)

        return outputDir


    }

    /**
     * Resolve the depencies of a Maven project.
     * Usefull to pre-cache them
     * @param projectRoot Where is the project root
     * @param pomFile Name of the pom file to resolve dependencies for, located in $projectRoot, defaults to pom.xml
     * @return true on success
     */
    boolean resolveMavenDependencies(String projectRoot, String pomFile = "pom.xml") {

        log.info("Resolving Maven dependencies")
        log.info("\tProject root:" + projectRoot)

        log.debug("\t"*2 + "Starting dependency resolve")
        long start = System.currentTimeSeconds()
        ArrayList<String>resolveOut = runBashCommandInContainer("cd $projectRoot && mvn dependency:resolve -f $pomFile && echo Status:\$?", 1200)
        log.debug("\t" *2 + "Finished resolve after " + (System.currentTimeSeconds()- start))
        assert resolveOut.toString().contains("Status:0")

        log.info("\tFinished resolving dependencies")

        return true

    }

    /**
     * Generate a new effective pom based on the profiles in a source pom
     * @param projectRoot Root of the project where $srcPom is located
     * @param mavenProfile The profil/profiles to use, ex: groovy-3,groovy-3.0.14
     * @param dstPom The destination/output pom name
     * @param srcPom (Optional) The src pom, defaults to pom.xml
     * @return
     */
    boolean generateEffectivePom(String projectRoot, String mavenProfile, String dstPom = "effective-pom.xml", String srcPom = "pom.xml" ) {

        log.info("Generating effective pom")
        log.info("\tProject root:" + projectRoot)
        log.info("\tUsing maven profile:" + mavenProfile)
        log.info("\tSource Pom will be::" + srcPom)
        log.info("\tDestination Pom will be::" + dstPom)

        ArrayList<String>genOut = runBashCommandInContainer("cd $projectRoot && mvn help:effective-pom -f $srcPom -P $mavenProfile -Doutput=$dstPom && echo Status:\$?", 10)
        assert genOut.toString().contains("Status:0") : "Error generating effective pom:" + genOut.join("\n")

        return true

    }


    /**
     * Run "maven install" for a project
     * @param projectRoot Root of project
     * @param pom (Optional) The pom file in projectRoot to use, defaults to pom.xml
     * @param mavenParams Additional parameters to pass
     * @return An array containing the complete file paths of the installed files (in the container)
     */
    ArrayList<String> installMavenProject(String projectRoot, String pom = "pom.xml", String mavenParams = "") {

        log.info("Installing a maven project")
        log.info("\tProject root:" + projectRoot)

        ArrayList<String>installOut = runBashCommandInContainer("cd $projectRoot && mvn install -f $pom $mavenParams && echo Status:\$?", (5*60))

        if (log.traceEnabled) {
            log.trace("\tmvn install output:")
            installOut.each {log.trace("\t"*2 + it)}
        }
        assert installOut.toString().contains("Status:0") : "Error generating effective pom:" + installOut.join("\n")
        log.info("\tMaven installed finished")

        log.debug("\tCollecting paths of installed files")
        ArrayList<String>installedFiles = []
        Pattern installPattern = ~/${projectRoot.replace("/", "\\/")}.*? to (.*)$/
        installOut.findAll {it.contains( "Installing " + projectRoot)}.each {logRow ->

            log.trace("\t"*2+ "Log row contains installed file:" + logRow)

            Matcher matcher = logRow =~installPattern

            ArrayList<String> matches = matcher.findAll {true}.flatten()
            if (matches.size() == 2) {
                installedFiles.add(matches[1])
                log.trace("\t"*3 + "Extracted file path:" + matches[1])
            }else {
                log.warn("Error extracting installed file path from log:" + logRow)
            }

        }
        log.info("\tDetected ${installedFiles.size()} files installed by maven")
        log.info("\tFinished installing maven project")

        return installedFiles

    }


    void runSpockTest(String jarPath) {



        String testDir = "/tmp/" + jarPath.substring(jarPath.lastIndexOf("/") + 1) + "-spock"

        String extractScript = "" +
                "rm -rf $testDir && " +
                "mkdir -p $testDir && " +
                "cd $testDir && " +
                "jar xf $jarPath && " +
                "echo Status:\$?"

        assert runBashCommandInContainer(extractScript, 60 ).toString().contains("Status:0") : "Error extracting jar $jarPath"

        String resolvePomScript = "" +
                "cd $testDir &&" +
                "find META-INF/ -name pom.xml"

        ArrayList<String>out = runBashCommandInContainer(resolvePomScript)
        assert out.size()  == 1 : "Error resolving pom file in $testDir/META-INF/"
        String pomRelPath = out.first()

        String buildClasspathScript = "cd $testDir &&" +
                "mvn dependency:build-classpath -f $pomRelPath -Dmdep.outputFile=$testDir/classpath.txt && " +
                "CLASSPATH=\$(cat classpath.txt) && " +
                "echo CLASSPATH-OUT &&" +
                "echo \$CLASSPATH"
                //"cat classpath.txt"

        //"CLASSPATH=\$(cat classpath.txt) && export CLASSPATH"

        out = runBashCommandInContainer(buildClasspathScript, 120)
        assert out.find {it.contains("CLASSPATH-OUT")}

        out = runBashCommandInContainer("echo \$CLASSPATH")
        String classPath = out.get(out.findIndexOf {it.contains("CLASSPATH-OUT")} + 1)

        ""


    }

    @Override
    boolean runOnFirstStartup() {

        boolean success = true
        if (installMavenOnStartup) {
            success = success && installMaven()
        }

        if (installGitOnStartup) {
            success = success && installGit()
        }

        return success

    }

}
