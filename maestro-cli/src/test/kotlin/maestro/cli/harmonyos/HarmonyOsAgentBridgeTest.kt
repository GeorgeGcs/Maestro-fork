package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HarmonyOsAgentBridgeTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `JSONL reader processes session cancel and release while the action is still executing`() {
        val backend = Backend(waitForPermissionToExit = true)
        val bridge = Bridge(backend)
        try {
            val session = bridge.exchange("agent.connect", config())["result"]["data"]["sessionId"].asText()
            val attempt = UUID.randomUUID().toString()
            val actionId = bridge.send("agent.action", actionParams(session, attempt))
            assertThat(bridge.response(actionId)["result"]["data"]["status"].asText()).isEqualTo("pending")
            await(backend.executing)
            assertThat(bridge.exchange("agent.session", mapOf("sessionId" to session))["result"]["data"]["state"].asText()).isEqualTo("busy")
            assertThat(bridge.exchange("connect", config())["error"]["code"].asText()).isEqualTo("DEVICE_BUSY")
            bridge.send("agent.action", actionParams(session, attempt), actionId)
            assertThat(bridge.response(actionId)["error"]["code"].asText()).isEqualTo("INVALID_REQUEST")
            val cancel = bridge.exchange("agent.cancel", mapOf("sessionId" to session, "attemptId" to attempt))
            assertThat(cancel["result"]["data"]["acceptance"].asText()).isEqualTo("accepted")
            await(backend.interrupted)
            val releasing = bridge.exchange("agent.release", mapOf("sessionId" to session))["result"]["data"]
            assertThat(releasing["state"].asText()).isEqualTo("releasing")
            assertThat(releasing["handoffReady"].asBoolean()).isFalse()
            assertThat(backend.closed).isFalse()
            backend.allowExit.countDown()
            var final: JsonNode? = null
            eventually {
                final = bridge.exchange("agent.session", mapOf("sessionId" to session))["result"]["data"]
                final!!["state"].asText() == "closed"
            }
            assertThat(final!!["handoffReady"].asBoolean()).isTrue()
            assertThat(backend.actions.get()).isEqualTo(1)
        } finally { backend.allowExit.countDown(); bridge.close() }
    }

    @Test
    fun `EOF cancels and joins only the bridge owned execution`() {
        val backend = Backend()
        val bridge = Bridge(backend)
        try {
            val session = bridge.exchange("agent.connect", config())["result"]["data"]["sessionId"].asText()
            bridge.exchange("agent.action", actionParams(session, UUID.randomUUID().toString()))
            await(backend.executing)
            bridge.closeInput()
            await(backend.interrupted)
            bridge.awaitExit()
            assertThat(backend.closed).isTrue()
            assertThat(bridge.exitCode.get()).isEqualTo(0)
        } finally { bridge.close() }
    }

    @Test
    fun `output failure stops the active worker even if the client leaves input open`() {
        val backend = Backend()
        val bridge = Bridge(backend)
        try {
            val session = bridge.exchange("agent.connect", config())["result"]["data"]["sessionId"].asText()
            bridge.exchange("agent.action", actionParams(session, UUID.randomUUID().toString()))
            await(backend.executing)
            bridge.output.failWrites = true
            bridge.send("agent.session", mapOf("sessionId" to session))
            await(backend.interrupted)
            bridge.awaitExit()
            assertThat(backend.closed).isTrue()
        } finally { bridge.close() }
    }

    @Test
    fun `legacy session cannot be replaced by agent connect`() {
        val backend = Backend()
        val bridge = Bridge(backend)
        try {
            bridge.exchange("connect", config())
            val denied = bridge.exchange("agent.connect", config())["error"]
            assertThat(denied["kind"].asText()).isEqualTo("environment")
            assertThat(denied["code"].asText()).isEqualTo("DEVICE_BUSY")
            assertThat(bridge.exchange("disconnect")["result"]["disconnected"].asBoolean()).isTrue()
            assertThat(backend.connects.get()).isEqualTo(1)
        } finally { bridge.close() }
    }

    @Test
    fun `duplicate keys trailing JSON unsafe ids and reused ids never dispatch operations`() {
        val source = listOf(
            "{\"id\":1,\"method\":\"devices\",\"method\":\"agent.connect\",\"params\":{}}",
            "{\"id\":2,\"method\":\"devices\"} {}",
            "{\"id\":9007199254740992,\"method\":\"agent.describe\"}",
            "{\"id\":3,\"method\":\"agent.describe\"}",
            "{\"id\":3,\"method\":\"agent.describe\"}",
        ).joinToString("\n") + "\n"
        val output = ByteArrayOutputStream()
        val backend = Backend()
        HarmonyOsBridgeServer(source.byteInputStream(), output, PrintStream(ByteArrayOutputStream()), backend).run()
        val responses = output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.map(mapper::readTree).toList()
        assertThat(responses).hasSize(5)
        assertThat(responses.map { it.path("error").path("code").asText() })
            .containsExactly("INVALID_REQUEST", "INVALID_REQUEST", "INVALID_REQUEST", "", "INVALID_REQUEST").inOrder()
        assertThat(backend.connects.get()).isEqualTo(0)
        assertThat(backend.discovery.get()).isEqualTo(0)
    }

    private class Backend(waitForPermissionToExit: Boolean = false) : RecorderBackend {
        val executing = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val allowExit = CountDownLatch(if (waitForPermissionToExit) 1 else 0)
        val actions = AtomicInteger()
        val connects = AtomicInteger()
        val discovery = AtomicInteger()
        @Volatile var closed = false
        override fun devices(): List<String> { discovery.incrementAndGet(); return listOf("device-fixture") }
        override fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit) {
            connects.incrementAndGet()
            try {
                block(object : AgentRecorderConnection {
                    override fun snapshot(): Any = mapOf("snapshotId" to "fixture")
                    override fun action(parameters: JsonNode): Any = mapOf("yaml" to "- back")
                    override fun executeAgentAction(parameters: JsonNode, attempt: AgentAttempt) {
                        actions.incrementAndGet()
                        executing.countDown()
                        try { CountDownLatch(1).await() } catch (_: InterruptedException) { interrupted.countDown() }
                        while (true) try { allowExit.await(); break } catch (_: InterruptedException) { /* Controlled fixture cleanup. */ }
                        attempt.finishCancelled()
                    }
                })
            } finally { closed = true }
        }
    }

    private inner class Bridge(backend: RecorderBackend) : AutoCloseable {
        private val requests = PipedOutputStream()
        private val input = PipedInputStream(requests, 128 * 1024)
        val output = Responses()
        val exitCode = AtomicInteger(-1)
        private var nextId = 1
        private val server = Thread({ exitCode.set(HarmonyOsBridgeServer(input, output,
            PrintStream(ByteArrayOutputStream()), backend).run()) }, "agent-bridge-test").apply { isDaemon = true; start() }
        private val pending = mutableMapOf<Int, JsonNode>()
        fun send(method: String, params: Map<String, Any> = emptyMap(), id: Int = nextId++): Int {
            requests.write((mapper.writeValueAsString(mapOf("id" to id, "method" to method, "params" to params)) + "\n").toByteArray())
            requests.flush()
            return id
        }
        fun exchange(method: String, params: Map<String, Any> = emptyMap()) = response(send(method, params))
        fun response(id: Int): JsonNode {
            pending.remove(id)?.let { return it }
            while (true) {
                val response = output.responses.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("Missing bridge response for $id")
                if (response["id"].asInt() == id) return response
                pending[response["id"].asInt()] = response
            }
        }
        fun closeInput() { requests.close() }
        fun awaitExit() { server.join(6_000); check(!server.isAlive) { "Bridge did not close its worker" } }
        override fun close() { runCatching { requests.close() }; awaitExit() }
    }

    private inner class Responses : OutputStream() {
        val responses = LinkedBlockingQueue<JsonNode>()
        private val line = ByteArrayOutputStream()
        @Volatile var failWrites = false
        @Synchronized override fun write(value: Int) {
            if (failWrites) throw IOException("fixture output disconnected")
            if (value == '\n'.code) { responses.add(mapper.readTree(line.toByteArray())); line.reset() }
            else line.write(value)
        }
        @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failWrites) throw IOException("fixture output disconnected")
            line.write(bytes, offset, length)
        }
    }

    private fun config(): Map<String, Any> = mapOf("deviceId" to "device-fixture", "appId" to "org.example.recorder",
        "abilityName" to "EntryAbility", "artifactsDir" to directory.resolve("artifacts").toString())
    private fun actionParams(session: String, attempt: String) = mapOf("sessionId" to session,
        "runId" to UUID.randomUUID().toString(), "attemptId" to attempt, "attemptNo" to 1, "action" to mapOf("type" to "back"))
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) { "Expected worker phase was not reached" } }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < deadline) { "Fixture did not reach terminal state" }; Thread.sleep(5) }
    }
}
