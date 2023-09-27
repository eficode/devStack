package com.eficode.devstack.deployment.impl

import com.eficode.devstack.container.Container
import com.eficode.devstack.container.impl.GroovyContainer
import com.eficode.devstack.deployment.Deployment
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GroovyBuilderDeployment implements Deployment {

    Logger log = LoggerFactory.getLogger(this.class)
    String friendlyName = "Groovy Builder Deployment"
    ArrayList<Container> containers = []
    static String m2VolumeName = "groovy-m2-cache"
    String dockerHost
    String dockerCertPath


    GroovyBuilderDeployment(String groovyVersion = "latest", String dockerHost = "", String dockerCertPath = "") {


        containers = [new GroovyContainer(dockerHost, dockerCertPath)]
        this.dockerHost = dockerHost
        this.dockerCertPath = dockerCertPath
        groovyContainer.setGroovyVersion(groovyVersion)
        groovyContainer.setContainerName("groovyBuilder")
    }

    GroovyContainer getGroovyContainer() {
        return  containers.find {it instanceof GroovyContainer} as GroovyContainer
    }

    @Override
    boolean setupDeployment() {

        groovyContainer.containerDefaultNetworks = [this.deploymentNetworkName]
        groovyContainer.prepareVolumeMount(m2VolumeName, "/home/groovy/.m2/", false)
        groovyContainer.createGroovyBuildContainer()
        groovyContainer.startContainer()

    }


    static NginxFileServer buildAndHost(String localPath, String hostName = "repo.localhost", String port = "8081", String dockerNetwork = "bridge") {

        GroovyBuilderDeployment groovyBuild = new GroovyBuilderDeployment()
        groovyBuild.groovyContainer.containerName = "groovy-buildAndHost"
        groovyBuild.setupDeployment()
        groovyBuild.buildLocalSources(localPath)
        groovyBuild.stopAndRemoveDeployment()

        NginxFileServer fileServer = hostM2Volume(hostName, port, dockerNetwork, groovyBuild.m2VolumeName)

        return fileServer
    }


    /**
     * Creates an Nginx based file server
     * @param hostName Will be used as container name and hostname
     * @param port The port where nginx should listen
     * @param dockerNetwork The name of the docker network that the file server should be attached to
     * @param volumeName Name of the m2 docker volume to share
     */
    static NginxFileServer hostM2Volume(String hostName = "repo.localhost", String port = "8081", String dockerNetwork = "bridge", String volumeName = m2VolumeName) {

        NginxFileServer nginx = new NginxFileServer()
        nginx.setPort(port)
        nginx.container.containerName = hostName
        nginx.deploymentNetworkName = dockerNetwork
        nginx.container.prepareVolumeMount(volumeName, "/usr/share/nginx/html", true)
        nginx.setupDeployment()
        nginx.startDeployment()


        return nginx


    }


    /**
     * Builds docker engine local source files in a groovy container
     * @param localPath The local paths on the docker engine where sources are found, is expected to have a pom.xml file in the root
     * @param mavenProfiles Optional maven profile to build
     * @param ignorePaths Paths that should be ignored when copying files to the groovy container
     * @return A list of built files in the docker container
     */
    ArrayList<String> buildLocalSources(String localPath, String mavenProfiles = "", ArrayList<String> ignorePaths = [".*\\.git.*", ".*target/.*", ".*\\.terraform/.*"]) {

        String projectRoot = groovyContainer.getHomePath() + "/projectRoot/"

        log.info("Building JARs from local sources")


        groovyContainer.runBashCommandInContainer("rm -rf $projectRoot && mkdir -p $projectRoot")
        assert groovyContainer.copyFileToContainer(localPath, projectRoot, ignorePaths)
        groovyContainer.runBashCommandInContainer("chown groovy:groovy -R $projectRoot", 10, "root")


        log.info("\tFinished checking copying sources from local path:" + localPath)

        String pomFileName = "pom.xml"

        //If maven profiles are supplied, we generate a new effective pom file
        if (mavenProfiles) {
            assert groovyContainer.generateEffectivePom(projectRoot, mavenProfiles, "effective-pom.xml") : "Error generating effective pom"
            pomFileName = "effective-pom.xml"
            log.debug("\tGenerated a new pom file based on Maven Profile(s):" + mavenProfiles )
        }

        log.info("\tResolving dependencies for pom: " + projectRoot + "/"+pomFileName)
        groovyContainer.resolveMavenDependencies(projectRoot,pomFileName)
        log.info("\t"* 2 + "Finished resolving dependencies")


        ArrayList<String> installedFiles = groovyContainer.installMavenProject(projectRoot, pomFileName)

        log.info("\tBuilt ${installedFiles.size()} files")
        if (log.isDebugEnabled()) {
            installedFiles.each {
                log.debug("\t\t" + it)
            }
        }

        return installedFiles
    }





    void buildGroovyJarsFromGit(String gitUrl, String gitBranch = "", String mavenProfiles = "") {

        log.info("Building JARs from Git Repo")
        String projectRoot = groovyContainer.cloneGitRepo(gitUrl, gitBranch)
        log.info("\tFinished checking out git repo to container path:" + projectRoot)

        String pomFileName = "pom.xml"

        //If maven profiles are supplied, we generate a new effective pom file
        if (mavenProfiles) {
            assert groovyContainer.generateEffectivePom(projectRoot, mavenProfiles, "effective-pom.xml") : "Error generating effective pom"
            pomFileName = "effective-pom.xml"
            log.debug("\tGenerated a new pom file based on Maven Profile(s):" + mavenProfiles )
        }

        log.info("\tResolving dependencies for pom: " + projectRoot + "/"+pomFileName)
        groovyContainer.resolveMavenDependencies(projectRoot,pomFileName)
        log.info("\t"* 2 + "Finished resolving dependencies")


        ArrayList<String> installedFiles = groovyContainer.installMavenProject(projectRoot, pomFileName)




        ""



    }


}
