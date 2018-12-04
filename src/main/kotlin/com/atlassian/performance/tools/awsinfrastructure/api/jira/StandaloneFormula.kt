package com.atlassian.performance.tools.awsinfrastructure.api.jira

import com.amazonaws.services.cloudformation.model.Parameter
import com.amazonaws.services.ec2.model.Tag
import com.atlassian.performance.tools.aws.api.*
import com.atlassian.performance.tools.awsinfrastructure.JiraStoragePaths
import com.atlassian.performance.tools.awsinfrastructure.TemplateBuilder
import com.atlassian.performance.tools.awsinfrastructure.api.RemoteLocation
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.C4EightExtraLargeElastic
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.Computer
import com.atlassian.performance.tools.awsinfrastructure.api.storage.ApplicationStorage
import com.atlassian.performance.tools.awsinfrastructure.jira.StandaloneNodeFormula
import com.atlassian.performance.tools.concurrency.api.submitWithLogContext
import com.atlassian.performance.tools.infrastructure.api.app.Apps
import com.atlassian.performance.tools.infrastructure.api.database.Database
import com.atlassian.performance.tools.infrastructure.api.jira.JiraHomeSource
import com.atlassian.performance.tools.infrastructure.api.jira.JiraJvmArgs
import com.atlassian.performance.tools.infrastructure.api.jira.JiraLaunchTimeouts
import com.atlassian.performance.tools.infrastructure.api.jira.JiraNodeConfig
import com.atlassian.performance.tools.io.api.ensureDirectory
import com.atlassian.performance.tools.jvmtasks.api.TaskTimer.time
import com.atlassian.performance.tools.ssh.api.Ssh
import com.atlassian.performance.tools.ssh.api.SshHost
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.apache.commons.codec.digest.DigestUtils
import org.apache.logging.log4j.CloseableThreadContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.net.URI
import java.time.Duration
import java.nio.file.Files
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

class StandaloneFormula(
    private val apps: Apps,
    private val application: ApplicationStorage,
    private val jiraHomeSource: JiraHomeSource,
    private val database: Database,
    private val config: JiraNodeConfig,
    private val computer: Computer
) : JiraFormula {

    constructor (
        apps: Apps,
        application: ApplicationStorage,
        jiraHomeSource: JiraHomeSource,
        database: Database
    ) : this(
        apps = apps,
        application = application,
        jiraHomeSource = jiraHomeSource,
        database = database,
        config = JiraNodeConfig.Builder().build(),
        computer = C4EightExtraLargeElastic()
    )

    private val logger: Logger = LogManager.getLogger(this::class.java)

    override fun provision(
        investment: Investment,
        pluginsTransport: Storage,
        resultsTransport: Storage,
        key: Future<SshKey>,
        roleProfile: String,
        aws: Aws
    ): ProvisionedJira = time("provision Jira Server") {
        logger.info("Setting up Jira...")

        val executor = Executors.newFixedThreadPool(
            4,
            ThreadFactoryBuilder().setNameFormat("standalone-provisioning-thread-%d")
                .build()
        )

        val template = TemplateBuilder("single-node.yaml").adaptTo(listOf(config))

        val stackProvisioning = executor.submitWithLogContext("provision stack") {
            StackFormula(
                investment = investment,
                cloudformationTemplate = template,
                parameters = listOf(
                    Parameter()
                        .withParameterKey("KeyName")
                        .withParameterValue(key.get().remote.name),
                    Parameter()
                        .withParameterKey("InstanceProfile")
                        .withParameterValue(roleProfile),
                    Parameter()
                        .withParameterKey("Ami")
                        .withParameterValue(aws.defaultAmi),
                    Parameter()
                        .withParameterKey("JiraInstanceType")
                        .withParameterValue(computer.instanceType.toString())
                ),
                aws = aws
            ).provision()
        }

        val uploadJiraStorage = executor.submitWithLogContext("JiraStorage bucket") {
            val jiraStorageDir = createTempDir("jira-storage")
            val installedPlugins = jiraStorageDir.resolve(JiraStoragePaths.APPS).ensureDirectory()
            logger.info("Copying Apps into $installedPlugins")

            // apps to install
            apps.listFiles().forEach { it.copyTo(installedPlugins) }

            // collectd
            val collectdConf = jiraStorageDir.resolve(JiraStoragePaths.COLLECTD_CONFIGS).ensureDirectory()
            logger.info("Copying collectd configs into $collectdConf")
            config.collectdConfigs.stream().filter(Objects::nonNull).forEach{ uri ->
                val urlMd5 = DigestUtils.md5(uri.toString())
                val filename = urlMd5.joinToString("") { String.format("%02X", (it.toInt() and 0xFF)) }
                val target = collectdConf.toPath().resolve("$filename.conf")
                try {
                    Files.copy(uri.toURL().openStream(), target)
                    logger.info("Copied '$uri' to '$target'")
                } catch (e: Exception) {
                    logger.warn("Failed to copy '$uri' to '$target'", e)
                }
            }
            val collectdJar = jiraStorageDir.resolve(JiraStoragePaths.COLLECTD_JARS).ensureDirectory()
            Files.copy(javaClass.getResourceAsStream("/collectd/generic-jmx.jar"),
                collectdJar.toPath().resolve("generic-jmx.jar"))

            // upload to Jira storage
            logger.info("Uploading to JiraStorage bucket")
            jiraStorageDir.listFiles().forEach { pluginsTransport.upload(it) }
        }

        val jiraStack = stackProvisioning.get()
        val keyPath = key.get().file.path

        val machines = jiraStack.listMachines()
        val databaseIp = machines.single { it.tags.contains(Tag("jpt-database", "true")) }.publicIpAddress
        val databaseHost = SshHost(databaseIp, "ubuntu", keyPath)
        val databaseSsh = Ssh(databaseHost, connectivityPatience = 4)
        val jiraIp = machines.single { it.tags.contains(Tag("jpt-jira", "true")) }.publicIpAddress
        val jiraAddress = URI("http://$jiraIp:8080/")

        val setupDatabase = executor.submitWithLogContext("database") {
            databaseSsh.newConnection().use {
                logger.info("Setting up database...")
                key.get().file.facilitateSsh(databaseIp)
                val location = database.setup(it)
                logger.info("Database is set up")
                logger.info("Starting database...")
                database.start(jiraAddress, it)
                logger.info("Database is started")
                RemoteLocation(databaseHost, location)
            }
        }

        val ssh = Ssh(SshHost(jiraIp, "ubuntu", keyPath), connectivityPatience = 5)

        CloseableThreadContext.push("Jira node").use {
            key.get().file.facilitateSsh(jiraIp)
        }
        val nodeFormula = StandaloneNodeFormula(
            config = config,
            jiraHomeSource = jiraHomeSource,
            pluginsTransport = pluginsTransport,
            resultsTransport = resultsTransport,
            databaseIp = databaseIp,
            application = application,
            ssh = ssh,
            computer = computer
        )

        uploadJiraStorage.get()

        val provisionedNode = nodeFormula.provision()

        val databaseDataLocation = setupDatabase.get()
        executor.shutdownNow()
        val node = time("start") { provisionedNode.start() }

        val jira = Jira(
            nodes = listOf(node),
            jiraHome = RemoteLocation(
                ssh.host,
                provisionedNode.jiraHome
            ),
            database = databaseDataLocation,
            address = jiraAddress,
            jmxClients = listOf(config.remoteJmx.getClient(jiraIp))
        )
        logger.info("$jira is set up, will expire ${jiraStack.expiry}")
        return@time ProvisionedJira(jira = jira, resource = jiraStack)
    }
}