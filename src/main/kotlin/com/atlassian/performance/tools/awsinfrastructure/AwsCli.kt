package com.atlassian.performance.tools.awsinfrastructure

import com.atlassian.performance.tools.aws.api.StorageLocation
import com.atlassian.performance.tools.infrastructure.api.os.Ubuntu
import com.atlassian.performance.tools.ssh.api.SshConnection
import java.time.Duration

internal class AwsCli {
    fun ensureAwsCli(ssh: SshConnection) {
        Ubuntu().install(ssh, listOf("zip", "python"), Duration.ofMinutes(3))
        if (!ssh.safeExecute("aws --version").isSuccessful()) {
            ssh.execute(
                cmd = "curl --silent https://s3.amazonaws.com/aws-cli/awscli-bundle-1.15.51.zip -o awscli-bundle.zip",
                timeout = Duration.ofSeconds(50)
            )
            ssh.execute("unzip -n -q awscli-bundle.zip")
            ssh.execute("sudo ./awscli-bundle/install -i /usr/local/aws -b /usr/local/bin/aws")
        }
    }

    fun download(
        location: StorageLocation,
        ssh: SshConnection,
        target: String,
        timeout: Duration = Duration.ofSeconds(30)
    ) {
        ensureAwsCli(ssh)
        ssh.execute(
            "aws s3 sync --only-show-errors --region=${location.regionName} ${location.uri} $target",
            timeout
        )
    }

    fun upload(
        location: StorageLocation,
        ssh: SshConnection,
        source: String,
        timeout: Duration
    ) {
        ensureAwsCli(ssh)
        ssh.execute(
            "aws s3 sync --only-show-errors --region=${location.regionName} $source ${location.uri}",
            timeout
        )
    }


    fun downloadFile(
        location: StorageLocation,
        ssh: SshConnection,
        file: String,
        target: String,
        timeout: Duration
    ) {
        ssh.execute(
            downloadFileCommand(location, ssh, file, target),
            timeout
        )
    }

    fun downloadFileCommand(
        location: StorageLocation,
        ssh: SshConnection,
        file: String,
        target: String
    ): String {
        ensureAwsCli(ssh)
        return "aws s3 cp --only-show-errors --region=${location.regionName} ${location.uri}/$file $target"
    }

    fun uploadFile(
        location: StorageLocation,
        ssh: SshConnection,
        source: String,
        timeout: Duration
    ) {
        ensureAwsCli(ssh)
        ssh.execute(
            "aws s3 cp --only-show-errors --region=${location.regionName} $source ${location.uri}/$source",
            timeout
        )
    }
}