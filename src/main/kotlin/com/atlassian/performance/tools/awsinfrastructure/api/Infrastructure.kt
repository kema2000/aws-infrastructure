package com.atlassian.performance.tools.awsinfrastructure.api

import com.atlassian.performance.tools.aws.api.SshKey
import com.atlassian.performance.tools.aws.api.Storage
import com.atlassian.performance.tools.awsinfrastructure.api.jira.Jira
import com.atlassian.performance.tools.concurrency.api.submitWithLogContext
import com.atlassian.performance.tools.infrastructure.api.MeasurementSource
import com.atlassian.performance.tools.infrastructure.api.virtualusers.VirtualUsers
import com.atlassian.performance.tools.jvmtasks.api.TaskTimer.time
import com.atlassian.performance.tools.virtualusers.api.VirtualUserOptions
import com.atlassian.performance.tools.virtualusers.api.config.VirtualUserBehavior
import com.atlassian.performance.tools.virtualusers.api.config.VirtualUserTarget
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import java.util.concurrent.Executors

class Infrastructure<out T : VirtualUsers>(
    val virtualUsers: T,
    val jira: Jira,
    private val resultsTransport: Storage,
    val sshKey: SshKey
) {
    private val logger: Logger = LogManager.getLogger(this::class.java)

    fun applyLoad(
        behavior: VirtualUserBehavior
    ) {
        time("applying load") {
            virtualUsers.applyLoad(
                VirtualUserOptions(
                    target = VirtualUserTarget(
                        webApplication = jira.address,
                        userName = behavior.adminUser,
                        password = behavior.adminPassword
                    ),
                    behavior = behavior
                )
            )
        }
    }

    fun downloadResults(
        target: Path
    ): Path {
        logger.info("Downloading results...")
        val resultSources: List<MeasurementSource> = listOf(virtualUsers, jira)
        val executor = Executors.newFixedThreadPool(
            resultSources.size.butNotMoreThan(4),
            ThreadFactoryBuilder()
                .setNameFormat("gather-results-thread-%d")
                .build()
        )
        resultSources
            .map { executor.submitWithLogContext("results") { it.gatherResults() } }
            .forEach {
                try {
                    it.get()
                } catch (e: Exception) {
                    logger.error("Failed to gather results. Proceeding...", e)
                }
            }
        executor.shutdownNow()
        logger.debug("Results are gathered")
        val results = resultsTransport.download(target)
        logger.info("Results are downloaded")
        return results
    }

    override fun toString(): String {
        return "Infrastructure(virtualUsers=$virtualUsers, jira=$jira, resultsTransport=$resultsTransport, sshKey=$sshKey)"
    }
}

private fun Int.butNotMoreThan(
    max: Int
) = Math.min(this, max)