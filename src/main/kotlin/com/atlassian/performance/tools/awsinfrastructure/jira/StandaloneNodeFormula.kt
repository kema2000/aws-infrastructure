package com.atlassian.performance.tools.awsinfrastructure.jira

import com.atlassian.performance.tools.aws.api.Storage
import com.atlassian.performance.tools.awsinfrastructure.AwsCli
import com.atlassian.performance.tools.awsinfrastructure.api.hardware.Computer
import com.atlassian.performance.tools.awsinfrastructure.JiraStoragePaths
import com.atlassian.performance.tools.awsinfrastructure.api.storage.ApplicationStorage
import com.atlassian.performance.tools.infrastructure.api.Sed
import com.atlassian.performance.tools.infrastructure.api.jira.JiraGcLog
import com.atlassian.performance.tools.infrastructure.api.jira.JiraHomeSource
import com.atlassian.performance.tools.infrastructure.api.jira.JiraNodeConfig
import com.atlassian.performance.tools.infrastructure.api.jira.SetenvSh
import com.atlassian.performance.tools.infrastructure.api.jvm.JavaDevelopmentKit
import com.atlassian.performance.tools.infrastructure.api.jvm.OracleJDK
import com.atlassian.performance.tools.infrastructure.api.os.Ubuntu
import com.atlassian.performance.tools.jvmtasks.api.TaskTimer
import com.atlassian.performance.tools.ssh.api.Ssh
import com.atlassian.performance.tools.ssh.api.SshConnection
import org.apache.commons.codec.digest.DigestUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.time.Duration
import java.util.*

internal class StandaloneNodeFormula(
    private val jiraHomeSource: JiraHomeSource,
    private val pluginsTransport: Storage,
    private val resultsTransport: Storage,
    private val databaseIp: String,
    private val application: ApplicationStorage,
    private val ssh: Ssh,
    private val config: JiraNodeConfig,
    private val computer: Computer
) : NodeFormula {
    private val logger: Logger = LogManager.getLogger(this::class.java)
    private val jdk: JavaDevelopmentKit = OracleJDK()
    private val ubuntu: Ubuntu = Ubuntu()

    override val name = config.name

    override fun provision(): StandaloneStoppedNode {
        logger.info("Setting up $name...")

        ssh.newConnection().use { connection ->
            computer.setUp(connection)
            val jiraArchiveName = application.download(connection, ".")
            val jiraHome = TaskTimer.time("download Jira home") {
                jiraHomeSource.download(connection)
            }
            val unpackedProduct = getUnpackedProductName(connection, jiraArchiveName)

            replaceDbconfigUrl(connection, "$jiraHome/dbconfig.xml")
            connection.execute("tar -xzf $jiraArchiveName", Duration.ofMinutes(1))
            SetenvSh(unpackedProduct).setup(
                connection = connection,
                config = config,
                gcLog = JiraGcLog(unpackedProduct),
                jiraIp = ssh.host.ipAddress
            )
            connection.execute("echo jira.home=`realpath $jiraHome` > $unpackedProduct/atlassian-jira/WEB-INF/classes/jira-application.properties")
            connection.execute("echo jira.autoexport=false > $jiraHome/jira-config.properties")
            connection.execute("wget -q https://dev.mysql.com/get/Downloads/Connector-J/mysql-connector-java-5.1.40.tar.gz")
            connection.execute("tar -xzf mysql-connector-java-5.1.40.tar.gz")
            connection.execute("cp mysql-connector-java-5.1.40/mysql-connector-java-5.1.40-bin.jar $unpackedProduct/lib")

            provisionFromJiraStorage(connection, jiraHome)

            jdk.install(connection)
            val osMetrics = ubuntu.metrics(connection)

            config.splunkForwarder.jsonifyLog4j(
                connection,
                log4jPropertiesPath = "$unpackedProduct/atlassian-jira/WEB-INF/classes/log4j.properties"
            )
            config.splunkForwarder.run(connection, name, "/home/ubuntu/jirahome/log")

            logger.info("$name is set up")

            return StandaloneStoppedNode(
                name = name,
                jiraHome = jiraHome,
                analyticLogs = jiraHome,
                resultsTransport = resultsTransport,
                unpackedProduct = unpackedProduct,
                osMetrics = osMetrics,
                ssh = ssh,
                launchTimeouts = config.launchTimeouts
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

    private fun installAndConfigureCollectd(
        connection: SshConnection,
        jiraStorageDir: String
    ) {
        val jmxPort = config.remoteJmx.getRequiredPorts().firstOrNull()
        val configDir = "$jiraStorageDir/${JiraStoragePaths.COLLECTD_CONFIGS}"
        if (jmxPort != null) {
            Sed().replace(
                connection = connection,
                expression = "localhost:3333",
                output = "localhost:$jmxPort",
                file = "$configDir/*.conf")
        }
        ubuntu.install(connection, listOf("collectd"))

        config.collectdConfigs
            .stream()
            .filter(Objects::nonNull)
            .map { url -> DigestUtils.md5(url.toString()) }
            .map { md5 -> md5.joinToString("") { String.format("%02X", (it.toInt() and 0xFF)) }}
            .forEach {
                filename -> connection.execute("sudo mv $configDir/$filename.conf /etc/collectd/collectd.conf.d")
        }
        connection.execute("sudo mv $jiraStorageDir/${JiraStoragePaths.COLLECTD_JARS}/* /usr/share/collectd/java")
        connection.execute("sudo systemctl restart collectd.service")
    }

    private fun installPlugins(
        connection: SshConnection,
        jiraStorageDir: String,
        jiraHome: String
    ) {
        val targetDir = "$jiraHome/plugins/installed-plugins"
        connection.execute("mkdir -p $targetDir")
        connection.safeExecute("mv $jiraStorageDir/${JiraStoragePaths.APPS}/* $targetDir")
    }

    private fun provisionFromJiraStorage(
        connection: SshConnection,
        jiraHome: String
    ) {
        val jiraStorageDir = "/tmp/jira-storage"
        connection.execute("mkdir -p $jiraStorageDir")
        AwsCli().download(pluginsTransport.location, connection, target = jiraStorageDir)

        installPlugins(connection, jiraStorageDir, jiraHome)
        installAndConfigureCollectd(connection, jiraStorageDir)
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
}