package com.eficode.devstack.container.impl

import com.eficode.atlassian.jiraInstanceManager.beans.MarketplaceApp
import com.eficode.devstack.DevStackSpec
import com.eficode.devstack.container.Container
import com.eficode.devstack.deployment.impl.JsmH2Deployment
import com.eficode.devstack.util.TimeMachine
import kong.unirest.HttpResponse
import kong.unirest.UnirestInstance
import org.slf4j.LoggerFactory
import spock.lang.Ignore

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset


@Ignore(value = "Generally a bit dangerous to run if not intentional as it messes with the clock on the docker engine")
class TimeMachineSpec extends DevStackSpec {


    long okTimeDiffS = 3 //The nr of seconds that considered OK for time to diff in most circumstances during testing
    ZoneOffset defaultZoneOffset = ZoneId.systemDefault().offset


    def setupSpec() {

        DevStackSpec.log = LoggerFactory.getLogger(this.class)

        cleanupContainerNames = []
        cleanupContainerPorts = []

        disableCleanup = false


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

    JsmH2Deployment setupJsm(boolean useSnapshotIfAvailable = true) {

        log.info("Setting up JSM container")

        String jsmLicense = new File(System.getProperty("user.home") + "/.licenses/jira/jsm.license").text
        String srLicense = new File(System.getProperty("user.home") + "/.licenses/jira/sr.license").text
        assert jsmLicense: "Error finding JSM license"
        assert srLicense: "Error finding script runner license"


        MarketplaceApp srMarketApp = MarketplaceApp.searchMarketplace("Adaptavist ScriptRunner for JIRA", MarketplaceApp.Hosting.Datacenter).find { it.key == "com.onresolve.jira.groovy.groovyrunner" }
        MarketplaceApp.Version srVersion = srMarketApp?.getVersion("latest", MarketplaceApp.Hosting.Datacenter)


        JsmH2Deployment timeTraveler = new JsmH2Deployment("http://jsmtime.localhost:8060", dockerRemoteHost, dockerCertPath)
        timeTraveler.appsToInstall.put(srVersion, srLicense)
        timeTraveler.setJiraLicense(jsmLicense)

        timeTraveler.setupDeployment(true, true)
        /*
        if (useSnapshotIfAvailable && timeTraveler.jsmContainer.created && timeTraveler.jsmContainer.getSnapshotVolume()) {
            log.info("\tSnapshot is available, will restore that instead of setting up new JSM")
            assert timeTraveler.jsmContainer.restoreJiraHomeSnapshot(): "Error resting snapshot for " + timeTraveler.jsmContainer.shortId
            log.info("\t" * 2 + "Finished restoring JSM snapshot")
        } else {
            log.info("\tSetting up new blank JSM container ")
            timeTraveler.stopAndRemoveDeployment()
            timeTraveler.setupDeployment()
            assert timeTraveler.jiraRest.installScriptRunner(srLicense, "latest"): "Error installing ScriptRunner in JSM"
            log.info("\tScriptRunner was installed")
            assert timeTraveler.jsmContainer.snapshotJiraHome(): "Error snapshoting new container"
        }

         */

        assert waitForJiraToBeResponsive(timeTraveler)


        return timeTraveler
    }

    boolean waitForJiraToBeResponsive(JsmH2Deployment deployment, long timeOutS = 90) {
        UnirestInstance jsmUnirest = deployment.jiraRest.getUnirest()

        HttpResponse<Map> response = null

        log.info("\tWaiting for JIRA to become response")
        long start = System.currentTimeSeconds()
        while (response == null || response.body?.get("state") != "RUNNING") {

            try {


                response = jsmUnirest.get("/status").asObject(Map.class).ifFailure { log.warn("JSM container not yet responsive") }

                if ((start + timeOutS) < System.currentTimeSeconds()) {
                    log.error("Timed out waiting for JSM to start after ${System.currentTimeSeconds() - start} seconds")
                    return false
                }
            } catch (ignored) {
            }
            sleep(2000)

        }
        jsmUnirest.shutDown()
        log.debug("\t\tJSM started after ${System.currentTimeSeconds() - start} seconds")


        boolean srResponsive = false

        while (!srResponsive) {

            log.warn("SR not yet responsive")
            try {
                Map rawResponse = deployment.jiraRest.executeLocalScriptFile("return true")
                srResponsive = rawResponse.success
            } catch (ignored) {
            }

            if ((start + timeOutS) < System.currentTimeSeconds()) {
                log.error("Timed out waiting for JSM to start after ${System.currentTimeSeconds() - start} seconds")
                return false
            }
            sleep(2000)
        }
        log.debug("\t\tSR started after ${System.currentTimeSeconds() - start} seconds")

        return response.body.get("state") == "RUNNING" && srResponsive
    }

    long getJsmGroovyTime(JsmH2Deployment jsmDeploy) {

        Map rawOut = jsmDeploy.jiraRest.executeLocalScriptFile("log.warn(\"EPOCH:\" + new Date().toInstant().epochSecond)")

        assert rawOut.success == true: "There was an error querying for GroovyTime from JSM ScriptRunner"
        assert (rawOut.log as ArrayList<String>).size() == 1

        String rawLogStatement = (rawOut.log as ArrayList<String>).get(0)
        long epochS = rawLogStatement.substring(rawLogStatement.lastIndexOf(":") + 1).toLong()

        return epochS

    }

    def "Verify companion JSM containers created before and after time travel are affected by the changes"(LocalDateTime travelToTime) {

        setup:
        assert restoreDockerEngineTime(): "Error restoring docker engine time"


        JsmH2Deployment timeTraveler = setupJsm()
        assert timeTraveler.jsmContainer.running: "Error starting time traveler container"
        log.info("\tJSM has been setup")
        assert (getContainerTime(timeTraveler.jsmContainer) - TimeMachine.getExternalTime()).abs() <= okTimeDiffS
        log.info("\tTime in the TimeTraveler container was realTime before test")

        when: "Traveling in time, after having an already running companion container"

        long travelToTimeS = getEpochS(travelToTime)
        log.info("\tWill travel to:" + travelToTime + " ($travelToTimeS)")
        assert TimeMachine.setLocalDateTime(travelToTime, dockerRemoteHost, dockerCertPath): "TimeMachine container errord when traveling to $travelToTime"
        log.info("\tTime was updated using the TimeMachine")


        then: "Time in the companion should be updated after the change"
        assert (getContainerTime(timeTraveler.jsmContainer) - travelToTimeS).abs() <= okTimeDiffS: "The container OS time and destination time travel time differs"
        log.info("\tTime travel was reflected in the OS of the already running companion container")
        assert (getJsmGroovyTime(timeTraveler) - travelToTimeS).abs() <= okTimeDiffS: "The container Groovy time and destination time travel time differs"
        log.info("\tTime travel was reflected by Groovy script executed by ScriptRunner")
        assert (getJsmGroovyTime(timeTraveler) - getContainerTime(timeTraveler.jsmContainer)).abs() <= okTimeDiffS: "The container Groovy time and OS time differs"
        log.info("\tCompanion container OS and Groovy time is the same")


        when: "When starting a new companion container, after having travelled forward in time"
        timeTraveler = setupJsm()
        assert timeTraveler.jsmContainer.running: "Error starting time traveler container"

        then: "The time in the new container should also be in the future"
        assert (getContainerTime(timeTraveler.jsmContainer) - travelToTimeS).abs() <= 120
        assert (getJsmGroovyTime(timeTraveler) - getContainerTime(timeTraveler.jsmContainer)).abs() <= okTimeDiffS: "The container Groovy time and OS time differs"
        log.info("\tCompanion container OS and Groovy time is the same")

        where:
        travelToTime | _
        LocalDateTime.now().plusDays(1)  | _
        LocalDateTime.now().minusDays(1) | _

    }


}
