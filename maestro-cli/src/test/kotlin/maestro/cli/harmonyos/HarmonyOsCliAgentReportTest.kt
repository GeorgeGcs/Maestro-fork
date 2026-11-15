package maestro.cli.harmonyos

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.cli.session.HarmonyOsSessionLease
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HarmonyOSRecordingResourceJournal
import maestro.harmonyos.hdc.RecordingResourceFailure
import maestro.orchestra.ArtifactEntry
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.BackPressCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.CompositeCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.debug.CommandArtifact
import maestro.orchestra.debug.CommandDebugMetadata
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.debug.ExecutionContext
import maestro.orchestra.debug.ExecutionEvent
import maestro.orchestra.debug.ExecutionTraceArtifacts
import maestro.orchestra.debug.ExecutionTraceEnd
import maestro.orchestra.debug.RetryExecutionEvent
import maestro.orchestra.debug.RetryExecutionFrame
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.time.Instant
import java.time.Duration
import java.util.UUID
import okio.Sink

/** Tests the CLI observer boundary; no device execution is simulated as a validation pass. */
class HarmonyOsCliAgentReportTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    private fun create(): Pair<HarmonyOsCliAgentReport, Path> {
        val flow = directory.resolve("source.yaml")
        Files.writeString(flow, "appId: org.example.fixture\n---\n- back\n")
        return HarmonyOsCliAgentReport.create(directory.resolve("artifacts"), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 1, "fixture-device", setOf(flow.toFile()), emptyMap()) to flow
    }

    @Test
    fun `device selection failure preserves input and typed blocked result without a started step`() {
        val (report, _) = create()
        report.finish(false, AgentProtocolException(AgentError("environment", "DEVICE_NOT_FOUND", "fixture missing")))
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isEqualTo("blocked")
        assertThat(result["startedAt"].isNull).isTrue()
        assertThat(result["effects"].asText()).isEqualTo("none")
        assertThat(result["failedStepId"].isNull).isTrue()
        assertThat(result["error"]["code"].asText()).isEqualTo("DEVICE_NOT_FOUND")
        assertThat(Files.exists(report.directory.resolve(result["input"]["path"].asText()))).isTrue()
    }

    @Test
    fun `repeated command objects get separate steps and failure artifacts match the executed step`() {
        val (report, flow) = create()
        val bundle = Files.createDirectories(report.directory.resolve("flows/fixture"))
        val observer = report.flow(flow.toFile(), bundle)
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        val failure = MaestroException.AssertionFailure("fixture assertion", TreeNode(), "fixture")
        observer.onFlowStart()
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.onCommandFinished(command, CommandOutcome.Completed, 1, 2)
        observer.starting(command)
        observer.onCommandStart(command, 1)
        observer.onCommandFinished(command, CommandOutcome.Failed(failure), 3, 4)
        Files.writeString(bundle.resolve("failure.png"), "fixture screenshot bytes")
        Files.writeString(bundle.resolve("maestro.log"), "fixture structured linkage")
        Files.writeString(bundle.resolve("manifest.json"), "{}")
        observer.finished(Orchestra.FlowResult(false, FlowDebugOutput(executedSteps = mutableListOf(
            CommandDebugMetadata(sequenceNumber = 0, timestamp = 1, duration = 1),
            CommandDebugMetadata(sequenceNumber = 1, timestamp = 3, duration = 1,
                artifacts = mutableListOf(CommandArtifact(ArtifactKind.SCREENSHOT, "failure.png"))),
        )), ArtifactManifest(listOf(ArtifactEntry(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, "maestro.log")))))
        report.finish(false)
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        val entries = mapper.readTree(report.directory.resolve("evidence-index.json").toFile())["data"]["entries"]
        val steps = entries.filter { !it["stepId"].isNull }
        assertThat(steps.size).isEqualTo(2)
        assertThat(steps[0]["stepId"].asText()).isNotEqualTo(steps[1]["stepId"].asText())
        assertThat(result["failedStepId"].asText()).isEqualTo(steps[1]["stepId"].asText())
        assertThat(result["error"]["kind"].asText()).isEqualTo("assertion")
        assertThat(steps[0]["evidence"].size()).isEqualTo(0)
        val shot = steps[1]["evidence"].first { it["kind"].asText() == "screenshot" }
        assertThat(shot["path"].asText()).isEqualTo("flows/fixture/failure.png")
        assertThat(shot["state"].asText()).isEqualTo("available")
        assertThat(shot["sha256"].asText()).hasLength(64)
        assertThat(steps[1]["evidence"].first { it["kind"].asText() == "hierarchy" }["state"].asText()).isEqualTo("missing")
    }

    @Test
    fun `recovered retry retains failure evidence but does not fail a passed flow`() {
        val (report, flow) = create()
        val observer = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/retry")))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        observer.onFlowStart()
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.onCommandFinished(command, CommandOutcome.Failed(UnsupportedOperationException("fixture")), 1, 2)
        report.finish(true)
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isEqualTo("passed")
        assertThat(result["error"].isNull).isTrue()
        assertThat(result["failedStepId"].isNull).isTrue()
        assertThat(result["effects"].asText()).isEqualTo("possible")
    }

    @Test
    fun `real optional retries retain Warned outcomes and count only actual extra attempts`() {
        for (scenario in listOf("warning-without-retry", "recovered", "exhausted")) {
            val maxRetries = if (scenario == "warning-without-retry") 0 else 1
            val attempts = maxRetries + 1
            val warned = scenario != "recovered"
            val source = directory.resolve("optional-$scenario.yaml")
            val condition = if (warned) "false" else "\${output.optionalAttempt >= 2}"
            Files.writeString(source, """
                appId: org.example.fixture
                ---
                - evalScript: ${'$'}{output.optionalAttempt = 0}
                - retry:
                    maxRetries: $maxRetries
                    optional: true
                    commands:
                      - evalScript: ${'$'}{output.optionalAttempt += 1}
                      - assertTrue: $condition
                - assertTrue: ${'$'}{output.optionalAttempt == $attempts}
            """.trimIndent())
            val report = HarmonyOsCliAgentReport.create(directory.resolve("optional-$scenario"), null, null, 1,
                "fixture-device", setOf(source.toFile()), emptyMap())
            val commands = YamlCommandReader.readCommands(source)
            val owner = commands.single { it.retryCommand != null }
            val assertion = owner.retryCommand!!.commands.single { it.assertConditionCommand != null }
            val tail = commands.last()
            val bundle = Files.createDirectories(report.directory.resolve("flows/main"))
            val observer = report.flow(source.toFile(), bundle, commands)
            val retries = mutableListOf<RetryExecutionEvent>()
            val outcomes = mutableListOf<Pair<MaestroCommand, CommandOutcome>>()
            var traceEnd: ExecutionTraceEnd? = null
            val native = object : OrchestraListener {
                override fun onRetryExecution(event: RetryExecutionEvent) { retries += event }
                override fun onCommandFinished(cmd: MaestroCommand, outcome: CommandOutcome, startedAt: Long, finishedAt: Long) {
                    outcomes += cmd to outcome
                }
                override fun onExecutionTraceEnd(end: ExecutionTraceEnd) { traceEnd = end }
            }
            val returned = runBlocking {
                Orchestra(recordingMaestro(null), artifactsDir = bundle, listeners = listOf(observer, native),
                    onCommandStart = { index, command -> observer.starting(command, index) },
                    onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL }).runFlow(commands)
            }
            observer.finished(returned)
            report.finish(returned.success)

            assertThat(returned.success).isTrue()
            assertThat(retries.all { it.optional }).isTrue()
            assertThat(retries.map { it.invocationId }.distinct()).hasSize(1)
            assertThat(retries.filter { it.kind == "retryTryStarted" }.map { it.tryNo })
                .containsExactlyElementsIn((1..attempts).toList()).inOrder()
            assertThat(retries.filter { it.kind == "retryScheduled" }.map { it.tryNo })
                .containsExactlyElementsIn((2..attempts).toList()).inOrder()
            assertThat(retries.single { it.kind == "retryBlockFinished" }.outcome)
                .isEqualTo(if (warned) "failed" else "completed")
            assertThat(outcomes.single { it.first === owner }.second)
                .isEqualTo(if (warned) CommandOutcome.Warned else CommandOutcome.Completed)
            assertThat(outcomes.filter { it.first === assertion }).hasSize(attempts)
            assertThat(outcomes.count { it.first === assertion && it.second is CommandOutcome.Failed })
                .isEqualTo(if (warned) attempts else 1)
            assertThat(outcomes.single { it.first === tail }.second).isEqualTo(CommandOutcome.Completed)
            assertThat(checkNotNull(traceEnd).activeCommands).isEqualTo(0)
            assertThat(checkNotNull(traceEnd).activeRetries).isEqualTo(0)

            val summary = mapper.readTree(bundle.resolve("retry-summary.json").toFile())
            assertThat(summary["state"].asText()).isEqualTo("complete")
            assertThat(summary["retryUsed"].asBoolean()).isEqualTo(attempts > 1)
            assertThat(summary["optionalRetryUsed"].asBoolean()).isEqualTo(attempts > 1)
            assertThat(summary["firstPassStatus"].asText()).isEqualTo(if (attempts > 1) "not_passed" else "passed")
            assertThat(summary["outcomeCounts"].path("Warned").asInt()).isEqualTo(if (warned) 1 else 0)
            val events = Files.readAllLines(bundle.resolve("retry-trace.jsonl")).map { mapper.readTree(it) }
            val block = events.single { it["event"].asText() == "retryBlockStarted" }
            val ownerStep = events.single { it["event"].asText() == "stepFinished" && it["stepId"] == block["retryCommandStepId"] }
            assertThat(ownerStep["outcome"].asText()).isEqualTo(if (warned) "Warned" else "Completed")
            assertThat(events.last()["event"].asText()).isEqualTo("traceCompleted")
            val saved = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
            assertThat(saved["status"].asText()).isEqualTo("passed")
            assertThat(saved["error"].isNull).isTrue()
            val entries = mapper.readTree(report.directory.resolve("evidence-index.json").toFile())["data"]["entries"]
            assertThat(entries.single { it["stepId"] == ownerStep["stepId"] }["status"].asText())
                .isEqualTo(if (warned) "skipped" else "passed")
        }
    }

    @Test
    fun `unconfirmed session cleanup preserves the observed flow outcome`() {
        val (report, flow) = create()
        val observer = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/cleanup")))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.onCommandFinished(command, CommandOutcome.Completed, 1, 2)
        observer.finished(Orchestra.FlowResult(true, FlowDebugOutput(), ArtifactManifest(emptyList())))
        val owner = UUID.randomUUID().toString()
        report.finish(false, AgentProtocolException(AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "Unconfirmed cleanup",
            mapOf("recoverySessionId" to owner))))
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isEqualTo("passed")
        assertThat(result["error"].isNull).isTrue()
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(result["cleanup"]["errors"][0]["code"].asText()).isEqualTo("DEVICE_RECOVERY_REQUIRED")
        assertThat(result["cleanup"]["errors"][0]["details"]["recoverySessionId"].asText()).isEqualTo(owner)
    }

    @Test
    fun `normal session return keeps final cleanup complete after a handled timeout with early cleanup failure`() {
        val (report, flow) = create()
        val session = report.sessionStarted("fixture-device")
        val observer = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/timeout")))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        val failure = HdcTimeoutException(Duration.ofMillis(4994))
        failure.addSuppressed(IOException("early temporary layout cleanup failed", HdcTimeoutException(Duration.ofSeconds(2))))
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.onCommandFinished(command, CommandOutcome.Failed(failure), 1, 2)
        observer.finished(Orchestra.FlowResult(false, FlowDebugOutput(), ArtifactManifest(emptyList())))

        val locks = directory.resolve("clean-session-locks")
        val noHdc = HdcClient("unused", HdcCommandRunner { _, _ -> error("No device command is expected") })
        HarmonyOsSessionSupport.withDeviceLock("fixture-device", locks) {
            val lease = HarmonyOsSessionLease.begin("fixture-device", UUID.randomUUID().toString(), locks)
            lease.finish(null, noHdc)
        }
        // This is the same boundary as TestCommand's newSession(...).also callback.
        report.sessionClosedNormally(session)
        report.finish(false)

        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isEqualTo("unknown")
        assertThat(result["error"]["code"].asText()).isEqualTo("OPERATION_TIMEOUT")
        assertThat(result["error"]["message"].asText()).contains("4994 ms")
        assertThat(result["error"]["details"]["suppressedErrors"].single()["message"].asText()).contains("2000 ms")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("complete")
        assertThat(result["cleanup"]["errors"].size()).isEqualTo(0)
        val gate = mapper.readTree(locks.resolve("${HarmonyOsSessionSupport.lockName("fixture-device")}.owner.json").toFile())
        assertThat(gate["state"].asText()).isEqualTo("clean")
        val receipt = mapper.readTree(Path.of(gate["reportPath"].asText()).toFile())
        assertThat(receipt["reason"].asText()).isEqualTo("session_close")
        assertThat(receipt["state"].asText()).isEqualTo("complete")
    }

    @Test
    fun `an earlier session cannot confirm cleanup for the latest unclosed session`() {
        val (report, _) = create()
        val earlier = report.sessionStarted("fixture-device")
        report.sessionClosedNormally(earlier)
        report.sessionStarted("fixture-device")
        assertThrows<IllegalStateException> { report.sessionClosedNormally(earlier) }
        report.failed(HdcTimeoutException(Duration.ofSeconds(5)))
        report.finish(false)

        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isNotEqualTo("passed")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
    }

    @Test
    fun `a nested recovery blocker cannot be hidden by a normal return notification`() {
        val (report, _) = create()
        val session = report.sessionStarted("fixture-device")
        val owner = UUID.randomUUID().toString()
        val failure = HdcTimeoutException(Duration.ofSeconds(5))
        val cleanup = IOException("recording cleanup unconfirmed")
        cleanup.addSuppressed(AgentProtocolException(AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "recording remains unknown",
            mapOf("recoverySessionId" to owner))))
        failure.addSuppressed(cleanup)
        report.failed(failure)
        report.sessionClosedNormally(session)
        report.finish(false)

        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isNotEqualTo("passed")
        assertThat(result["error"]["code"].asText()).isEqualTo("OPERATION_TIMEOUT")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
        val blocker = result["cleanup"]["errors"].single { it["code"].asText() == "DEVICE_RECOVERY_REQUIRED" }
        assertThat(blocker["details"]["recoverySessionId"].asText()).isEqualTo(owner)
    }

    @Test
    fun `final close failure revokes a prior normal session notification`() {
        val (report, _) = create()
        val session = report.sessionStarted("fixture-device")
        report.sessionClosedNormally(session)
        val failure = IOException("final close failed")
        failure.addSuppressed(AgentProtocolException(AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "cleanup unconfirmed")))
        report.finish(false, failure)

        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isNotEqualTo("passed")
        assertThat(result["error"]["message"].asText()).isEqualTo("final close failed")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(result["cleanup"]["errors"].map { it["code"].asText() }).contains("DEVICE_RECOVERY_REQUIRED")
    }

    @Test
    fun `real Orchestra FAIL keeps assertion and independent recording recovery blocker in CLI result`() {
        val source = directory.resolve("recording-assertion.yaml")
        Files.writeString(source, "appId: org.example.fixture\n---\n- startRecording: failure\n- assertTrue: false\n")
        val report = HarmonyOsCliAgentReport.create(directory.resolve("recording-report"), null, null, 1,
            "fixture-device", setOf(source.toFile()), emptyMap())
        val commands = YamlCommandReader.readCommands(source)
        val bundle = Files.createDirectories(report.directory.resolve("flows/recording"))
        val observer = report.flow(source.toFile(), bundle, commands)
        val cleanup = IOException("recording output flush failed")
        val owner = UUID.randomUUID().toString()
        val locks = directory.resolve("recording-locks")
        val noHdc = HdcClient("unused", HdcCommandRunner { _, _ -> error("No device command is expected") })
        HarmonyOsSessionSupport.withDeviceLock("fixture-device", locks) {
            val lease = HarmonyOsSessionLease.begin("fixture-device", owner, locks, requiresRecordingCleanup = true)
            val resource = lease.journal.allocateRecording()
            resource.starting(); resource.started(51); resource.stopping(); resource.ended()
            resource.media("file://media/Photo/123/VID_123/${resource.fileName}")
            val original = lease.journal.path.parent.resolve("original.mp4")
            Files.writeString(original, "retained recording fixture")
            resource.archive(original)
            resource.failed(cleanup)
            val marker = cleanup.suppressed.filterIsInstance<RecordingResourceFailure>().single()
            val maestro = recordingMaestro(cleanup)
            var commandFailure: Throwable? = null
            val orchestra = Orchestra(maestro, artifactsDir = bundle, listeners = listOf(observer),
                onCommandStart = { index, command -> observer.starting(command, index) },
                onCommandFailed = { _, _, error -> commandFailure = error; Orchestra.ErrorResolution.FAIL })

            val failure = assertThrows<MaestroException.AssertionFailure> { runBlocking { orchestra.runFlow(commands) } }
            assertThat(failure).isSameInstanceAs(commandFailure)
            assertThat(failure.suppressed).asList().containsExactly(cleanup)
            assertThat(HarmonyOSRecordingResourceJournal.failureFingerprint(cleanup)).isEqualTo(marker.fingerprint)
            observer.aborted(failure)
            // withSession passes the independently remembered Driver close error to the lease.
            val blocked = assertThrows<AgentProtocolException> { lease.finish(cleanup, noHdc) }
            failure.addSuppressed(blocked)
            report.finish(false, failure)

            val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
            assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
            assertThat(result["error"]["message"].asText()).isEqualTo(failure.message)
            assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
            val recovery = result["cleanup"]["errors"].single { it["code"].asText() == "DEVICE_RECOVERY_REQUIRED" }
            assertThat(recovery["details"]["recoverySessionId"].asText()).isEqualTo(owner)
            val gatePath = locks.resolve("${HarmonyOsSessionSupport.lockName("fixture-device")}.owner.json")
            val gate = mapper.readTree(gatePath.toFile())
            assertThat(gate["state"].asText()).isEqualTo("quarantined")
            assertThat(gate["closeFailure"]["code"].asText()).isEqualTo("RECORDING_RESOURCE_CLEANUP_FAILED")
            assertThat(gate["closeFailure"]["details"]["recordingId"].asText()).isEqualTo(marker.recordingId)
            assertThat(gate["closeFailure"]["details"]["failureFingerprint"].asText()).isEqualTo(marker.fingerprint)
        }
    }

    @Test
    fun `real Orchestra handled assertion with successful recording close adds no cleanup error`() {
        val source = directory.resolve("handled-assertion.yaml")
        Files.writeString(source, "appId: org.example.fixture\n---\n- startRecording: failure\n- assertTrue: false\n")
        val report = HarmonyOsCliAgentReport.create(directory.resolve("handled-report"), null, null, 1,
            "fixture-device", setOf(source.toFile()), emptyMap())
        val commands = YamlCommandReader.readCommands(source)
        val bundle = Files.createDirectories(report.directory.resolve("flows/handled"))
        val observer = report.flow(source.toFile(), bundle, commands)
        val result = runBlocking {
            Orchestra(recordingMaestro(null), artifactsDir = bundle, listeners = listOf(observer),
                onCommandStart = { index, command -> observer.starting(command, index) },
                onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL }).runFlow(commands)
        }
        assertThat(result.success).isFalse()
        observer.finished(result)
        report.finish(false)
        val saved = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(saved["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
        assertThat(saved["cleanup"]["state"].asText()).isEqualTo("complete")
        assertThat(saved["cleanup"]["errors"].size()).isEqualTo(0)
    }

    @Test
    fun `bounded cyclic cleanup cause graph never claims cleanup complete`() {
        val (report, _) = create()
        val failure = MaestroException.AssertionFailure("original assertion", TreeNode(), "fixture")
        val causeOne = IOException("cyclic cause one")
        val causeTwo = IOException("cyclic cause two")
        causeOne.initCause(causeTwo)
        causeTwo.initCause(causeOne)
        failure.addSuppressed(causeOne)
        var tail: Throwable = failure
        repeat(80) { index ->
            val next = IOException("cleanup layer $index")
            tail.addSuppressed(next)
            next.addSuppressed(failure)
            tail = next
        }
        tail.addSuppressed(AgentProtocolException(AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "late blocker")))
        report.finish(false, failure)
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(result["cleanup"]["errors"].map { it["message"].asText() })
            .containsExactly("original assertion", "cyclic cause one", "cleanup layer 0")
        assertThat(result["cleanup"]["errors"].none { it["code"].asText() == "DEVICE_RECOVERY_REQUIRED" }).isTrue()
    }

    @Test
    fun `suppressed recovery blocker does not replace a final error with a recovered retry assertion`() {
        val (report, flow) = create()
        val observer = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/recovered")))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.onCommandFinished(command, CommandOutcome.Failed(
            MaestroException.AssertionFailure("earlier retry assertion", TreeNode(), "fixture")), 1, 2)
        observer.finished(Orchestra.FlowResult(true, FlowDebugOutput(), ArtifactManifest(emptyList())))
        val finalError = IOException("final driver close failed")
        finalError.addSuppressed(AgentProtocolException(AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "cleanup blocked")))

        report.finish(false, finalError)

        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isNotEqualTo("passed")
        assertThat(result["error"]["message"].asText()).isEqualTo("final driver close failed")
        assertThat(result["error"]["code"].asText()).isNotEqualTo("ASSERTION_FAILED")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(result["cleanup"]["errors"].map { it["code"].asText() }).contains("DEVICE_RECOVERY_REQUIRED")
    }

    private fun recordingMaestro(cleanup: IOException?): Maestro = mockk(relaxed = true) {
        coEvery { cachedDeviceInfo } returns DeviceInfo(Platform.HARMONYOS, 100, 200, 100, 200)
        coEvery { viewHierarchy(any()) } returns ViewHierarchy(TreeNode())
        coEvery { findAppCrash(any(), any()) } returns null
        coEvery { takeScreenshot(any<Sink>(), any(), any()) } answers { firstArg<Sink>().close() }
        coEvery { startScreenRecording(any()) } returns object : ScreenRecording {
            override val startedAt: Instant = Instant.now()
            override fun close() { cleanup?.let { throw it } }
        }
    }

    @Test
    fun `passed earlier flow cannot hide a later aborted flow behind cleanup failure`() {
        val (report, flow) = create()
        val first = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/first")))
        first.finished(Orchestra.FlowResult(true, FlowDebugOutput(), ArtifactManifest(emptyList())))
        val second = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/second")))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        second.starting(command)
        second.onCommandStart(command, 0)
        second.aborted(MaestroException.AssertionFailure("second flow assertion", TreeNode(), "fixture"))
        report.finish(false, AgentProtocolException(AgentError("environment", "DEVICE_RECOVERY_REQUIRED", "Unconfirmed cleanup")))
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isEqualTo("failed")
        assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
        assertThat(result["error"]["message"].asText()).isEqualTo("second flow assertion")
        assertThat(result["cleanup"]["errors"][0]["code"].asText()).isEqualTo("DEVICE_RECOVERY_REQUIRED")
    }

    @Test
    fun `unclassified exit and external junit are archived without guessing assertion failure`() {
        val (report, _) = create()
        val junit = directory.resolve("external.xml")
        Files.writeString(junit, "<testsuites/>")
        report.junit(junit)
        report.finish(false)
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["status"].asText()).isEqualTo("unknown")
        assertThat(result["error"]["code"].asText()).isEqualTo("EXECUTOR_LOST")
        assertThat(result["artifacts"]["junitPath"].asText()).isEqualTo("junit.xml")
        assertThat(Files.readString(report.directory.resolve("junit.xml"))).isEqualTo("<testsuites/>")
    }

    @Test
    fun `repeat retry and nested flow keep source positions while assigning a new step for every execution`() {
        val child = directory.resolve("child.yaml")
        Files.writeString(child, "appId: org.example.fixture\n---\n- back\n")
        val main = directory.resolve("main.yaml")
        Files.writeString(main, """
            appId: org.example.fixture
            ---
            - repeat:
                times: 2
                commands:
                  - runFlow: child.yaml
            - retry:
                maxRetries: 2
                commands:
                  - runFlow: child.yaml
        """.trimIndent())
        val report = HarmonyOsCliAgentReport.create(directory.resolve("sources-test"), null, null, 1,
            "fixture-device", setOf(main.toFile()), emptyMap())
        val commands = YamlCommandReader.readCommands(main)
        fun flatten(commands: List<MaestroCommand>): List<MaestroCommand> = commands.flatMap {
            listOf(it) + flatten((it.asCommand() as? CompositeCommand)?.subCommands().orEmpty())
        }
        val children = flatten(commands).filter { it.backPressCommand != null }
        assertThat(children.size).isEqualTo(2)
        val observer = report.flow(main.toFile(), Files.createDirectories(report.directory.resolve("flows/main")), commands)
        observer.onFlowStart()
        listOf(children[0], children[0], children[1], children[1]).forEachIndexed { sequence, command ->
            observer.starting(command, 1)
            observer.onCommandStart(command, sequence)
            observer.onCommandFinished(command, CommandOutcome.Completed, 1, 2)
        }
        report.finish(true)
        val entries = mapper.readTree(report.directory.resolve("evidence-index.json").toFile())["data"]["entries"]
        assertThat(entries.map { it["stepId"].asText() }.distinct().size).isEqualTo(4)
        assertThat(entries.map { it["source"]["commandIndex"].asInt() }).containsExactly(0, 0, 0, 0)
        val paths = entries.map { it["source"]["flow"].asText() }.distinct()
        assertThat(paths.size).isEqualTo(1)
        assertThat(Files.readString(report.directory.resolve(paths.single()))).isEqualTo(Files.readString(child))
        val prior = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        Files.writeString(child, "appId: org.example.fixture\n---\n- launchApp\n")
        val conflict = assertThrows<AgentProtocolException> {
            HarmonyOsCliAgentReport.create(directory.resolve("sources-test"), prior["runId"].asText(), UUID.randomUUID().toString(), 2,
                "fixture-device", setOf(main.toFile()), emptyMap())
        }
        assertThat(conflict.error.code).isEqualTo("ATTEMPT_CONFLICT")
    }

    @Test
    fun `interrupted active command is terminal and names every unavailable evidence kind`() {
        val (report, flow) = create()
        val observer = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/interrupted")))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        observer.onFlowStart()
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.aborted(InterruptedException("fixture interruption"))
        report.finish(false)
        val entries = mapper.readTree(report.directory.resolve("evidence-index.json").toFile())["data"]["entries"]
        assertThat(entries[0]["status"].asText()).isEqualTo("cancelled")
        assertThat(entries[0]["error"]["code"].asText()).isEqualTo("CANCELLED")
        assertThat(entries[0]["evidence"].map { it["kind"].asText() }).containsExactly("screenshot", "hierarchy", "log", "commands")
    }

    @Test
    fun `artifact indexing error preserves the original assertion classification`() {
        val (report, flow) = create()
        val observer = report.flow(flow.toFile(), Files.createDirectories(report.directory.resolve("flows/failure")))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        val assertion = MaestroException.AssertionFailure("original assertion", TreeNode(), "fixture")
        observer.onFlowStart()
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.onCommandFinished(command, CommandOutcome.Failed(assertion), 1, 2)
        val indexingError = assertThrows<IllegalArgumentException> {
            observer.finished(Orchestra.FlowResult(false, FlowDebugOutput(executedSteps = mutableListOf(
                CommandDebugMetadata(sequenceNumber = 0, artifacts = mutableListOf(CommandArtifact(ArtifactKind.SCREENSHOT, "../escape.png")))
            )), ArtifactManifest()))
        }
        observer.aborted(indexingError)
        report.finish(false, indexingError)
        val result = mapper.readTree(report.directory.resolve("result.json").toFile())["data"]
        assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
        assertThat(result["error"]["message"].asText()).isEqualTo("original assertion")
        assertThat(result["error"]["details"].has("evidenceError")).isTrue()
    }

    @Test
    fun `source snapshots retain CRLF and UTF8 BOM bytes`() {
        for ((index, prefix) in listOf("", "\uFEFF").withIndex()) {
            val source = directory.resolve("windows-$index.yaml")
            val bytes = (prefix + "appId: org.example.fixture\r\n---\r\n- back\r\n").toByteArray(Charsets.UTF_8)
            Files.write(source, bytes)
            val report = HarmonyOsCliAgentReport.create(directory.resolve("windows-$index"), null, null, 1,
                "fixture-device", setOf(source.toFile()), emptyMap())
            val commands = YamlCommandReader.readCommands(source)
            val observer = report.flow(source.toFile(), Files.createDirectories(report.directory.resolve("flows/main")), commands)
            val command = commands.first { it.backPressCommand != null }
            observer.starting(command, 0)
            observer.onCommandStart(command, 0)
            observer.onCommandFinished(command, CommandOutcome.Completed, 1, 2)
            report.finish(true)
            val entries = mapper.readTree(report.directory.resolve("evidence-index.json").toFile())["data"]["entries"]
            assertThat(Files.readAllBytes(report.directory.resolve(entries[0]["source"]["flow"].asText()))).isEqualTo(bytes)
        }
    }

    @Test
    fun `retry trace binds existing step IDs to each execution and its own screenshot`() {
        val source = directory.resolve("retry-source.yaml")
        Files.writeString(source, "appId: org.example.fixture\n---\n- retry:\n    maxRetries: 1\n    commands:\n      - back\n")
        val report = HarmonyOsCliAgentReport.create(directory.resolve("retry-agent"), null, null, 1,
            "fixture-device", setOf(source.toFile()), emptyMap())
        val commands = YamlCommandReader.readCommands(source)
        val owner = commands.first { it.retryCommand != null }
        val child = owner.retryCommand!!.commands.first { it.backPressCommand != null }
        val bundle = Files.createDirectories(report.directory.resolve("flows/main"))
        val observer = report.flow(source.toFile(), bundle, commands)
        var refreshedManifest = ArtifactManifest()
        observer.onExecutionTraceReady(ExecutionTraceArtifacts({ name, _ -> bundle.resolve(name).toFile() }, {
            refreshedManifest = ArtifactManifest(listOf("retry-trace.jsonl", "retry-summary.json").map { name ->
                ArtifactEntry(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, name, sizeBytes = Files.size(bundle.resolve(name)))
            })
            mapper.writeValue(bundle.resolve("manifest.json").toFile(), refreshedManifest)
        }, { refreshedManifest }))
        var sequence = 0L
        fun event() = ExecutionEvent(sequence++, sequence * 100)
        fun retry(kind: String, tryNo: Int? = null, outcome: String? = null) =
            observer.onRetryExecution(RetryExecutionEvent(event(), kind, "retry", 0, emptyList(), 1, false, tryNo, outcome))
        val parent = ExecutionContext(0, null, emptyList(), null, "body", emptyList())
        observer.starting(owner)
        observer.onCommandExecutionStarted(owner, parent, 0, event())
        retry("retryBlockStarted")
        val failed = MaestroException.AssertionFailure("first attempt", TreeNode(), "fixture")
        for (tryNo in 1..2) {
            retry("retryTryStarted", tryNo)
            val context = ExecutionContext(tryNo, 0, listOf(RetryExecutionFrame("retry", 0, tryNo)), 0, "body", emptyList())
            observer.starting(child)
            observer.onCommandExecutionStarted(child, context, 1, event())
            observer.onCommandExecutionFinished(child, context,
                if (tryNo == 1) CommandOutcome.Failed(failed) else CommandOutcome.Completed, 1, 2, event())
            retry("retryTryFinished", tryNo, if (tryNo == 1) "failed" else "completed")
            if (tryNo == 1) retry("retryScheduled", 2)
            Files.writeString(bundle.resolve("attempt-$tryNo.png"), "screenshot-$tryNo")
        }
        retry("retryBlockFinished", outcome = "completed")
        observer.onCommandExecutionFinished(owner, parent, CommandOutcome.Completed, 1, 3, event())
        val ending = event()
        observer.onExecutionTraceEnd(ExecutionTraceEnd(ending, sequence, sequence + 1, 2, 0, 0, true, null))
        val returned = observer.finished(Orchestra.FlowResult(true, FlowDebugOutput(executedSteps = (1..2).map { index ->
            CommandDebugMetadata(sequenceNumber = index, artifacts = mutableListOf(
                CommandArtifact(ArtifactKind.SCREENSHOT, "attempt-$index.png")))
        }.toMutableList()), ArtifactManifest()))
        report.finish(true)

        assertThat(returned.artifactManifest.entries).hasSize(2)
        returned.artifactManifest.entries.forEach { entry ->
            assertThat(entry.sizeBytes).isEqualTo(Files.size(bundle.resolve(entry.relativePath)))
        }
        assertThat(returned.success).isTrue()

        val summary = mapper.readTree(bundle.resolve("retry-summary.json").toFile())
        assertThat(summary["state"].asText()).isEqualTo("complete")
        assertThat(summary["firstPassStatus"].asText()).isEqualTo("not_passed")
        val events = Files.readAllLines(bundle.resolve("retry-trace.jsonl")).map { mapper.readTree(it) }
        val starts = events.filter { it["event"].asText() == "stepStarted" && it["executionSequence"].asInt() > 0 }
        val entries = mapper.readTree(report.directory.resolve("evidence-index.json").toFile())["data"]["entries"]
        starts.forEachIndexed { index, start ->
            val entry = entries.single { it["stepId"].asText() == start["stepId"].asText() }
            assertThat(entry["source"]["flow"].asText()).isEqualTo(start["sourcePath"].asText())
            assertThat(start["retryContext"][0]["tryNo"].asInt()).isEqualTo(index + 1)
            val screenshot = entry["evidence"].single { it["kind"].asText() == "screenshot" && it["state"].asText() == "available" }
            assertThat(screenshot["path"].asText()).isEqualTo("flows/main/attempt-" + (index + 1) + ".png")
        }
        assertThat(starts.map { it["stepId"].asText() }.distinct()).hasSize(2)
        assertThat(starts.map { it["sourcePath"].asText() }.distinct()).hasSize(1)
        assertThat(mapper.readTree(report.directory.resolve("result.json").toFile())["data"]["status"].asText()).isEqualTo("passed")
    }

    @Test
    fun `same content subflows retain distinct registered identities on every real step`() {
        val main = directory.resolve("identity-main.yaml")
        val first = directory.resolve("identity-a.yaml")
        val second = directory.resolve("identity-b.yaml")
        val content = "appId: org.example.fixture\n---\n- back\n"
        Files.writeString(first, content)
        Files.writeString(second, content)
        Files.writeString(main, "appId: org.example.fixture\n---\n- runFlow: identity-a.yaml\n- runFlow: identity-b.yaml\n")
        val report = HarmonyOsCliAgentReport.create(directory.resolve("identity-agent"), null, null, 1,
            "fixture-device", setOf(main.toFile()), emptyMap())
        val commands = YamlCommandReader.readCommands(main)
        val children = commands.mapNotNull { it.runFlowCommand }.map { flow ->
            flow.commands.first { it.backPressCommand != null }
        }
        assertThat(children.map { it.sourceInfo!!.path }).containsExactly(first.toString(), second.toString()).inOrder()
        val bundle = Files.createDirectories(report.directory.resolve("flows/identity"))
        val observer = report.flow(main.toFile(), bundle, commands)
        var manifest = ArtifactManifest()
        observer.onExecutionTraceReady(ExecutionTraceArtifacts({ name, _ -> bundle.resolve(name).toFile() }, {
            manifest = ArtifactManifest(listOf("retry-trace.jsonl", "retry-summary.json").map { name ->
                ArtifactEntry(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, name, sizeBytes = Files.size(bundle.resolve(name)))
            })
        }, { manifest }))
        var sequence = 0L
        fun event() = ExecutionEvent(sequence++, sequence * 100)
        // Include a repeated A step and a generated command: neither can borrow B's identity.
        val executed = listOf(children[0], children[1], children[0], MaestroCommand(backPressCommand = BackPressCommand()))
        executed.forEachIndexed { index, command ->
            val context = ExecutionContext(index, null, emptyList(), null, "body", emptyList())
            observer.starting(command)
            observer.onCommandExecutionStarted(command, context, 0, event())
            observer.onCommandExecutionFinished(command, context, CommandOutcome.Completed, 1, 2, event())
        }
        val ending = event()
        observer.onExecutionTraceEnd(ExecutionTraceEnd(ending, sequence, sequence + 1, executed.lastIndex, 0, 0, true, null))
        observer.finished(Orchestra.FlowResult(true, FlowDebugOutput(), ArtifactManifest()))
        report.finish(true)

        val events = Files.readAllLines(bundle.resolve("retry-trace.jsonl")).map { mapper.readTree(it) }
        val starts = events.filter { it["event"].asText() == "stepStarted" }
        assertThat(events.first()["schemaVersion"].asInt()).isEqualTo(2)
        assertThat(mapper.readTree(bundle.resolve("retry-summary.json").toFile())["state"].asText()).isEqualTo("complete")
        val expected = listOf(first, second, first).map { it.toAbsolutePath().normalize().toString() }
        assertThat(starts.take(3).map { it["sourceIdentity"].asText() }).containsExactlyElementsIn(expected).inOrder()
        assertThat(starts.take(3).map { it["sourcePath"].asText() }.distinct()).hasSize(1)
        assertThat(starts.take(3).map { it["sourceSha256"].asText() }.distinct()).hasSize(1)
        assertThat(starts.take(3).map { it["commandIndex"].asInt() }.distinct()).hasSize(1)
        assertThat(starts.last()["sourceIdentity"].isNull).isTrue()
        assertThat(starts.last()["sourceOrigin"].asText()).isEqualTo("generated")
        val entries = mapper.readTree(report.directory.resolve("evidence-index.json").toFile())["data"]["entries"]
        starts.forEach { start ->
            val terminal = events.single { it["event"].asText() == "stepFinished" && it["stepId"] == start["stepId"] }
            assertThat(terminal["sourceIdentity"]).isEqualTo(start["sourceIdentity"])
            val entry = entries.single { it["stepId"] == start["stepId"] }
            if (!entry["source"].isNull) {
                assertThat(entry["source"]["flow"]).isEqualTo(start["sourcePath"])
                assertThat(entry["source"].has("sourceIdentity")).isFalse()
            }
        }
    }

    @Test
    fun `same bytes at an unregistered path cannot acquire a registered source identity`() {
        val (report, source) = create()
        val alias = directory.resolve("unregistered-copy.yaml")
        Files.copy(source, alias)
        try {
            val failure = assertThrows<AgentProtocolException> {
                report.flow(source.toFile(), directory.resolve("unregistered-flow"), YamlCommandReader.readCommands(alias))
            }
            assertThat(failure.message).contains("not present in the registered input")
        } finally {
            report.finish(false, IllegalStateException("unregistered source rejected"))
        }
    }

    @Test
    fun `sidecar failure does not enter strict reporting failure or change a passed result`() {
        val (report, source) = create()
        val bundle = Files.createDirectories(report.directory.resolve("flows/trace-failure"))
        val observer = report.flow(source.toFile(), bundle)
        observer.onExecutionTraceReady(ExecutionTraceArtifacts({ name, _ -> bundle.resolve(name).toFile() }, {}))
        observer.retryTrace.failed(java.io.IOException("sidecar only"))
        val command = MaestroCommand(backPressCommand = BackPressCommand())
        observer.starting(command)
        observer.onCommandStart(command, 0)
        observer.onCommandFinished(command, CommandOutcome.Completed, 1, 2)
        observer.starting(command)
        observer.onCommandStart(command, 1)
        observer.onCommandFinished(command, CommandOutcome.Completed, 3, 4)
        observer.finished(Orchestra.FlowResult(true, FlowDebugOutput(), ArtifactManifest()))
        report.finish(true)
        assertThat(mapper.readTree(bundle.resolve("retry-summary.json").toFile())["state"].asText()).isEqualTo("incomplete")
        assertThat(mapper.readTree(report.directory.resolve("result.json").toFile())["data"]["status"].asText()).isEqualTo("passed")
    }
}
