package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.cli.session.HarmonyOsSessionSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HarmonyOsAgentControllerTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `cancel is reachable during execution and handoff waits for actual worker and device lock release`() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val allowExit = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val backend = Backend { _, attempt ->
            // A cooperative local wait before dispatching any device command can be cancelled safely.
            worker.set(Thread.currentThread())
            entered.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) {
                interrupted.countDown()
                awaitUninterruptibly(allowExit)
                attempt.finishCancelled()
            }
        }
        val controller = controller(backend)
        try {
            val session = connect(controller)
            val ids = submit(controller, session)
            await(entered)
            assertThat(call(controller, "agent.session", sessionParams(session))["data"]["state"].asText()).isEqualTo("busy")
            val cancel = call(controller, "agent.cancel", mapOf("sessionId" to session, "attemptId" to ids.second))
            assertThat(cancel["data"]["acceptance"].asText()).isEqualTo("accepted")
            await(interrupted)
            assertThat(call(controller, "agent.cancel", mapOf("sessionId" to session, "attemptId" to ids.second))["data"]["acceptance"].asText())
                .isEqualTo("already_requested")
            val release = call(controller, "agent.release", sessionParams(session))["data"]
            assertThat(release["state"].asText()).isEqualTo("releasing")
            assertThat(release["handoffReady"].asBoolean()).isFalse()
            assertThat(HarmonyOsSessionSupport.probeDeviceLock("device-fixture", backend.locks))
                .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.BUSY)
            allowExit.countDown()
            val closed = awaitClosed(controller, session)
            assertThat(closed["handoffReady"].asBoolean()).isTrue()
            assertThat(closed["cleanup"]["state"].asText()).isEqualTo("complete")
            assertThat(worker.get().isAlive).isFalse()
            var acquired = false
            HarmonyOsSessionSupport.withDeviceLock("device-fixture", backend.locks) { acquired = true }
            assertThat(acquired).isTrue()
            assertThat(call(controller, "agent.result", mapOf("runId" to ids.first, "attemptId" to ids.second))["data"]["status"].asText())
                .isEqualTo("cancelled")
        } finally { allowExit.countDown(); controller.close() }
    }

    @Test
    fun `interrupted dispatched action preserves unknown remote cleanup even after host lock release`() {
        val entered = CountDownLatch(1)
        val backend = Backend { _, attempt ->
            attempt.markRunning()
            attempt.startStep("flow.yaml", 0, "tapOn", true)
            entered.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { attempt.finishCancelled() }
        }
        val controller = controller(backend)
        try {
            val session = connect(controller)
            val ids = submit(controller, session)
            await(entered)
            call(controller, "agent.cancel", mapOf("sessionId" to session, "attemptId" to ids.second))
            var uncertain: JsonNode? = null
            eventually {
                uncertain = call(controller, "agent.session", sessionParams(session))["data"]
                uncertain!!["state"].asText() == "unknown"
            }
            assertThat(uncertain!!["cleanup"]["state"].asText()).isEqualTo("unknown")
            assertThat(uncertain!!["lockState"].asText()).isEqualTo("held")
            assertThat(error(controller, "agent.action", actionParams(session, uuid(), uuid()))["code"].asText()).isEqualTo("PRECONDITION_UNKNOWN")
            call(controller, "agent.release", sessionParams(session))
            val closed = awaitClosed(controller, session)
            assertThat(closed["lockState"].asText()).isEqualTo("released")
            assertThat(closed["cleanup"]["state"].asText()).isEqualTo("unknown")
            assertThat(closed["handoffReady"].asBoolean()).isFalse()
            val result = call(controller, "agent.result", mapOf("runId" to ids.first, "attemptId" to ids.second))["data"]
            assertThat(result["effects"].asText()).isEqualTo("possible")
            assertThat(error(controller, "agent.connect", config())["code"].asText()).isEqualTo("DEVICE_BUSY")
            assertThat(controller.close()).isFalse()
        } finally { controller.close() }
    }

    @Test
    fun `executor swallowing interrupt cannot report clean handoff after accepting cancellation`() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val reportOutcome = CountDownLatch(1)
        val backend = Backend { _, attempt ->
            attempt.markRunning()
            entered.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { interrupted.countDown() }
            awaitUninterruptibly(reportOutcome)
            // Reproduce a backend that loses cancellation and claims an ordinary assertion failure.
            attempt.setCleanup("complete")
            attempt.finishFailure(AgentProtocolException(AgentError("assertion", "ASSERTION_FAILED", "fixture assertion after swallowed interrupt")))
        }
        val controller = controller(backend)
        try {
            val session = connect(controller)
            val ids = submit(controller, session)
            await(entered)
            val acknowledgement = call(controller, "agent.cancel", mapOf("sessionId" to session, "attemptId" to ids.second))
            assertThat(acknowledgement["data"]["acceptance"].asText()).isEqualTo("accepted")
            await(interrupted)
            val pending = call(controller, "agent.result", mapOf("runId" to ids.first, "attemptId" to ids.second))["data"]
            assertThat(pending["cleanup"]["state"].asText()).isEqualTo("unknown")
            call(controller, "agent.release", sessionParams(session))
            reportOutcome.countDown()
            val closed = awaitClosed(controller, session)
            assertThat(closed["lockState"].asText()).isEqualTo("released")
            assertThat(closed["cleanup"]["state"].asText()).isEqualTo("unknown")
            assertThat(closed["handoffReady"].asBoolean()).isFalse()
            assertThat(closed["cleanup"]["errors"].map { it["code"].asText() }).contains("CANCELLATION_NOT_CONFIRMED")
            val result = call(controller, "agent.result", mapOf("runId" to ids.first, "attemptId" to ids.second))["data"]
            assertThat(result["status"].asText()).isEqualTo("failed")
            assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
            assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
            assertThat(controller.close()).isFalse()
        } finally { reportOutcome.countDown(); controller.close() }
    }

    @Test
    fun `late cancel cannot interrupt the next attempt and duplicate submissions never dispatch`() {
        val nextEntered = CountDownLatch(1)
        val finishNext = CountDownLatch(1)
        val count = AtomicInteger()
        val backend = Backend { _, attempt ->
            attempt.markRunning()
            if (count.incrementAndGet() == 2) { nextEntered.countDown(); finishNext.await() }
            attempt.finishSuccess()
        }
        val controller = controller(backend)
        try {
            val session = connect(controller)
            val first = submit(controller, session)
            awaitIdle(controller, session)
            val duplicate = actionParams(session, first.first, first.second)
            assertThat(error(controller, "agent.action", duplicate)["code"].asText()).isEqualTo("ATTEMPT_ALREADY_EXISTS")
            val next = submit(controller, session)
            await(nextEntered)
            assertThat(call(controller, "agent.cancel", mapOf("sessionId" to session, "attemptId" to first.second))["data"]["acceptance"].asText())
                .isEqualTo("already_finished")
            assertThat(error(controller, "agent.cancel", mapOf("sessionId" to session, "attemptId" to uuid()))["code"].asText())
                .isEqualTo("STALE_ATTEMPT")
            assertThat(call(controller, "agent.session", sessionParams(session))["data"]["activeAttemptId"].asText()).isEqualTo(next.second)
            finishNext.countDown()
            awaitIdle(controller, session)
            assertThat(call(controller, "agent.result", mapOf("runId" to next.first, "attemptId" to next.second))["data"]["status"].asText())
                .isEqualTo("passed")
            assertThat(count.get()).isEqualTo(2)
        } finally { finishNext.countDown(); controller.close() }
    }

    @Test
    fun `snapshot does not block control and restores idle before delivering its response`() {
        val capturing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val backend = Backend { _, attempt -> attempt.finishSuccess() }.apply {
            capture = { capturing.countDown(); finish.await(); mapOf("snapshotId" to "fixture-snapshot") }
        }
        val controller = controller(backend)
        try {
            val session = connect(controller)
            val response = dispatch(controller, "agent.snapshot", sessionParams(session))
            await(capturing)
            assertThat(call(controller, "agent.session", sessionParams(session))["data"]["state"].asText()).isEqualTo("inspecting")
            assertThat(error(controller, "agent.action", actionParams(session, uuid(), uuid()))["code"].asText()).isEqualTo("DEVICE_BUSY")
            finish.countDown()
            assertThat(response.get(5, TimeUnit.SECONDS)["data"]["sessionId"].asText()).isEqualTo(session)
            submit(controller, session)
            awaitIdle(controller, session)
        } finally { finish.countDown(); controller.close() }
    }

    @Test
    fun `cleanup failure never reports handoff ready and release remains idempotent`() {
        val backend = Backend { _, attempt ->
            attempt.markRunning()
            attempt.finishFailure(AgentProtocolException(AgentError("assertion", "ASSERTION_FAILED", "fixture original assertion")))
        }.apply {
            closeFailure = IllegalStateException("fixture cleanup failure").also {
                it.addSuppressed(IllegalStateException("fixture secondary cleanup failure"))
            }
        }
        val controller = controller(backend)
        try {
            val session = connect(controller)
            val ids = submit(controller, session)
            awaitIdle(controller, session)
            call(controller, "agent.release", sessionParams(session))
            val closed = awaitClosed(controller, session)
            assertThat(closed["cleanup"]["state"].asText()).isEqualTo("failed")
            assertThat(closed["cleanup"]["errors"].map { it["message"].asText() })
                .containsExactly("fixture cleanup failure", "fixture secondary cleanup failure").inOrder()
            assertThat(closed["handoffReady"].asBoolean()).isFalse()
            val result = call(controller, "agent.result", mapOf("runId" to ids.first, "attemptId" to ids.second))["data"]
            assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
            assertThat(result["error"]["message"].asText()).isEqualTo("fixture original assertion")
            assertThat(call(controller, "agent.release", sessionParams(session))["data"]).isEqualTo(closed)
            assertThat(controller.close()).isFalse()
        } finally { controller.close() }
    }

    @Test
    fun `shutdown budget does not pretend an uncooperative worker has exited`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val backend = Backend { _, attempt -> entered.countDown(); awaitUninterruptibly(finish); attempt.finishCancelled() }
        val controller = HarmonyOsAgentController(backend, PrintStream(ByteArrayOutputStream()), shutdownWaitMs = 25)
        try {
            val session = connect(controller)
            submit(controller, session)
            await(entered)
            assertThat(controller.close()).isFalse()
            assertThat(backend.closed).isFalse()
        } finally { finish.countDown(); eventually { backend.closed }; controller.close() }
    }

    private inner class Backend(private val execute: (JsonNode, AgentAttempt) -> Unit) : RecorderBackend {
        val locks: Path = directory.resolve("locks")
        @Volatile var closed = false
        var closeFailure: Exception? = null
        var capture: () -> Any = { mapOf("snapshotId" to "fixture") }
        override fun devices() = listOf("device-fixture")
        override fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit) {
            HarmonyOsSessionSupport.withDeviceLock(config.deviceId, locks) {
                try {
                    block(object : AgentRecorderConnection {
                        override fun executeAgentAction(parameters: JsonNode, attempt: AgentAttempt) = execute(parameters, attempt)
                        override fun snapshot(): Any = capture()
                        override fun action(parameters: JsonNode): Any = error("Legacy action is not used by this fixture")
                    })
                } finally { closed = true; closeFailure?.let { throw it } }
            }
        }
    }

    private fun controller(backend: RecorderBackend) = HarmonyOsAgentController(backend, PrintStream(ByteArrayOutputStream()))
    private fun config(): Map<String, Any> = mapOf("deviceId" to "device-fixture", "appId" to "org.example.recorder",
        "abilityName" to "EntryAbility", "artifactsDir" to directory.resolve("artifacts").toString())
    private fun connect(controller: HarmonyOsAgentController) = call(controller, "agent.connect", config())["data"]["sessionId"].asText()
    private fun sessionParams(session: String) = mapOf("sessionId" to session)
    private fun submit(controller: HarmonyOsAgentController, session: String): Pair<String, String> {
        val ids = uuid() to uuid()
        val accepted = call(controller, "agent.action", actionParams(session, ids.first, ids.second))
        assertThat(accepted["data"]["status"].asText()).isEqualTo("pending")
        return ids
    }
    private fun actionParams(session: String, run: String, attempt: String) = mapOf("sessionId" to session,
        "runId" to run, "attemptId" to attempt, "attemptNo" to 1, "action" to mapOf("type" to "back"))
    private fun dispatch(controller: HarmonyOsAgentController, method: String, params: Map<String, Any>): CompletableFuture<JsonNode> {
        val future = CompletableFuture<JsonNode>()
        controller.dispatch(method, mapper.valueToTree(params), { future.complete(mapper.valueToTree(it)) }, { future.completeExceptionally(it) })
        return future
    }
    private fun call(controller: HarmonyOsAgentController, method: String, params: Map<String, Any>) = dispatch(controller, method, params).get(5, TimeUnit.SECONDS)
    private fun error(controller: HarmonyOsAgentController, method: String, params: Map<String, Any>): JsonNode {
        try { call(controller, method, params); throw AssertionError("Expected protocol error") }
        catch (failure: ExecutionException) { return mapper.valueToTree(HarmonyOsAgentProtocol.error(failure.cause!!).toMap()) }
    }
    private fun awaitClosed(controller: HarmonyOsAgentController, session: String): JsonNode {
        var data: JsonNode? = null
        eventually { data = call(controller, "agent.session", sessionParams(session))["data"]; data!!["state"].asText() == "closed" }
        return data!!
    }
    private fun awaitIdle(controller: HarmonyOsAgentController, session: String) = eventually {
        call(controller, "agent.session", sessionParams(session))["data"]["state"].asText() == "idle"
    }
    private fun await(latch: CountDownLatch) { check(latch.await(5, TimeUnit.SECONDS)) { "Fixture did not reach the expected phase" } }
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { /* Wait until the test explicitly permits exit. */ }
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out waiting for fixture" }; Thread.sleep(5) }
    }
    private fun uuid() = UUID.randomUUID().toString()
}
