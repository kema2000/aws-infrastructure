package com.atlassian.performance.tools.awsinfrastructure.jira

import com.atlassian.performance.tools.aws.api.Storage
import com.atlassian.performance.tools.awsinfrastructure.AwsCli
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.Computer
import com.atlassian.performance.tools.awsinfrastructure.api.storage.ApplicationStorage
import com.atlassian.performance.tools.awsinfrastructure.api.storage.S3Artifact
import com.atlassian.performance.tools.infrastructure.api.Sed
import com.atlassian.performance.tools.infrastructure.api.jira.JiraGcLog
import com.atlassian.performance.tools.infrastructure.api.jira.JiraHomeSource
import com.atlassian.performance.tools.infrastructure.api.jira.JiraNodeConfig
import com.atlassian.performance.tools.infrastructure.api.jira.SetenvSh
import com.atlassian.performance.tools.infrastructure.api.os.Ubuntu
import com.atlassian.performance.tools.jvmtasks.api.Backoff
import com.atlassian.performance.tools.jvmtasks.api.IdempotentAction
import com.atlassian.performance.tools.jvmtasks.api.TaskTimer
import com.atlassian.performance.tools.ssh.api.Ssh
import com.atlassian.performance.tools.ssh.api.SshConnection
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.time.Duration

internal class StandaloneNodeFormula(
    private val jiraHomeSource: JiraHomeSource,
    private val pluginsTransport: Storage,
    private val resultsTransport: Storage,
    private val databaseIp: String,
    private val application: ApplicationStorage,
    private val ssh: Ssh,
    private val config: JiraNodeConfig,
    private val computer: Computer,
    private val isRds: Boolean
) : NodeFormula {
    private val logger: Logger = LogManager.getLogger(this::class.java)
    private val jdk = config.jdk
    private val ubuntu: Ubuntu = Ubuntu()

    override val name = config.name


    constructor (
            jiraHomeSource: JiraHomeSource,
            pluginsTransport: Storage,
            resultsTransport: Storage,
            databaseIp: String,
            application: ApplicationStorage,
            ssh: Ssh,
            config: JiraNodeConfig,
            computer: Computer
    ) : this(
            jiraHomeSource = jiraHomeSource,
            pluginsTransport = pluginsTransport,
            resultsTransport = resultsTransport,
            databaseIp = databaseIp,
            application = application,
            ssh = ssh,
            config = config,
            computer = computer,
            isRds = false
    )


    override fun provision(): StandaloneStoppedNode {
        logger.info("Setting up $name...")

        ssh.newConnection().use { connection ->
            computer.setUp(connection)
            val jiraArchiveName = application.download(connection, ".")
            val jiraHome = TaskTimer.time("download Jira home") {
                jiraHomeSource.download(connection)
            }
            connection.execute("tar -xzf $jiraArchiveName", Duration.ofMinutes(1))
            val unpackedProduct = getUnpackedProductName(connection, jiraArchiveName)

            connection.execute("echo jira.home=`realpath $jiraHome` > $unpackedProduct/atlassian-jira/WEB-INF/classes/jira-application.properties")
            connection.execute("echo jira.autoexport=false > $jiraHome/jira-config.properties")
            SetenvSh(unpackedProduct).setup(
                    connection = connection,
                    config = config,
                    gcLog = JiraGcLog(unpackedProduct),
                    jiraIp = ssh.host.ipAddress
            )
            if(isRds) {
                S3Artifact(
                        region = "eu-central-1",
                        bucketName = "jpt-oracle-test",
                        archivesLocation = "test",
                        archiveName = "dbconfig.xml"
                ).download(connection, "~/")

                S3Artifact(
                        region = "eu-central-1",
                        bucketName = "jpt-oracle-test",
                        archivesLocation = "test",
                        archiveName = "ojdbc8.jar"
                ).download(connection, "~/")

                Sed().replace(
                        connection = connection,
                        expression = "URLLLL",
                        output = "<url>jdbc:oracle:thin:@//" + databaseIp + "/ORCL_A</url>",
                        file = "~/dbconfig.xml"
                )

                connection.execute("rm $jiraHome/dbconfig.xml")
                connection.execute("cp ~/dbconfig.xml $jiraHome/")
                connection.execute("cp ojdbc8.jar $unpackedProduct/lib")
            } else {
                replaceDbconfigUrl(connection, "$jiraHome/dbconfig.xml")
                downloadMysqlConnector("https://dev.mysql.com/get/Downloads/Connector-J/mysql-connector-java-5.1.40.tar.gz", connection)
                connection.execute("tar -xzf mysql-connector-java-5.1.40.tar.gz")
                connection.execute("cp mysql-connector-java-5.1.40/mysql-connector-java-5.1.40-bin.jar $unpackedProduct/lib")
            }
            AwsCli().download(pluginsTransport.location, connection, target = "$jiraHome/plugins/installed-plugins")

            jdk.install(connection)
            val osMetrics = ubuntu.metrics(connection)

            config.splunkForwarder.jsonifyLog4j(
                connection,
                log4jPropertiesPath = "$unpackedProduct/atlassian-jira/WEB-INF/classes/log4j.properties"
            )
            config.splunkForwarder.run(connection, name, "/home/ubuntu/jirahome/log")
            config.profiler.install(connection)
            logger.info("$name is set up")

            return StandaloneStoppedNode(
                name = name,
                jiraHome = jiraHome,
                analyticLogs = jiraHome,
                resultsTransport = resultsTransport,
                unpackedProduct = unpackedProduct,
                osMetrics = osMetrics,
                ssh = ssh,
                launchTimeouts = config.launchTimeouts,
                jdk = jdk,
                profiler = config.profiler
            )
        }
    }

    private fun getUnpackedProductName(
        connection: SshConnection,
        archiveName: String
    ): String {
        return connection
            .execute("tar -tf $archiveName | head -n 1", timeout = Duration.ofMinutes(1))
            .output
            .split("/")
            .first()
    }

    private fun replaceDbconfigUrl(
        connection: SshConnection,
        dbconfigXml: String
    ) {
        Sed().replace(
            connection = connection,
            expression = "(<url>.*(@(//)?|//))" + "([^:/]+)" + "(.*</url>)",
            output = """\1$databaseIp\5""",
            file = dbconfigXml
        )
    }

    private fun mountEphemeralDrive(
        connection: SshConnection
    ) {
        connection.execute("sudo tar -cf /home/ubuntu.tar .")
        connection.execute("sudo mkfs.ext4 /dev/nvme1n1")
        connection.execute("sudo mount -t ext4 /dev/nvme1n1 /home/ubuntu")
        connection.execute("sudo chown ubuntu /home/ubuntu")
        connection.execute("cd /home/ubuntu")
        connection.execute("tar -xf /home/ubuntu.tar")
        connection.execute("sudo rm /home/ubuntu.tar")
    }

    private fun downloadMysqlConnector(
        url: String,
        connection: SshConnection
    ) {
        IdempotentAction(
            description = "Download MySQL connector",
            action = {
                connection.execute("wget -q $url")
            }
        ).retry(
            maxAttempts = 3,
            backoff = StaticBackoff(Duration.ofSeconds(5))
        )
    }
}

private class StaticBackoff(
    private val backOff: Duration
) : Backoff {
    override fun backOff(attempt: Int): Duration = backOff
}