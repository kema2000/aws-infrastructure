package com.atlassian.performance.tools.awsinfrastructure.api.jira

import com.amazonaws.services.cloudformation.model.Parameter
import com.amazonaws.services.ec2.model.Tag
import com.amazonaws.services.rds.model.ModifyDBInstanceRequest
import com.atlassian.performance.tools.aws.api.*
import com.atlassian.performance.tools.awsinfrastructure.TemplateBuilder
import com.atlassian.performance.tools.awsinfrastructure.api.RemoteLocation
import com.atlassian.performance.tools.awsinfrastructure.api.database.DatabaseFormula
import com.atlassian.performance.tools.awsinfrastructure.api.database.DatabaseFormula.ProvisionedDatabase
import com.atlassian.performance.tools.awsinfrastructure.api.database.DockerDatabaseFormula
import com.atlassian.performance.tools.awsinfrastructure.api.database.RdsDatabase
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.C4EightExtraLargeElastic
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.Computer
import com.atlassian.performance.tools.awsinfrastructure.api.storage.ApplicationStorage
import com.atlassian.performance.tools.awsinfrastructure.findDBIPAddress
import com.atlassian.performance.tools.awsinfrastructure.findJiraIPAddresses
import com.atlassian.performance.tools.awsinfrastructure.jira.StandaloneNodeFormula
import com.atlassian.performance.tools.awsinfrastructure.pickAvailabilityZone
import com.atlassian.performance.tools.concurrency.api.submitWithLogContext
import com.atlassian.performance.tools.infrastructure.api.app.Apps
import com.atlassian.performance.tools.infrastructure.api.database.Database
import com.atlassian.performance.tools.infrastructure.api.jira.JiraHomeSource
import com.atlassian.performance.tools.infrastructure.api.jira.JiraNodeConfig
import com.atlassian.performance.tools.jvmtasks.api.TaskTimer.time
import com.atlassian.performance.tools.ssh.api.Ssh
import com.atlassian.performance.tools.ssh.api.SshHost
import com.google.common.util.concurrent.ThreadFactoryBuilder
import org.apache.logging.log4j.CloseableThreadContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.net.URI
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

class StandaloneFormula (
    private val apps: Apps,
    private val application: ApplicationStorage,
    private val jiraHomeSource: JiraHomeSource,
    private val databaseFormula: DatabaseFormula,
    private val config: JiraNodeConfig,
    private val computer: Computer,
    private val stackCreationTimeout: Duration
) : JiraFormula {

    @Deprecated(message = "Use StandaloneFormula.Builder instead.")
    constructor (
        apps: Apps,
        application: ApplicationStorage,
        jiraHomeSource: JiraHomeSource,
        database: Database
    ) : this(
        apps = apps,
        application = application,
        jiraHomeSource = jiraHomeSource,
        config = JiraNodeConfig.Builder().build(),
        computer = C4EightExtraLargeElastic(),
        database = database
    )

    @Deprecated(message = "Use StandaloneFormula.Builder instead.")
    constructor (
        apps: Apps,
        application: ApplicationStorage,
        jiraHomeSource: JiraHomeSource,
        database: Database,
        config: JiraNodeConfig,
        computer: Computer
    ) : this(
        apps = apps,
        application = application,
        jiraHomeSource = jiraHomeSource,
        config = config,
        databaseFormula = DockerDatabaseFormula(
            database = database
        ),
        computer = computer,
        stackCreationTimeout = Duration.ofMinutes(30)
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

        val template = TemplateBuilder("single-node.yaml")
            .mergeWith(getCloudFormationTemplateForDatabase(databaseFormula.database), arrayOf("Resources", "Parameters"))
            .adaptTo(listOf(config))

        val parameters: List<Parameter>;

        val zone1 = aws.pickAvailabilityZone().zoneName
        val zone2 = aws.pickAvailabilityZone(Collections.singleton(zone1)).zoneName

        parameters  = listOf(
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
                        .withParameterValue(computer.instanceType.toString()),
                Parameter()
                        .withParameterKey("AvailabilityZone1")
                        .withParameterValue(zone1),
                Parameter()
                        .withParameterKey("AvailabilityZone2")
                        .withParameterValue(zone2))

        val stackProvisioning = executor.submitWithLogContext("provision stack") {
            StackFormula(
                investment = investment,
                cloudformationTemplate = template,
                parameters = parameters,
                aws = aws,
                pollingTimeout = stackCreationTimeout
            ).provision()
        }

        val uploadPlugins = executor.submitWithLogContext("upload plugins") {
            apps.listFiles().forEach { pluginsTransport.upload(it) }
        }

        val jiraStack: ProvisionedStack = stackProvisioning.get()

        val machines = jiraStack.listMachines()

        val jiraIp = jiraStack.findJiraIPAddresses(aws).single()
        val databaseIp = jiraStack.findDBIPAddress(aws)
        val jiraAddress = URI("http://$jiraIp:8080/")

        val keyPath = key.get().file.path
        val ssh = Ssh(SshHost(jiraIp, "ubuntu", keyPath), connectivityPatience = 5)

        CloseableThreadContext.push("Jira node").use {
            key.get().file.facilitateSsh(jiraIp)
        }

        val futureProvisionedDatabase: Future<ProvisionedDatabase> = executor.submitWithLogContext("database") {
            databaseFormula.provision(key.get(), jiraAddress, jiraStack, aws)
        }

        val nodeFormula = StandaloneNodeFormula(
            config = config,
            jiraHomeSource = jiraHomeSource,
            pluginsTransport = pluginsTransport,
            resultsTransport = resultsTransport,
            databaseIp = databaseIp,
            application = application,
            ssh = ssh,
            computer = computer,
            isRds = RdsDatabase::class.isInstance(databaseFormula.database)
        )

        uploadPlugins.get()

        val provisionedNode = nodeFormula.provision()

        val provisionedDatabase: ProvisionedDatabase = futureProvisionedDatabase.get()
        val remoteDatabaseDataLocation = provisionedDatabase.remoteDatabaseDataLocation
        executor.shutdownNow()
        val node = time("start") { provisionedNode.start() }

        val jira = Jira(
            nodes = listOf(node),
            jiraHome = RemoteLocation(
                ssh.host,
                provisionedNode.jiraHome
            ),
            database = remoteDatabaseDataLocation,
            address = jiraAddress,
            jmxClients = listOf(config.remoteJmx.getClient(jiraIp))
        )
        logger.info("$jira is set up, will expire ${jiraStack.expiry}")
        return@time ProvisionedJira(jira = jira, resource = jiraStack)
    }

    class Builder(
        private val application: ApplicationStorage,
        private val jiraHomeSource: JiraHomeSource,
        private val database: Database
    ) {
        private var config: JiraNodeConfig = JiraNodeConfig.Builder().build()
        private var apps: Apps = Apps(emptyList())
        private var computer: Computer = C4EightExtraLargeElastic()
        private var stackCreationTimeout: Duration = Duration.ofMinutes(30)

        fun config(config: JiraNodeConfig): Builder = apply { this.config = config }
        fun apps(apps: Apps): Builder = apply { this.apps = apps }
        fun computer(computer: Computer): Builder = apply { this.computer = computer }
        fun stackCreationTimeout(stackCreationTimeout: Duration): Builder =
            apply { this.stackCreationTimeout = stackCreationTimeout }

        fun build(): StandaloneFormula = StandaloneFormula(
            apps = apps,
            application = application,
            jiraHomeSource = jiraHomeSource,
            databaseFormula = DockerDatabaseFormula(database),
            config = config,
            computer = computer,
            stackCreationTimeout = stackCreationTimeout
        )
    }
}