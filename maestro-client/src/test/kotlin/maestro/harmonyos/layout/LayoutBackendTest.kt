package maestro.harmonyos.layout

import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LayoutBackendTest {
    private val deviceId = "TEST-01"
    private val budget = Duration.ofSeconds(4)

    @Test
    fun `requires a unique forward for the selected target and the UITest endpoint`() {
        val rule = "TEST-01\ttcp:12345 localabstract:uitest_socket   [Forward]"
        assertThat(HdcClient.matchesUitestLayoutForward(rule, deviceId, 12345)).isTrue()
        for (invalid in listOf("[Empty]", rule.replace("TEST-01", "TEST-02"),
            rule.replace("uitest_socket", "another_socket"), rule.replace("[Forward]", "[Reverse]"),
            "$rule\n" + rule.replace("TEST-01", "TEST-02"))) {
            assertThat(HdcClient.matchesUitestLayoutForward(invalid, deviceId, 12345)).isFalse()
        }
    }

    @Test
    fun `a mismatched forward never connects to the loopback listener`() {
        listener().use { server ->
            val fixture = HdcFixture(server.localPort, "OTHER-DEVICE")
            val device = fixture.device()
            device.connect()
            try {
                assertThat(device.hierarchy(budget).attributes["text"]).isEqualTo("HDC")
                server.soTimeout = 100
                org.junit.jupiter.api.Assertions.assertThrows(java.net.SocketTimeoutException::class.java) { server.accept() }
            } finally { device.disconnect() }
        }
    }

    @Test
    fun `a failed RPC read closes the socket and falls back for the session`() {
        listener().use { server ->
            val fixture = HdcFixture(server.localPort)
            val device = fixture.device()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream()
                        val request = receiveLayoutFrame { input.read() }
                        check(String(request).contains("captureLayout"))
                        socket.getOutputStream().write("""{"exception":"fixture failure"}""".toByteArray())
                        check(input.read() == -1) { "Failed RPC connection was left open" }
                    }
                }
                device.connect()
                assertThat(device.hierarchy(budget).attributes["text"]).isEqualTo("HDC")
                assertThat(device.hierarchy(budget).attributes["text"]).isEqualTo("HDC")
                peer.get(5, TimeUnit.SECONDS)
                assertThat(fixture.dumps).isEqualTo(2)
            } finally {
                device.disconnect()
                executor.shutdownNow()
            }
        }
    }

    private fun listener() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 4000 }

    private inner class HdcFixture(private val port: Int, private val owner: String = deviceId) {
        var dumps = 0

        fun device(): HdcHarmonyOSDevice {
            val runner = HdcCommandRunner { command, _ ->
                val response = when {
                    command.drop(1) == listOf("list", "targets") -> deviceId
                    command.drop(1) == listOf("fport", "ls") -> "$owner tcp:$port localabstract:uitest_socket [Forward]"
                    command.last().startsWith("'uitest' 'dumpLayout'") -> { dumps++; "layout saved" }
                    command.getOrNull(3) == "file" && command.getOrNull(4) == "recv" -> {
                        Files.writeString(Path.of(command.last()), """{"attributes":{"text":"HDC"},"children":[]}""")
                        "FileTransfer finish"
                    }
                    command.last().startsWith("'rm' '-f'") -> ""
                    else -> error("Unexpected HDC command: $command")
                }
                HdcCommandResult(0, response, "")
            }
            return HdcHarmonyOSDevice(deviceId, HdcClient("hdc", runner), port)
        }
    }
}
