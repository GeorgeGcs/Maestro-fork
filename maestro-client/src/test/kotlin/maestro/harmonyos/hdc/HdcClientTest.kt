package maestro.harmonyos.hdc

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

class HdcClientTest {
    @Test
    fun `empty device output is an empty list`() {
        listOf("", "  \r\n", "[Empty]\r\n").forEach { output ->
            assertThat(client(output).listDevices()).isEmpty()
        }
    }

    @Test
    fun `device discovery uses plain listing and removes duplicates`() {
        val runner = RecordingRunner(HdcCommandResult(0, "USB-001\r\n192.0.2.1:8710\r\nUSB-001\r\n", ""))
        val client = HdcClient("C:/SDK with spaces/hdc.exe", runner)

        assertThat(client.listDevices()).containsExactly("USB-001", "192.0.2.1:8710").inOrder()
        assertThat(runner.command).containsExactly("C:/SDK with spaces/hdc.exe", "list", "targets").inOrder()
    }

    @Test
    fun `diagnostics are not treated as device identifiers`() {
        listOf(
            "[Fail] failed to connect",
            "error: device not found",
            "daemon started successfully",
            "error",
            "USB-001 USB Connected",
            "USB-001\n[Empty]",
            "USB-001\n[Warning] daemon restarted",
        ).forEach { output ->
            assertThrows(HdcException::class.java) { client(output).listDevices() }
        }
    }

    @Test
    fun `listing diagnostics on stderr fail even with zero exit code`() {
        val runner = RecordingRunner(HdcCommandResult(0, "USB-001", "connection warning"))
        assertThrows(HdcException::class.java) { HdcClient("hdc", runner).listDevices() }
    }

    @Test
    fun `single device is selected automatically`() {
        assertThat(client("USB-001").selectDevice()).isEqualTo("USB-001")
    }

    @Test
    fun `missing and ambiguous devices require user selection`() {
        assertThrows(HdcDeviceSelectionException::class.java) { client("[Empty]").selectDevice() }
        assertThrows(HdcDeviceSelectionException::class.java) { client("USB-001\nUSB-002").selectDevice() }
        assertThrows(HdcDeviceSelectionException::class.java) { client("USB-001").selectDevice("USB-002") }
        assertThat(client("USB-001\nUSB-002").selectDevice("USB-002")).isEqualTo("USB-002")
    }

    @Test
    fun `nonzero exit preserves error output`() {
        val result = HdcCommandResult(17, "output", "connection lost")
        val error = assertThrows(HdcCommandException::class.java) {
            HdcClient("hdc", RecordingRunner(result)).listDevices()
        }
        assertThat(error.result).isEqualTo(result)
        assertThat(error.message).contains("exit 17")
        assertThat(error.message).contains("connection lost")
    }

    @Test
    fun `failure text with a successful host exit is still an error`() {
        val error = assertThrows(HdcCommandException::class.java) {
            HdcClient("hdc", RecordingRunner(HdcCommandResult(0, "[Fail] device offline", "")))
                .shell("USB-001", listOf("uitest", "dumpLayout"))
        }
        assertThat(error.result.exitCode).isEqualTo(0)
    }

    @Test
    fun `shell arguments are quoted for device shell and target is explicit`() {
        val runner = RecordingRunner(HdcCommandResult(0, "done", ""))
        val timeout = Duration.ofSeconds(3)
        HdcClient("hdc", runner).shell(
            "USB-001", listOf("printf", "%s", "中文 text\n'quoted'; echo \$HOME", ""), timeout,
        )

        assertThat(runner.command).containsExactly(
            "hdc", "-t", "USB-001", "shell",
            "'printf' '%s' '中文 text\n'\"'\"'quoted'\"'\"'; echo \$HOME' ''",
        ).inOrder()
        assertThat(runner.timeout).isEqualTo(timeout)
    }

    @Test
    fun `invalid targets and NUL arguments fail before starting a command`() {
        val runner = RecordingRunner(HdcCommandResult(0, "", ""))
        val client = HdcClient("hdc", runner)
        listOf("", "-t", "USB 001", "USB;reboot", "USB\n001").forEach { target ->
            assertThrows(IllegalArgumentException::class.java) { client.shell(target, listOf("echo")) }
        }
        assertThrows(IllegalArgumentException::class.java) { client.shell("USB-001", listOf("echo", "\u0000")) }
        assertThrows(IllegalArgumentException::class.java) { client.shell("USB-001", emptyList()) }
        assertThat(runner.command).isEmpty()
    }

    @Test
    fun `receive file uses explicit target and separate path arguments`() {
        val runner = RecordingRunner(HdcCommandResult(0, "FileTransfer finish", ""))
        val target = Path.of("artifacts", "screen capture.png")
        HdcClient("hdc", runner).receiveFile("USB-001", "/data/local/tmp/capture.png", target)

        assertThat(runner.command).containsExactly(
            "hdc", "-t", "USB-001", "file", "recv", "/data/local/tmp/capture.png", target.toAbsolutePath().toString(),
        ).inOrder()
        assertThrows(IllegalArgumentException::class.java) {
            HdcClient("hdc", runner).receiveFile("USB-001", "-a", target)
        }
    }

    private fun client(output: String): HdcClient = HdcClient("hdc", RecordingRunner(HdcCommandResult(0, output, "")))

    private class RecordingRunner(private val result: HdcCommandResult) : HdcCommandRunner {
        var command: List<String> = emptyList()
        var timeout: Duration? = null

        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            this.command = command
            this.timeout = timeout
            return result
        }
    }
}
