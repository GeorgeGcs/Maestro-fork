package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.hdc.HdcTimeoutException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HarmonyOsAgentRecoveryTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper()
    private fun uuid() = UUID.randomUUID().toString()

    @Test fun `version one stays unchanged and version two requires explicit negotiation`() {
        val old = controller(Backend())
        val upgraded = controller(Backend())
        try {
            val original = call(old, "agent.describe")
            assertThat(original["result"]["schemaVersion"].asInt()).isEqualTo(1)
            assertThat(original["result"]["data"]["methods"].size()).isEqualTo(10)
            assertThat(call(old, "agent.recover", mapOf("deviceId" to "fixture", "sessionId" to uuid()))["error"]["code"].asText()).isEqualTo("UNKNOWN_METHOD")
            assertThat(call(old, "agent.describe", mapOf("schemaVersion" to 2))["error"]["code"].asText()).isEqualTo("PROTOCOL_VERSION_FIXED")
            assertThat(call(upgraded, "agent.describe", mapOf("schemaVersion" to 3))["error"]["code"].asText()).isEqualTo("UNSUPPORTED_PROTOCOL_VERSION")
            val modern = call(upgraded, "agent.describe", mapOf("schemaVersion" to 2))["result"]
            assertThat(modern["schemaVersion"].asInt()).isEqualTo(2)
            assertThat(modern["data"]["methods"].map { it.asText() }).contains("agent.recover")
        } finally { old.close(); upgraded.close() }
    }

    @Test fun `recovery requires completed owner and preserves the original attempt files`() {
        val backend = Backend()
        val control = controller(backend)
        try {
            call(control, "agent.describe", mapOf("schemaVersion" to 2))
            val session = call(control, "agent.connect", config())["result"]["data"]["sessionId"].asText()
            assertThat(call(control, "agent.recover", recovery(session))["error"]["code"].asText()).isEqualTo("DEVICE_BUSY")
            val run = uuid(); val attempt = uuid()
            val submitted = call(control, "agent.action", mapOf("sessionId" to session, "runId" to run,
                "attemptId" to attempt, "attemptNo" to 1, "action" to mapOf("type" to "back")))["result"]
            assertThat(submitted["schemaVersion"].asInt()).isEqualTo(2)
            awaitState(control, session, "unknown")
            call(control, "agent.release", mapOf("sessionId" to session))
            assertThat(awaitState(control, session, "closed")["handoffReady"].asBoolean()).isFalse()
            val resultFile = root.resolve("runs/$run/$attempt/result.json")
            val evidenceFile = root.resolve("runs/$run/$attempt/evidence-index.json")
            val originalResult = Files.readString(resultFile)
            val originalEvidence = Files.readString(evidenceFile)
            assertThat(mapper.readTree(originalResult)["schemaVersion"].asInt()).isEqualTo(1)
            val recovered = call(control, "agent.recover", recovery(session))["result"]
            assertThat(recovered["kind"].asText()).isEqualTo("recoveryReport")
            assertThat(recovered["schemaVersion"].asInt()).isEqualTo(2)
            assertThat(recovered["data"]["handoffReady"].asBoolean()).isTrue()
            assertThat(call(control, "agent.session", mapOf("sessionId" to session))["result"]["data"]["handoffReady"].asBoolean()).isTrue()
            assertThat(Files.readString(resultFile)).isEqualTo(originalResult)
            assertThat(Files.readString(evidenceFile)).isEqualTo(originalEvidence)
            val next = call(control, "agent.connect", config())["result"]["data"]["sessionId"].asText()
            assertThat(next).isNotEqualTo(session)
            call(control, "agent.release", mapOf("sessionId" to next))
            awaitState(control, next, "closed")
        } finally { control.close() }
    }

    @Test fun `recovery is reachable without an in-memory session and cannot overlap another recovery`() {
        val backend = Backend()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        backend.beforeRecovery = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        val control = controller(backend)
        try {
            call(control, "agent.describe", mapOf("schemaVersion" to 2))
            val session = uuid()
            val pending = dispatch(control, "agent.recover", recovery(session))
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(control.hasUnreleasedSession()).isTrue()
            assertThat(call(control, "agent.recover", recovery(session))["error"]["code"].asText()).isEqualTo("DEVICE_BUSY")
            assertThat(call(control, "agent.connect", config())["error"]["code"].asText()).isEqualTo("DEVICE_BUSY")
            finish.countDown()
            assertThat(pending.get(5, TimeUnit.SECONDS)["result"]["data"]["handoffReady"].asBoolean()).isTrue()
            assertThat(control.hasUnreleasedSession()).isFalse()
        } finally { finish.countDown(); control.close() }
    }

    @Test fun `inconsistent backend completion evidence is rejected and does not poison future requests`() {
        val backend = Backend().apply { pendingOperations = 1 }
        val control = controller(backend)
        try {
            call(control, "agent.describe", mapOf("schemaVersion" to 2))
            assertThat(call(control, "agent.recover", recovery(uuid()))["error"]["kind"].asText()).isEqualTo("internal")
            backend.pendingOperations = 0
            assertThat(call(control, "agent.recover", recovery(uuid()))["result"]["data"]["handoffReady"].asBoolean()).isTrue()
        } finally { control.close() }
    }

    @Test fun `snapshot still replies with its original failure when quarantine persistence fails`() {
        val snapshotFailure = HdcTimeoutException(Duration.ofMillis(10))
        val persistenceFailure = IOException("gate write failed")
        val backend = Backend().apply { snapshotError = snapshotFailure; quarantineError = persistenceFailure }
        val control = controller(backend)
        try {
            val session = call(control, "agent.connect", config())["result"]["data"]["sessionId"].asText()
            val response = call(control, "agent.snapshot", mapOf("sessionId" to session))
            assertThat(response["error"]["code"].asText()).isEqualTo("OPERATION_TIMEOUT")
            assertThat(snapshotFailure.suppressed.toList()).contains(persistenceFailure)
            val closed = awaitState(control, session, "closed")
            assertThat(closed["handoffReady"].asBoolean()).isFalse()
            assertThat(closed["cleanup"]["errors"].map { it["message"].asText() }).contains("gate write failed")
            assertThat(backend.returnedNormally).isFalse()
            assertThat(call(control, "agent.connect", config())["error"]["code"].asText()).isEqualTo("DEVICE_BUSY")
        } finally { control.close() }
    }

    @Test fun `action quarantine failure closes ownership without rewriting the terminal attempt`() {
        val backend = Backend().apply { quarantineError = IOException("gate write failed") }
        val control = controller(backend)
        val run = uuid(); val attempt = uuid()
        val resultPath = root.resolve("runs/$run/$attempt/result.json")
        val evidencePath = root.resolve("runs/$run/$attempt/evidence-index.json")
        var originalResult: ByteArray? = null
        var originalEvidence: ByteArray? = null
        backend.beforeQuarantine = {
            originalResult = Files.readAllBytes(resultPath)
            originalEvidence = Files.readAllBytes(evidencePath)
        }
        try {
            val session = call(control, "agent.connect", config())["result"]["data"]["sessionId"].asText()
            call(control, "agent.action", mapOf("sessionId" to session, "runId" to run,
                "attemptId" to attempt, "attemptNo" to 1, "action" to mapOf("type" to "back")))
            val closed = awaitState(control, session, "closed")
            assertThat(closed["handoffReady"].asBoolean()).isFalse()
            assertThat(closed["cleanup"]["errors"].map { it["message"].asText() }).contains("gate write failed")
            assertThat(backend.returnedNormally).isFalse()
            assertThat(Files.readAllBytes(resultPath)).isEqualTo(originalResult)
            assertThat(Files.readAllBytes(evidencePath)).isEqualTo(originalEvidence)
            assertThat(mapper.readTree(Files.readAllBytes(resultPath))["data"]["error"]["code"].asText())
                .isEqualTo("OPERATION_TIMEOUT")
        } finally { control.close() }
    }

    private inner class Backend : RecorderBackend {
        var beforeRecovery: () -> Unit = {}
        var beforeQuarantine: () -> Unit = {}
        var pendingOperations = 0
        var snapshotError: Exception? = null
        var quarantineError: Exception? = null
        var returnedNormally = false
        override fun devices() = listOf("fixture")
        override fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit) {
            assertThat(config.sessionId).isNotNull()
            block(object : AgentRecorderConnection {
                override fun snapshot(): Any = throw snapshotError ?: IllegalStateException("Not used")
                override fun action(parameters: JsonNode): Any = error("Not used")
                override fun executeAgentAction(parameters: JsonNode, attempt: AgentAttempt) {
                    attempt.startStep("flow.yaml", 0, "back", true)
                    attempt.finishFailure(HdcTimeoutException(Duration.ofMillis(10)))
                }
            })
            returnedNormally = true
        }
        override fun noteUncertainCleanup(sessionId: String) {
            beforeQuarantine()
            quarantineError?.let { throw it }
        }
        override fun recover(deviceId: String, sessionId: String): Map<String, Any?> {
            beforeRecovery()
            return mapOf("deviceId" to deviceId, "sessionId" to sessionId, "state" to "complete", "handoffReady" to true,
                "cleanup" to mapOf("state" to "complete", "errors" to emptyList<Any>()),
                "operations" to 1, "pendingOperations" to pendingOperations, "temporaryFiles" to 0,
                "reportPath" to root.resolve("recovery.json").toString(), "reportSha256" to "0".repeat(64))
        }
    }
    private fun controller(backend: Backend) = HarmonyOsAgentController(backend, PrintStream(ByteArrayOutputStream()))
    private fun config() = mapOf("deviceId" to "fixture", "appId" to "org.example.fixture", "abilityName" to "EntryAbility", "artifactsDir" to root.toString())
    private fun recovery(session: String) = mapOf("deviceId" to "fixture", "sessionId" to session)
    private fun dispatch(control: HarmonyOsAgentController, method: String, data: Map<String, Any?> = emptyMap()): CompletableFuture<JsonNode> {
        val result = CompletableFuture<JsonNode>()
        control.dispatch(method, mapper.valueToTree(data),
            { result.complete(mapper.valueToTree(mapOf("result" to it))) },
            { result.complete(mapper.valueToTree(mapOf("error" to HarmonyOsAgentProtocol.error(it).toMap()))) })
        return result
    }
    private fun call(control: HarmonyOsAgentController, method: String, data: Map<String, Any?> = emptyMap()) = dispatch(control, method, data).get(5, TimeUnit.SECONDS)
    private fun awaitState(control: HarmonyOsAgentController, session: String, expected: String): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val state = call(control, "agent.session", mapOf("sessionId" to session))["result"]["data"]
            if (state["state"].asText() == expected) return state
            Thread.sleep(5)
        }
        error("Session did not reach $expected")
    }
}
