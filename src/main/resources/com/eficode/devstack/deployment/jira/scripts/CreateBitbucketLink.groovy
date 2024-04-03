package com.eficode.devstack.deployment.jira.scripts

/**
 * A script executed in JIRA by Scriptrunner
 * Sets up an applicaiton link between jira anb bitbucket
 *
 * Requries: ScriptRunner and JiraShortcuts to be installed
 *
 * The following parameters should be replaced before executing:
 *      BITBUCKET_URL
 *      BITBUCKET_USER
 *      BITBUCKET_PASSWORD
 *
 */

import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.application.bitbucket.BitbucketApplicationType
import com.onresolve.scriptrunner.runner.customisers.WithPlugin

@WithPlugin("com.eficode.atlassian.jira.jiraShortcuts")
import com.eficode.atlassian.jira.jiraShortcuts.JiraShortcuts
import org.apache.log4j.Level
import org.apache.log4j.Logger


Logger log = Logger.getLogger("create.bb.app.link")
log.setLevel(Level.ALL)


JiraShortcuts jc = new JiraShortcuts()

ApplicationLink link  = jc.createApplicationLink(BitbucketApplicationType, "Bitbucket", true, "BITBUCKET_URL", "BITBUCKET_USER", "BITBUCKET_PASSWORD")

log.info("Created link:" + link.toString())

log.info("Script END")
