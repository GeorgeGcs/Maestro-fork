package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.TreeNode
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.driver.HarmonyOSCommandException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HarmonyOsAgentAttemptStoreTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper()

    @Test fun `native junit is hashed in the finalized artifact manifest`() {
        val attempt = register()
        val report = attempt.directory.resolve("junit.xml")
        Files.writeString(report, "<testsuites><testsuite tests=\"1\" failures=\"0\"/></testsuites>")
        attempt.setJUnit(report)
        attempt.finishSuccess()
        val manifest = mapper.readTree(attempt.directory.resolve("agent-manifest.json").toFile())
        val entry = manifest["files"].single { it["path"].asText() == "junit.xml" }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(report))
            .joinToString("") { "%02x".format(it) }
        assertThat(entry["sha256"].asText()).isEqualTo(expected)
        assertThat(json(attempt.result())["data"]["artifacts"]["junitPath"].asText()).isEqualTo("junit.xml")
    }

    @Test fun `canonical registration rejects duplicate across store instances without replaying`() {
        val run = uuid()
        val id = uuid()
        val first = register(run, id, input = "{\"type\":\"inputText\",\"text\":\"hello\"}")
        first.finishSuccess()
        val duplicate = assertThrows<AgentProtocolException> {
            register(run, id, input = "{\"text\":\"hello\",\"type\":\"inputText\"}")
        }
        assertThat(duplicate.error.code).isEqualTo("ATTEMPT_ALREADY_EXISTS")
        assertThat(assertThrows<AgentProtocolException> {
            register(run, id, input = "{\"type\":\"back\"}")
        }.error.code).isEqualTo("ATTEMPT_CONFLICT")
        assertThat(json(HarmonyOsAgentAttemptStore(root).result(run, id))["data"]["status"].asText()).isEqualTo("passed")
    }

    @Test fun `concurrent identical submissions have one durable owner`() {
        val run = uuid()
        val id = uuid()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes = executor.invokeAll(List(2) { Callable {
                try { register(run, id).also { it.finishSuccess() }; "accepted" }
                catch (error: AgentProtocolException) { error.error.code }
            } }).map { it.get() }
            assertThat(outcomes).containsExactly("accepted", "ATTEMPT_ALREADY_EXISTS")
        } finally { executor.shutdownNow() }
    }

    @Test fun `attempt id cannot be replayed by changing run id`() {
        val id = uuid()
        register(attemptId = id).finishSuccess()
        assertThat(assertThrows<AgentProtocolException> { register(attemptId = id) }.error.code).isEqualTo("ATTEMPT_CONFLICT")
    }

    @Test fun `retries get isolated files and increasing numbers while keeping run input`() {
        val run = uuid()
        val first = register(run)
        first.finishFailure(IllegalArgumentException("bad request"))
        assertThat(assertThrows<AgentProtocolException> { register(run) }.error.code).isEqualTo("STALE_ATTEMPT")
        val next = register(run, attemptNo = 2)
        assertThat(next.directory).isNotEqualTo(first.directory)
        assertThat(json(next.result())["data"]["flow"].isNull).isTrue()
        next.finishCancelled()
    }

    @Test fun `lost executor is recovered unknown and is never replayed`() {
        val run = uuid()
        val id = uuid()
        val attempt = register(run, id)
        attempt.startStep("flow.yaml", 1, "InputTextCommand", true)
        attempt.closeOwner() // Simulate process ownership ending before the terminal write.
        val recovered = json(HarmonyOsAgentAttemptStore(root).result(run, id))["data"]
        assertThat(recovered["status"].asText()).isEqualTo("unknown")
        assertThat(recovered["error"]["code"].asText()).isEqualTo("EXECUTOR_LOST")
        assertThat(recovered["error"]["kind"].asText()).isEqualTo("transport")
        assertThat(recovered["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(recovered["effects"].asText()).isEqualTo("possible")
        assertThat(assertThrows<AgentProtocolException> { register(run, id) }.error.code).isEqualTo("ATTEMPT_ALREADY_EXISTS")
    }

    @Test fun `blocked validation has no flow execution effects or failed step`() {
        val attempt = register()
        attempt.finishFailure(AgentProtocolException(AgentError("environment", "DEVICE_LOCKED", "Unlock the device.")))
        val data = json(attempt.result())["data"]
        assertThat(data["status"].asText()).isEqualTo("blocked")
        assertThat(data["startedAt"].isNull).isTrue()
        assertThat(data["failedStepId"].isNull).isTrue()
        assertThat(data["flow"].isNull).isTrue()
        assertThat(data["effects"].asText()).isEqualTo("none")
    }

    @Test fun `interrupted work persists cancellation and uncertainty without clearing thread signal`() {
        val attempt = register()
        attempt.startStep("flow.yaml", 1, "TapOnElementCommand", true)
        assertThat(attempt.requestCancel()).isTrue()
        assertThat(attempt.requestCancel()).isFalse()
        Thread.currentThread().interrupt()
        try {
            attempt.finishFailure(InterruptedException("worker interrupted"))
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
        val data = json(attempt.result())["data"]
        assertThat(data["status"].asText()).isEqualTo("cancelled")
        assertThat(data["effects"].asText()).isEqualTo("possible")
        assertThat(data["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(mapper.readTree(attempt.directory.resolve("result.json").toFile())["data"]["status"].asText()).isEqualTo("cancelled")
    }

    @Test fun `interrupts arriving throughout terminal publication cannot leave a partial checkpoint`() {
        repeat(3) {
            val attempt = register()
            attempt.startStep("flow.yaml", 0, "InputTextCommand", true)
            attempt.requestCancel()
            val started = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            val preservedInterrupt = AtomicBoolean()
            val worker = Thread {
                started.countDown()
                try {
                    // A long diagnostic keeps the publication window open while interruption races it.
                    attempt.finishWithError(AgentError("cancelled", "CANCELLED", "cancellation diagnostic ".repeat(10_000)))
                } catch (failure: Throwable) { error.set(failure) }
                finally { preservedInterrupt.set(Thread.currentThread().isInterrupted); Thread.interrupted() }
            }.apply { isDaemon = true; start() }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (worker.isAlive && System.nanoTime() < deadline) {
                worker.interrupt()
                Thread.yield()
            }
            assertThat(worker.isAlive).isFalse()
            assertThat(error.get()).isNull()
            assertThat(preservedInterrupt.get()).isTrue()
            val state = mapper.readTree(attempt.directory.resolve("state.json").toFile())
            val result = mapper.readTree(attempt.directory.resolve("result.json").toFile())
            val evidence = mapper.readTree(attempt.directory.resolve("evidence-index.json").toFile())
            assertThat(result["data"]["status"].asText()).isEqualTo("cancelled")
            assertThat(state["result"]).isEqualTo(result)
            assertThat(state["evidence"]).isEqualTo(evidence)
            Files.list(attempt.directory).use { paths -> assertThat(paths.anyMatch { it.fileName.toString().endsWith(".pending") }).isFalse() }
        }
    }

    @Test fun `timeout after dispatch remains unknown and original assertion classification survives capture failure`() {
        val timeout = register()
        timeout.startStep("flow.yaml", 0, "InputTextCommand", true)
        timeout.finishFailure(HdcTimeoutException(Duration.ofMillis(10)))
        assertThat(json(timeout.result())["data"]["status"].asText()).isEqualTo("unknown")
        val attempt = register()
        val step = attempt.startStep("flow.yaml", 2, "AssertConditionCommand", false)
        val assertion = MaestroException.AssertionFailure("label missing", TreeNode(), "no label")
        val classified = HarmonyOsAgentProtocol.error(assertion)
        attempt.finishStep(step, "failed", classified)
        attempt.recordUnavailable(step, "screenshot", "capture_failed", "failure", AgentError("transport", "DEVICE_DISCONNECTED", "capture failed"))
        attempt.recordUnavailable(step, "hierarchy", "missing", "failure")
        attempt.finishFailure(assertion)
        val result = json(attempt.result())["data"]
        assertThat(result["error"]["kind"].asText()).isEqualTo("assertion")
        assertThat(result["failedStepId"].asText()).isEqualTo(step)
        val evidence = json(attempt.evidence())["data"]["entries"][0]["evidence"]
        assertThat(evidence[0]["state"].asText()).isEqualTo("capture_failed")
        assertThat(evidence[1]["state"].asText()).isEqualTo("missing")
    }

    @Test fun `evidence uses captured bytes and time rejects escaping path and keeps original input readonly`() {
        val attempt = register()
        val source = root.resolve("source.yaml")
        Files.writeString(source, "appId: fixture\n---\n- back\n")
        attempt.setFlow(source)
        val step = attempt.startStep("flow.yaml", 0, "BackPressCommand", true)
        val image = attempt.directory.resolve("capture.png")
        Files.write(image, byteArrayOf(1, 2, 3))
        val time = Instant.parse("2026-10-29T00:00:00Z")
        attempt.recordFile(step, "screenshot", image, "before", time)
        assertThrows<IllegalArgumentException> { attempt.recordFile(step, "log", source, "failure") }
        attempt.finishStep(step, "passed")
        attempt.finishSuccess()
        val evidence = json(attempt.evidence())["data"]["entries"][0]["evidence"][0]
        assertThat(evidence["sha256"].asText()).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
        assertThat(evidence["capturedAt"].asText()).isEqualTo(time.toString())
        assertThat(Files.readString(source)).isEqualTo("appId: fixture\n---\n- back\n")
        assertThat(attempt.requestCancel()).isFalse()
        assertThat(json(attempt.result())["data"]["status"].asText()).isEqualTo("passed")
    }

    @Test fun `shared failure logs have unique immutable paths and retain every owner`() {
        val attempt = register()
        val first = attempt.startStep("flow.yaml", 0, "First", false)
        val second = attempt.startStep("flow.yaml", 1, "Second", false)
        val log = attempt.directory.resolve("maestro.log")
        Files.writeString(log, "original failure diagnostics")
        attempt.recordFile(null, "log", log, "after")
        for (step in listOf(first, second)) {
            attempt.recordFile(step, "log", log, "failure")
            attempt.recordFile(step, "log", log, "failure")
        }
        val entries = json(attempt.evidence())["data"]["entries"].toList()
        val evidence = entries.flatMap { it["evidence"].toList() }
        assertThat(evidence).hasSize(3)
        assertThat(evidence.map { it["path"].asText() }.toSet()).hasSize(3)
        assertThat(entries.mapNotNull { it["stepId"].takeUnless { id -> id.isNull }?.asText() })
            .containsExactly(first, second)
        for (item in evidence) {
            assertThat(Files.readString(attempt.directory.resolve(item["path"].asText())))
                .isEqualTo("original failure diagnostics")
            assertThat(item["sha256"].asText()).isEqualTo(evidence[0]["sha256"].asText())
        }
        assertThat(Files.readString(log)).isEqualTo("original failure diagnostics")
    }

    @Test fun `a tampered shared reference is refused without overwriting bytes`() {
        val attempt = register()
        val step = attempt.startStep("flow.yaml", 0, "First", false)
        val log = attempt.directory.resolve("maestro.log")
        Files.writeString(log, "original")
        attempt.recordFile(null, "log", log, "after")
        attempt.recordFile(step, "log", log, "failure")
        val entry = json(attempt.evidence())["data"]["entries"].single { it["stepId"].asText() == step }
        val alias = attempt.directory.resolve(entry["evidence"][0]["path"].asText())
        Files.writeString(alias, "tampered")
        assertThrows<IllegalArgumentException> { attempt.recordFile(step, "log", log, "failure") }
        assertThat(Files.readString(alias)).isEqualTo("tampered")
        assertThat(Files.readString(log)).isEqualTo("original")
    }

    @Test fun `successful retry clears failure summary while preserving each step occurrence`() {
        val attempt = register()
        val one = attempt.startStep("flow.yaml", 3, "AssertConditionCommand", false)
        attempt.finishStep(one, "failed", AgentError("assertion", "ASSERTION_FAILED", "first check failed"))
        val two = attempt.startStep("flow.yaml", 3, "AssertConditionCommand", false)
        attempt.finishStep(two, "passed")
        attempt.finishSuccess()
        assertThat(one).isNotEqualTo(two)
        assertThat(json(attempt.result())["data"]["failedStepId"].isNull).isTrue()
        assertThat(json(attempt.evidence())["data"]["entries"].map { it["source"]["commandIndex"].asInt() }).containsExactly(3, 3)
    }

    @Test fun `known unknown cleanup merges suppressed errors without losing or duplicating its recovery owner`() {
        val attempt = register()
        attempt.startStep("flow.yaml", 0, "AssertConditionCommand", false)
        val owner = uuid()
        val recovery = AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "recording cleanup unconfirmed",
            mapOf("recoverySessionId" to owner))
        attempt.setCleanup("unknown", listOf(recovery))
        val assertion = MaestroException.AssertionFailure("body assertion", TreeNode(), "fixture")
        assertion.addSuppressed(AgentProtocolException(recovery))
        assertion.addSuppressed(java.io.IOException("recording flush failed"))

        attempt.finishFailure(assertion)

        val result = json(attempt.result())["data"]
        assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(result["cleanup"]["errors"].size()).isEqualTo(2)
        val retained = result["cleanup"]["errors"].single { it["code"].asText() == "DEVICE_RECOVERY_REQUIRED" }
        assertThat(retained["details"]["recoverySessionId"].asText()).isEqualTo(owner)
        assertThat(mapper.readTree(attempt.directory.resolve("result.json").toFile())["data"]).isEqualTo(result)
        assertThat(mapper.readTree(attempt.directory.resolve("state.json").toFile())["result"]["data"]).isEqualTo(result)
    }

    @Test fun `cancel timeout and transport terminal paths retain existing unknown cleanup diagnostics`() {
        for (kind in listOf("cancelled", "timeout", "transport")) {
            for (fromThrowable in listOf(false, true)) {
                val attempt = register()
                attempt.startStep("flow.yaml", 0, "InputTextCommand", true)
                val owner = uuid()
                val recovery = AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "cleanup needs recovery",
                    mapOf("recoverySessionId" to owner))
                attempt.setCleanup("unknown", listOf(recovery))
                val failure = AgentError(kind, "FIXTURE_FAILURE", "terminal $kind")

                if (fromThrowable) attempt.finishFailure(AgentProtocolException(failure))
                else attempt.finishWithError(failure)

                val result = json(attempt.result())["data"]
                assertThat(result["status"].asText()).isEqualTo(if (kind == "cancelled") "cancelled" else "unknown")
                assertThat(result["error"]["kind"].asText()).isEqualTo(kind)
                assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
                assertThat(result["cleanup"]["errors"].size()).isEqualTo(1)
                assertThat(result["cleanup"]["errors"][0]["details"]["recoverySessionId"].asText()).isEqualTo(owner)
            }
        }
    }

    @Test fun `ordinary assertion cleanup remains complete and unmarked suppressed cleanup remains failed`() {
        for (suppressed in listOf(false, true)) {
            val attempt = register()
            attempt.startStep("flow.yaml", 0, "AssertConditionCommand", false)
            val failure = MaestroException.AssertionFailure("body assertion", TreeNode(), "fixture")
            if (suppressed) failure.addSuppressed(java.io.IOException("recording close failed"))

            attempt.finishFailure(failure)

            val result = json(attempt.result())["data"]
            assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
            assertThat(result["cleanup"]["state"].asText()).isEqualTo(if (suppressed) "failed" else "complete")
            assertThat(result["cleanup"]["errors"].size()).isEqualTo(if (suppressed) 1 else 0)
        }
    }

    @Test fun `confirmed session cleanup preserves execution failure and earlier cleanup diagnostics`() {
        for (kind in listOf("timeout", "transport", "assertion")) {
            val attempt = register()
            val step = attempt.startStep("flow.yaml", 0, "ScrollUntilVisibleCommand", true)
            val failure = AgentProtocolException(AgentError(kind, "BODY_FAILURE", "original execution failure",
                mapOf("fixtureDetail" to "retained")))
            failure.addSuppressed(HdcTimeoutException(Duration.ofSeconds(2)))
            attempt.finishStep(step, "failed", HarmonyOsAgentProtocol.error(failure))
            attempt.confirmSessionCleanup()

            attempt.finishFailure(failure)

            val result = json(attempt.result())["data"]
            assertThat(result["status"].asText()).isEqualTo(if (kind == "assertion") "failed" else "unknown")
            assertThat(result["effects"].asText()).isEqualTo("possible")
            assertThat(result["failedStepId"].asText()).isEqualTo(step)
            assertThat(result["error"]["code"].asText()).isEqualTo("BODY_FAILURE")
            assertThat(result["error"]["details"]["fixtureDetail"].asText()).isEqualTo("retained")
            val diagnostic = result["error"]["details"]["suppressedErrors"].single()
            assertThat(diagnostic["code"].asText()).isEqualTo("OPERATION_TIMEOUT")
            assertThat(diagnostic["message"].asText()).contains("2000 ms")
            assertThat(result["cleanup"]["state"].asText()).isEqualTo("complete")
            assertThat(result["cleanup"]["errors"].size()).isEqualTo(0)
            assertThat(mapper.readTree(attempt.directory.resolve("result.json").toFile())["data"]).isEqualTo(result)
        }
    }

    @Test fun `generic cleanup complete cannot confirm teardown or retain an earlier confirmation`() {
        for (previouslyConfirmed in listOf(false, true)) {
            for (suppressed in listOf(false, true)) {
                val attempt = register()
                attempt.startStep("flow.yaml", 0, "ScrollUntilVisibleCommand", true)
                if (previouslyConfirmed) attempt.confirmSessionCleanup()
                attempt.setCleanup("complete")
                val failure = HdcTimeoutException(Duration.ofSeconds(5))
                if (suppressed) failure.addSuppressed(HdcTimeoutException(Duration.ofSeconds(2)))

                attempt.finishFailure(failure)

                val result = json(attempt.result())["data"]
                assertThat(result["status"].asText()).isEqualTo("unknown")
                assertThat(result["cleanup"]["state"].asText()).isEqualTo(if (suppressed) "failed" else "unknown")
                assertThat(result["cleanup"]["errors"].size()).isEqualTo(if (suppressed) 1 else 0)
            }
        }
    }

    @Test fun `classification uses wrapped types instead of misleading message text`() {
        val assertion = MaestroException.AssertionFailure("timeout device disconnected", TreeNode(), "assertion")
        assertThat(HarmonyOsAgentProtocol.error(RuntimeException("outer", assertion)).kind).isEqualTo("assertion")
        val typed = AgentProtocolException(AgentError("environment", "DEVICE_BUSY", "occupied"))
        assertThat(HarmonyOsAgentProtocol.error(RuntimeException("timeout", typed)).code).isEqualTo("DEVICE_BUSY")
        assertThat(HarmonyOsAgentProtocol.error(HdcException("device disconnected text without proof")).code).isEqualTo("HDC_ERROR")
        assertThat(HarmonyOsAgentProtocol.error(HarmonyOSCommandException("remote command rejected", "rejected", "", 37)).code)
            .isEqualTo("DEVICE_COMMAND_FAILED")
    }

    private fun register(runId: String = uuid(), attemptId: String = uuid(), attemptNo: Int = 1,
        input: String = "{\"type\":\"back\"}") = HarmonyOsAgentAttemptStore(root)
        .register(runId, attemptId, attemptNo, uuid(), "fixture", "action", mapper.readTree(input))
    private fun json(value: Any): JsonNode = mapper.valueToTree(value)
    private fun uuid() = UUID.randomUUID().toString()
}
