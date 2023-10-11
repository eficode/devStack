package com.eficode.devstack.container.impl

import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.container.Container
import com.eficode.devstack.deployment.impl.JsmH2Deployment
import com.eficode.devstack.util.TimeMachine
import org.slf4j.LoggerFactory

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset


class TimeMachineSpec extends DevStackSpec {


    long okTimeDiffS = 3 //The nr of seconds that considered OK for time to diff in most circumstances during testing
    ZoneOffset defaultZoneOffset = ZoneId.systemDefault().offset


    def setupSpec() {

        DevStackSpec.log = LoggerFactory.getLogger(this.class)

        cleanupContainerNames = []
        cleanupContainerPorts = []

        disableCleanup = true


    }

    //Run after every test
    @Override
    def cleanup() {
        if (!disableCleanup) {
            cleanupContainers()
            cleanupNetworks()
        }

    }

    boolean restoreDockerEngineTime() {

        long realTime = TimeMachine.getExternalTime()
        long dockerTime = TimeMachine.getDockerTime(dockerRemoteHost, dockerCertPath)

        long timeDiff = (realTime - dockerTime).abs()

        if (timeDiff > okTimeDiffS) {
            log.info("Restoring real time in docker engine, time diffs $timeDiff seconds")
            assert TimeMachine.setTime(realTime, dockerRemoteHost, dockerCertPath): "Error setting new docker time"
            log.info("\tSuccessfully restored time in Docker Engine")
        } else {
            log.info("The time diff between docker engine and reality is acceptable:" + timeDiff)
        }

        return true
    }


    long getEpochS(LocalDate localDate) {
        return localDate.toEpochSecond(LocalTime.of(0, 0), defaultZoneOffset)
    }

    long getEpochS(LocalDateTime localDateTime) {
        localDateTime.toEpochSecond(defaultZoneOffset)
    }

    long getContainerTime(Container container) {

        ArrayList<String> cmdOut = container.runBashCommandInContainer('date +"%s"', 10)
        long timeStamp = cmdOut.find { it.isNumber() }?.toLong() ?: 0
        assert timeStamp: "Unexpected output when getting container time"

        return timeStamp
    }

    def "Test comparing docker time with external and local"() {

        setup:
        log.info("Testing comparing time between docker container and external time, and local machine time")
        assert restoreDockerEngineTime(): "Error restoring docker engine time"
        log.info("\tSuccessfully restored time in docker engine")


        long dockerTime = TimeMachine.getDockerTime(dockerRemoteHost, dockerCertPath)
        long realTime = TimeMachine.getExternalTime()
        long localTIme = new Date().toInstant().epochSecond

        log.debug("\tGot Timestamps:")
        log.debug("\t" * 2 + "Time in docker engine:\t" + dockerTime)
        log.debug("\t" * 2 + "Time according to external source:\t" + realTime)
        log.debug("\t" * 2 + "Time according to local machine:\t" + localTIme)


        long dockerToExternalDiff = (realTime - dockerTime).abs()
        long dockerToLocalDiff = (localTIme - dockerTime).abs()
        log.info("\tTime diff between docker and reality is: " + dockerToExternalDiff + "s")
        log.info("\tTime diff between docker and local machine is: " + dockerToLocalDiff + "s")


        expect:
        assert dockerToExternalDiff <= okTimeDiffS: "Docker Time and external time appears to be different after restoring it"
        log.info("\tDocker time and external time are the same ")
        assert dockerToLocalDiff <= okTimeDiffS: "Docker Time and local time appears to be different after restoring it"
        log.info("\tDocker time and local machine time are the same ")

    }

    def "Verify companion Ubuntu containers created before and after time travel are affected by the changes"() {

        setup:
        assert restoreDockerEngineTime(): "Error restoring docker engine time"
        UbuntuContainer timeTraveler = new UbuntuContainer(dockerRemoteHost, dockerCertPath)
        timeTraveler.containerName = "TimeTraveler"
        timeTraveler.createSleepyContainer()
        assert timeTraveler.startContainer(): "Error starting time traveler container"
        assert (getContainerTime(timeTraveler) - TimeMachine.getExternalTime()).abs() <= okTimeDiffS
        log.info("\tTime in the TimeTraveler container was realTime before test")

        when: "Traveling forward in time, after having an already running companion container"
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1)
        long tomorrowS = getEpochS(tomorrow)
        log.info("\tWill travel to tomorrow:" + tomorrow + " ($tomorrowS)")
        assert TimeMachine.setLocalDateTime(tomorrow, dockerRemoteHost, dockerCertPath): "TimeMachine container errord when traveling to $tomorrow"
        log.info("\tTime as updated using the TimeMachine")


        then: "Time in the companion should be updated after the change"
        (getContainerTime(timeTraveler) - tomorrowS).abs() <= okTimeDiffS
        log.info("\tTime travel was reflected in the already running companion container")

        when: "When starting a new companion container, after having travelled forward in time"
        timeTraveler.stopAndRemoveContainer()
        timeTraveler = new UbuntuContainer(dockerRemoteHost, dockerCertPath)
        timeTraveler.containerName = "TimeTraveler"
        timeTraveler.createSleepyContainer()
        assert timeTraveler.startContainer(): "Error starting time traveler container"

        then: "The time in the new container should also be in the future"
        assert (getContainerTime(timeTraveler) - tomorrowS).abs() <= (okTimeDiffS * 5)


        cleanup:
        timeTraveler.stopAndRemoveContainer()


    }

    JsmH2Deployment setupJsm() {

        String jsmLicense = new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license").text
        String srLicense = new File(System.getProperty("user.home") + "/.licenses/jira/sr.license").text
        assert jsmLicense: "Error finding JSM license"
        assert srLicense: "Error finding script runner license"

        JsmH2Deployment timeTraveler = new JsmH2Deployment("http://jsmtime.localhost:8060", dockerRemoteHost, dockerCertPath)
        timeTraveler.setJiraLicense(jsmLicense)
        timeTraveler.stopAndRemoveDeployment()
        timeTraveler.setupDeployment()
        assert timeTraveler.jiraRest.installScriptRunner(srLicense, "latest"): "Error installing ScriptRunner in JSM"
        log.info("\tScriptRunner was installed")

        return timeTraveler
    }

    long getJsmGroovyTime(JsmH2Deployment jsmDeploy) {

        Map rawOut = jsmDeploy.jiraRest.executeLocalScriptFile("log.warn(\"EPOCH:\" + new Date().toInstant().epochSecond)")

        assert rawOut.success == true : "There was an error querying for GroovyTime from JSM ScriptRunner"
        assert (rawOut.log as ArrayList<String>).size() == 1

        String rawLogStatement = (rawOut.log as ArrayList<String>).get(0)
        long epochS = rawLogStatement.substring(rawLogStatement.lastIndexOf(":") + 1).toLong()

        return epochS

    }

    def "Verify companion JSM containers created before and after time travel are affected by the changes"() {

        setup:
        assert restoreDockerEngineTime(): "Error restoring docker engine time"


        JsmH2Deployment timeTraveler = setupJsm()
        assert timeTraveler.jsmContainer.running: "Error starting time traveler container"
        log.info("\tJSM has been setup")
        assert (getContainerTime(timeTraveler.jsmContainer) - TimeMachine.getExternalTime()).abs() <= okTimeDiffS
        log.info("\tTime in the TimeTraveler container was realTime before test")

        when: "Traveling forward in time, after having an already running companion container"
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1)
        long tomorrowS = getEpochS(tomorrow)
        log.info("\tWill travel to tomorrow:" + tomorrow + " ($tomorrowS)")
        assert TimeMachine.setLocalDateTime(tomorrow, dockerRemoteHost, dockerCertPath): "TimeMachine container errord when traveling to $tomorrow"
        log.info("\tTime was updated using the TimeMachine")


        then: "Time in the companion should be updated after the change"
        (getContainerTime(timeTraveler.jsmContainer) - tomorrowS).abs() <= okTimeDiffS
        log.info("\tTime travel was reflected in the already running companion container")
        getJsmGroovyTime(timeTraveler)

        when: "When starting a new companion container, after having travelled forward in time"
        timeTraveler.stopAndRemoveDeployment()
        timeTraveler = setupJsm()
        assert timeTraveler.jsmContainer.running: "Error starting time traveler container"

        then: "The time in the new container should also be in the future"
        assert (getContainerTime(timeTraveler.jsmContainer) - tomorrowS).abs() <= (okTimeDiffS * 5)



        cleanup:
        timeTraveler.stopAndRemoveDeployment()


    }


}
