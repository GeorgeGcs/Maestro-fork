package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class HarmonyOsBridgeServerTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `discovery works without a connection and explicit disconnect is acknowledged after release`() {
        val backend = Backend()
        val result = run(backend, listOf(request(1, "devices"), connect(2), request(3, "disconnect"), request(4, "disconnect")))
        assertThat(result[0]["result"]["devices"][0]["id"].asText()).isEqualTo("device-fixture")
        assertThat(result[1]["result"]["appId"].asText()).isEqualTo("org.example.recorder")
        assertThat(result[2]["result"]["disconnected"].asBoolean()).isTrue()
        assertThat(backend.closed).isTrue()
        assertThat(backend.connects).isEqualTo(1)
    }

    @Test
    fun `EOF releases the active session without requiring disconnect`() {
        val backend = Backend()
        run(backend, listOf(connect(1), request(2, "snapshot")))
        assertThat(backend.closed).isTrue()
        assertThat(backend.snapshots).isEqualTo(1)
    }

    @Test
    fun `failed actions return no yaml and do not retry or prevent a later disconnect`() {
        val backend = Backend().apply { actionFailure = RecorderError("STALE_SNAPSHOT", "fixture changed") }
        val response = run(backend, listOf(connect(1), request(2, "action", mapOf("type" to "back")), request(3, "disconnect")))
        assertThat(response[1]["error"]["code"].asText()).isEqualTo("STALE_SNAPSHOT")
        assertThat(response[1].has("result")).isFalse()
        assertThat(backend.actions).isEqualTo(1)
        assertThat(backend.closed).isTrue()
    }

    @Test
    fun `malformed and oversized requests cannot connect and following valid requests still work`() {
        val backend = Backend()
        val source = listOf("{", "x".repeat(HarmonyOsBridgeServer.MAX_REQUEST_BYTES + 1),
            request(-1, "connect"), request(5, "devices"))
        val response = run(backend, source)
        assertThat(response.map { it.path("error").path("code").asText() })
            .containsExactly("INVALID_REQUEST", "REQUEST_TOO_LARGE", "INVALID_REQUEST", "").inOrder()
        assertThat(response.last()["id"].asInt()).isEqualTo(5)
        assertThat(backend.connects).isEqualTo(0)
    }

    @Test
    fun `invalid connect parameters and commands outside session do not touch backend`() {
        val backend = Backend()
        val response = run(backend, listOf(
            request(1, "connect", config() + ("deviceId" to "a,b")),
            request(2, "connect", config() + ("artifactsDir" to "relative")),
            request(3, "connect", config() + ("appId" to "\${evil}")),
            request(4, "snapshot"), request(5, "action", mapOf("type" to "back")),
            request(6, "devices", mapOf("unexpected" to true)),
        ))
        assertThat(response.all { it.has("error") }).isTrue()
        assertThat(backend.connects).isEqualTo(0)
        assertThat(backend.discoveryCalls).isEqualTo(0)
    }

    @Test
    fun `a connection cannot be replaced while in use and another connection can follow disconnect`() {
        val backend = Backend()
        val response = run(backend, listOf(connect(1), connect(2), request(3, "disconnect"), connect(4)))
        assertThat(response[1]["error"]["code"].asText()).isEqualTo("ALREADY_CONNECTED")
        assertThat(backend.connects).isEqualTo(2)
        assertThat(backend.closed).isTrue()
    }

    @Test
    fun `close failure belongs to the disconnect response not to the already acknowledged connect`() {
        val backend = Backend().apply { closeFailure = IllegalStateException("fixture close failure") }
        val response = run(backend, listOf(connect(1), request(2, "disconnect")))
        assertThat(response).hasSize(2)
        assertThat(response[0].has("result")).isTrue()
        assertThat(response[1]["id"].asInt()).isEqualTo(2)
        assertThat(response[1]["error"]["message"].asText()).contains("fixture close failure")
    }

    private fun run(backend: Backend, requests: List<String>): List<JsonNode> {
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val text = String(bytes, offset, length, Charsets.UTF_8)
                if (text.contains("\"disconnected\":true")) assertThat(backend.closed || backend.connects == 0).isTrue()
                super.write(bytes, offset, length)
            }
        }
        val diagnostics = ByteArrayOutputStream()
        assertThat(HarmonyOsBridgeServer((requests.joinToString("\n") + "\n").byteInputStream(), output,
            PrintStream(diagnostics), backend).run()).isEqualTo(0)
        return output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.map(mapper::readTree).toList()
    }

    private fun config() = mapOf("deviceId" to "device-fixture", "appId" to "org.example.recorder",
        "abilityName" to "EntryAbility", "artifactsDir" to directory.toString())
    private fun connect(id: Int) = request(id, "connect", config())
    private fun request(id: Int, method: String, params: Map<String, Any> = emptyMap()) =
        mapper.writeValueAsString(mapOf("id" to id, "method" to method, "params" to params))

    private class Backend : RecorderBackend {
        var closed = false
        var connects = 0
        var discoveryCalls = 0
        var actions = 0
        var snapshots = 0
        var actionFailure: Exception? = null
        var closeFailure: Exception? = null
        override fun devices(): List<String> { discoveryCalls++; return listOf("device-fixture") }
        override fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit) {
            connects++
            closed = false
            try {
                block(object : RecorderConnection {
                    override fun snapshot(): Any { snapshots++; return mapOf("snapshotId" to "fixture") }
                    override fun action(parameters: JsonNode): Any {
                        actions++
                        actionFailure?.let { throw it }
                        return mapOf("yaml" to "- back", "artifactsDir" to config.artifactsDir.toString())
                    }
                })
            } finally {
                closed = true
                closeFailure?.let { throw it }
            }
        }
    }
}
