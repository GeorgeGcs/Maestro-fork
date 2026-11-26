package maestro.harmonyos.layout

import com.google.common.truth.Truth.assertThat
import com.fasterxml.jackson.databind.ObjectMapper
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    fun `required RPC rejects a forward for another device without dumping layout`() {
        val fixture = HdcFixture(12345, "OTHER-DEVICE")
        val device = fixture.device(requireLayoutRpc = true)
        val error = assertThrows(HdcException::class.java) { device.connect() }
        assertThat(error).hasMessageThat().contains("explicit_forward_unavailable")
        assertThat(fixture.dumps).isEqualTo(0)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a failed RPC read closes the socket and honors the fallback policy`(requireLayoutRpc: Boolean) {
        listener().use { server ->
            val fixture = HdcFixture(server.localPort)
            val device = fixture.device(requireLayoutRpc = requireLayoutRpc)
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
                repeat(2) {
                    if (requireLayoutRpc) {
                        val error = assertThrows(HdcException::class.java) { device.hierarchy(budget) }
                        assertThat(error).hasMessageThat().contains("rpc_read_failed")
                    } else {
                        assertThat(device.hierarchy(budget).attributes["text"]).isEqualTo("HDC")
                    }
                }
                peer.get(5, TimeUnit.SECONDS)
                assertThat(fixture.dumps).isEqualTo(if (requireLayoutRpc) 0 else 2)
            } finally {
                device.disconnect()
                executor.shutdownNow()
            }
        }
    }

    private fun listener() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 4000 }

    @Test
    fun `rotation is sent once and read back before success`() {
        listener().use { server ->
            val device = HdcFixture(server.localPort).device()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val mapper = ObjectMapper()
                        fun reply(api: String, result: String, rotation: Int? = null) {
                            val request = mapper.readTree(receiveLayoutFrame { socket.getInputStream().read() })
                            check(request.path("params").path("api").asText() == api)
                            if (rotation != null) check(request.path("params").path("args").get(0).asInt() == rotation)
                            val id = request.path("request_id").asText()
                            socket.getOutputStream().write("""{"request_id":"$id","result":$result}""".toByteArray())
                        }
                        reply("Driver.create", "\"Driver#1\"")
                        reply("Driver.getDisplayRotation", "0")
                        reply("Driver.setDisplayRotation", "null", 1)
                        reply("Driver.getDisplayRotation", "1")
                    }
                }
                device.connect()
                device.setDisplayRotation(1, budget)
                peer.get(5, TimeUnit.SECONDS)
            } finally {
                device.disconnect()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `the verified socket serves live display dimensions and layouts`() {
        listener().use { server ->
            val device = HdcFixture(server.localPort).device()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val peer = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        fun respond(api: String, result: String) {
                            val request = String(receiveLayoutFrame { input.read() })
                            check(request.contains(api))
                            output.write("""{"result":$result}""".toByteArray())
                        }
                        respond("Driver.create", "\"Driver#1\"")
                        respond("Driver.getDisplaySize", """{"x":1260,"y":2720}""")
                        respond("captureLayout", """{"attributes":{"text":"RPC"},"children":[]}""")
                        respond("Driver.getDisplaySize", """{"x":2720,"y":1260}""")
                    }
                }
                device.connect()
                assertThat(device.displaySize(budget)).isEqualTo(1260 to 2720)
                assertThat(device.hierarchy(budget).attributes["text"]).isEqualTo("RPC")
                assertThat(device.displaySize(budget)).isEqualTo(2720 to 1260)
                peer.get(5, TimeUnit.SECONDS)
            } finally {
                device.disconnect()
                executor.shutdownNow()
            }
        }
    }

    private inner class HdcFixture(private val port: Int, private val owner: String = deviceId) {
        var dumps = 0

        fun device(requireLayoutRpc: Boolean = false): HdcHarmonyOSDevice {
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
            return HdcHarmonyOSDevice(deviceId, HdcClient("hdc", runner), port, requireLayoutRpc = requireLayoutRpc)
        }
    }
}
