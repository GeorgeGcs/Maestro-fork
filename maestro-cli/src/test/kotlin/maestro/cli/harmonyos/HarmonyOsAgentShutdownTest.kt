package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.hdc.HdcException
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HarmonyOsAgentShutdownTest {
    private val mapper = jacksonObjectMapper()
    private val params = mapper.readTree("""{"deviceId":"fixture-target","commands":["launchApp"]}""")
    private val diagnostics = PrintStream(ByteArrayOutputStream())

    @Test
    fun `EOF drains every accepted query`() = assertDrained(newline = true)

    @Test
    fun `EOF drains a final line without newline`() = assertDrained(newline = false)

    private fun assertDrained(newline: Boolean) {
        val calls = AtomicInteger()
        val capabilities = capabilities { calls.incrementAndGet(); throw HdcException("fixture discovery failure") }
        val source = (1..3).joinToString("\n") { id ->
            mapper.writeValueAsString(mapOf("id" to id, "method" to "agent.preflight", "params" to params))
        } + if (newline) "\n" else ""
        val output = ByteArrayOutputStream()
        assertThat(HarmonyOsBridgeServer(source.byteInputStream(), output, diagnostics, Backend(), capabilities).run()).isEqualTo(0)
        val responses = output.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map(mapper::readTree).toList()
        assertThat(responses.map { it["id"].asInt() }).containsExactly(1, 2, 3).inOrder()
        assertThat(calls.get()).isEqualTo(3)
        responses.forEach { response ->
            assertThat(response.has("error")).isFalse()
            val data = response["result"]["data"]
            assertThat(data["readiness"].asText()).isEqualTo("blocked")
            val device = data["checks"].single { it["name"].asText() == "device" }
            assertThat(device["status"].asText()).isEqualTo("unknown")
            assertThat(device["code"].asText()).isEqualTo("HDC_DISCOVERY_FAILED")
        }
    }

    @Test
    fun `forced shutdown retains cancellation of a running environment query`() {
        val entered = CountDownLatch(1)
        val controller = controller { entered.countDown(); CountDownLatch(1).await(); emptyList() }
        try {
            val error = preflightError(controller)
            await(entered)
            controller.requestShutdown()
            assertCancelled(error)
            assertThat(controller.close()).isTrue()
        } finally { controller.close() }
    }

    @Test
    fun `drain deadline returns incomplete even when interruption promptly stops the query`() {
        val entered = CountDownLatch(1)
        val controller = controller(25) { entered.countDown(); CountDownLatch(1).await(); emptyList() }
        try {
            val error = preflightError(controller)
            await(entered)
            val started = System.nanoTime()
            assertThat(controller.close(drainQueries = true)).isFalse()
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000L)
            assertCancelled(error)
        } finally { controller.close() }
    }

    @Test
    fun `output failure still aborts a graceful drain and cannot hide a discarded query`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val controller = controller {
            calls.incrementAndGet(); entered.countDown(); await(release); emptyList()
        }
        val closeResult = CompletableFuture<Boolean>()
        val closer = Thread({ closeResult.complete(controller.close(drainQueries = true)) }, "agent-drain-test")
        try {
            controller.dispatch("agent.preflight", params, { throw IOException("fixture output failure") }, { })
            await(entered)
            val queued = CompletableFuture<Any>()
            controller.dispatch("agent.preflight", params, { queued.complete(it) }, { queued.completeExceptionally(it) })
            closer.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                var stopped = false
                controller.dispatch("agent.describe", mapper.createObjectNode(), { },
                    { stopped = HarmonyOsAgentProtocol.error(it).code == "DEVICE_BUSY" })
                if (stopped) break
                check(System.nanoTime() < deadline) { "Drain did not stop accepting requests" }
                Thread.sleep(1)
            }
            release.countDown()
            assertThat(closeResult.get(5, TimeUnit.SECONDS)).isFalse()
            assertThat(calls.get()).isEqualTo(1)
            assertThat(queued.isDone).isFalse()
        } finally { release.countDown(); controller.close(); closer.join(5_000) }
    }

    @Test
    fun `forced shutdown cannot report success after discarding an accepted recovery`() {
        val entered = CountDownLatch(1)
        val backend = Backend()
        val controller = HarmonyOsAgentController(backend, diagnostics,
            capabilities { entered.countDown(); CountDownLatch(1).await(); emptyList() })
        try {
            controller.dispatch("agent.describe", mapper.readTree("""{"schemaVersion":2}"""), { }, { throw it })
            val error = preflightError(controller)
            await(entered)
            val recovery = CompletableFuture<Any>()
            val request = mapper.valueToTree<JsonNode>(mapOf("deviceId" to "fixture-target", "sessionId" to UUID.randomUUID().toString()))
            controller.dispatch("agent.recover", request, { recovery.complete(it) }, { recovery.completeExceptionally(it) })
            assertThat(recovery.isDone).isFalse()
            controller.requestShutdown()
            assertCancelled(error)
            assertThat(controller.close()).isFalse()
            assertThat(backend.recoveries.get()).isEqualTo(0)
            assertThat(recovery.isDone).isFalse()
        } finally { controller.close() }
    }

    private fun controller(waitMs: Long = 5_000, discover: () -> List<String>) =
        HarmonyOsAgentController(Backend(), diagnostics, capabilities(discover), waitMs)

    private fun capabilities(discover: () -> List<String>) = HarmonyOsAgentCapabilities(object : AgentEnvironmentProbe {
        override fun devices() = discover()
        override fun lockStatus(deviceId: String): HarmonyOsSessionSupport.DeviceLockStatus = error("No target was found")
        override fun execute(deviceId: String, arguments: List<String>): String = error("No device command is permitted")
    })

    private fun preflightError(controller: HarmonyOsAgentController): CompletableFuture<Exception> {
        val result = CompletableFuture<Exception>()
        controller.dispatch("agent.preflight", params,
            { result.completeExceptionally(AssertionError("Expected interruption, got $it")) }, { result.complete(it) })
        return result
    }

    private fun assertCancelled(result: CompletableFuture<Exception>) {
        val error = HarmonyOsAgentProtocol.error(result.get(5, TimeUnit.SECONDS))
        assertThat(error.kind).isEqualTo("cancelled")
        assertThat(error.code).isEqualTo("CANCELLED")
    }

    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) { "Fixture phase was not reached" } }

    private class Backend : RecorderBackend {
        val recoveries = AtomicInteger()
        override fun devices(): List<String> = error("Legacy discovery is not permitted")
        override fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit) = error("No device session is permitted")
        override fun recover(deviceId: String, sessionId: String): Map<String, Any?> {
            recoveries.incrementAndGet()
            error("Queued recovery must not start after forced shutdown")
        }
    }
}
