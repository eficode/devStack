package com.eficode.devstack.util

import com.eficode.devstack.container.Container
import de.gesellix.docker.remote.api.ContainerCreateRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration
import java.time.Instant
import java.time.temporal.TemporalAdjuster

/**
 * <b>WARNING THIS AFFECTS ALL CONTAINERS</b>
 * <p>
 * This utility class is intended to change the "current time" as experienced by Docker Containers.
 * It has only been tested on, and will likely only work in the official "MacOS Docker Desktop" app.
 * <p>
 * The class essentially performs privilege escalation exploit to run root privileged commands on the docker engine,
 * <p>
 * <b>NEVER EVER</b> use this class on a production docker engine
 * <p>
 * <b>NOTE</b> all containers sharing a Docker Engine also share the date/time, be aware that when changing time
 * you change it for all running containers
 *
 */
class TimeMachine implements Container{

    String containerName = "TimeMachine"
    String containerMainPort = null
    String containerImage = "alpine"
    String containerImageTag = "latest"
    String defaultShell = "/bin/sh"



    /**
     * Travel back to the present
     * <b>NEVER EVER</b> use this class on a production docker engine <p>
     * <b>WARNING THIS AFFECTS ALL CONTAINERS - READ CLASS DOCUMENTATION</b>
     * @param dockerHost optional
     * @param dockerCertPath optional
     * @return true after verifying success
     */
    static boolean travelToNow(String dockerHost = "", String dockerCertPath = "") {
        return setTime(System.currentTimeSeconds(), dockerHost, dockerCertPath)
    }

    /**
     * Travel X days in time
     * <b>NEVER EVER</b> use this class on a production docker engine <p>
     * <b>WARNING THIS AFFECTS ALL CONTAINERS - READ CLASS DOCUMENTATION</b>
     * @param days Number of days to travel, can be negative (to the past) or positive (to the future)
     * @param dockerHost optional
     * @param dockerCertPath optional
     * @return true after verifying success
     */
    static boolean travelDays(int days, String dockerHost = "", String dockerCertPath = "") {

        long newEpochS = System.currentTimeSeconds() +  Duration.ofDays(days).toSeconds()

        return setTime(newEpochS, dockerHost, dockerCertPath)

    }


    /**
     * Change docker engine time <p>
     * <b>NEVER EVER</b> use this class on a production docker engine <p>
     * <b>WARNING THIS AFFECTS ALL CONTAINERS - READ CLASS DOCUMENTATION</b>
     * @param epochS The new epoch in seconds to be used
     * @param dockerHost optional
     * @param dockerCertPath optional
     * @return true after verifying success
     */
    static boolean setTime(long epochS, String dockerHost = "", String dockerCertPath = "") {

        Logger log = LoggerFactory.getLogger(this.class)
        log.info("Setting global docker time to:" + epochS)
        log.warn("THIS WILL AFFECT ALL CONTAINERS RUN BY THIS DOCKER ENGINE")


        assert epochS <= 9999999999 && epochS > 1000000000 : "Provide timestamp in epoch seconds"
        ArrayList<String> cmdOut = runCmdAndRm(["nsenter" ,"-t" ,"1" ,"-m" ,"-u" ,"-n", "-i", "sh", "-c", "pkill sntpc || date -s \"@${epochS}\" && echo Status \$?"], 5000, [] , dockerHost, dockerCertPath)
        assert cmdOut.toString().contains("Status 0") : "Error setting time"

        cmdOut = runCmdAndRm('date +"%s"', 5000, [], dockerHost, dockerCertPath )


        long newTime = cmdOut.find {it.isNumber()}?.toLong() ?: 0
        assert newTime : "Unexpected output when verifying time was change"
        assert newTime >= epochS : "The newly set time appears incorrect: " + newTime

        return true

    }



    @Override
    ContainerCreateRequest customizeContainerCreateRequest(ContainerCreateRequest containerCreateRequest){


        containerCreateRequest.hostConfig.setPrivileged(true)
        containerCreateRequest.hostConfig.setPidMode("host".toString())

        return containerCreateRequest

    }
}
