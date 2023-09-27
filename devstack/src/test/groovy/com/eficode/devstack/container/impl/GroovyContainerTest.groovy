package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.deployment.impl.GroovyBuilderDeployment
import com.eficode.devstack.deployment.impl.NginxFileServer
import kong.unirest.Unirest
import org.slf4j.LoggerFactory

class GroovyContainerTest extends DevStackSpec{

    def setupSpec() {


        DevStackSpec.log = LoggerFactory.getLogger(DoodContainerTest.class)

        cleanupContainerNames = ["groovyBuilder"]
        cleanupContainerPorts = []

        disableCleanup = true

    }


    String getProjectRootPath() {

        String root = System.getenv("projectRoot")
        assert root != "" : "Error determining project root, did you run using the Run Config?"
        return root

    }


    def "Test build DevStack"() {


        setup:
        //GroovyBuilderDeployment groovyBuild = new GroovyBuilderDeployment("3.0.14")
        //NginxContainer nginxContainer = new NginxContainer()
        //nginxContainer.prepareVolumeMount(groovyBuild.m2VolumeName, "/usr/share/nginx/html", true)
        NginxFileServer fileServer = GroovyBuilderDeployment.buildAndHost(projectRootPath)
        //groovyBuild.groovyContainer.setUseLocalM2Cache()



        expect:
        Unirest.get(fileServer.container.containerName + ":" + fileServer.container.containerMainPort)
        //groovyBuild.groovyContainer.isCreated() ?:  groovyBuild.setupDeployment()
        //groovyBuild.buildLocalSources(projectRootPath)
        //nginxContainer.createContainer()
        //groovyBuild.hostM2Volume("repo.localhost", "8081", "jsm123")



    }


}
