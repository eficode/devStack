package com.eficode.devstack.examples

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.container.impl.GroovyDoodContainer
import org.slf4j.LoggerFactory

class ExamplesTest extends DevStackSpec{
    File examplesDir = new File("examples").canonicalFile


    def setupSpec() {
        dockerRemoteHost = "https://docker.domain.se:2376"
        dockerCertPath = "~/.docker/"


        log = LoggerFactory.getLogger(ExamplesTest.class)

        cleanupContainerNames = ["Dood"]
        cleanupContainerPorts = [8080,7990]

        disableCleanup = false

    }

    def "Test JSM And Bitbucket Example"() {

        setup:
        File scriptFile = new File(examplesDir, "Basic JSM and Bitbukcet setup.groovy")
        assert scriptFile.exists()
        println(scriptFile.canonicalPath)
        GroovyDoodContainer groovyDood = new GroovyDoodContainer(dockerRemoteHost, dockerCertPath)
        groovyDood.useGroovy3()
        //groovyDood.stopAndRemoveContainer()
        //groovyDood.createSleepyContainer()
        //groovyDood.startContainer()



        when:
        ArrayList<String> scriptOutput = groovyDood.runScriptInContainer(scriptFile.text,"", 600, "root" )

        then:
        !scriptOutput.any {it.contains("unable to resolve class")}


        cleanup:
        //groovyDood.stopAndRemoveContainer()
        true
    }
}
