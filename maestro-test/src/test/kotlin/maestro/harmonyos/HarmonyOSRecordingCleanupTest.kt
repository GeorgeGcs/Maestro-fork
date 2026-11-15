package maestro.harmonyos

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.driver.HarmonyOSDevice
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.harmonyos.hdc.HdcClient
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.ExecutionTraceEnd
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.FileAccessScope
import maestro.utils.TempFileHandler
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class HarmonyOSRecordingCleanupTest {
    @Test
    fun `failed assertion closes the recording and its artifact sink before returning`() {
        withFlow { flow ->
            val failure = assertThrows<MaestroException.AssertionFailure> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }

            assertThat(failure).isSameInstanceAs(flow.commandFailures.single())
            assertThat(flow.device.stopCalls).isEqualTo(1)
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `recording cleanup failure is suppressed on the original assertion`() {
        val cleanupFailure = IOException("recording stop failed")
        withFlow(stopFailure = cleanupFailure) { flow ->
            val failure = assertThrows<MaestroException.AssertionFailure> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }

            assertThat(failure).isSameInstanceAs(flow.commandFailures.single())
            assertThat(failure.suppressed).asList().contains(cleanupFailure)
            assertThat(flow.device.stopCalls).isEqualTo(1)
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `FAIL callback still returns false when recording cleanup succeeds`() {
        withFlow(failureResolution = Orchestra.ErrorResolution.FAIL) { flow ->
            val result = runBlocking { flow.orchestra.runFlow(flow.commands) }
            assertThat(result.success).isFalse()
            assertThat(flow.commandFailures.single()).isInstanceOf(MaestroException.AssertionFailure::class.java)
            assertThat(flow.commandFailures.single().suppressed).isEmpty()
            assertThat(flow.traceEnds.single().error).isNull()
            assertThat(flow.traceEnds.single().success).isFalse()
            assertThat(flow.listenerFailures.single()).isSameInstanceAs(flow.commandFailures.single())
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `FAIL callback preserves the assertion when recording cleanup also fails`() {
        val cleanup = IOException("recording stop failed")
        withFlow(stopFailure = cleanup, failureResolution = Orchestra.ErrorResolution.FAIL) { flow ->
            val failure = assertThrows<MaestroException.AssertionFailure> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(failure).isSameInstanceAs(flow.commandFailures.single())
            assertThat(failure.suppressed).asList().containsExactly(cleanup)
            assertThat(flow.listenerFailures.single()).isSameInstanceAs(failure)
            assertThat(flow.traceEnds.single().error).isSameInstanceAs(failure)
            assertThat(flow.device.stopCalls).isEqualTo(1)
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `completion FAIL still returns false when its recording cleanup succeeds`() {
        withFlow(failureResolution = Orchestra.ErrorResolution.FAIL, yaml = completionRecordingFlow(bodyFails = false)) { flow ->
            val result = runBlocking { flow.orchestra.runFlow(flow.commands) }
            assertThat(result.success).isFalse()
            assertThat(flow.commandFailures).hasSize(1)
            assertThat(flow.commandFailures.single().suppressed).isEmpty()
            assertThat(flow.traceEnds.single().error).isNull()
            assertThat(flow.traceEnds.single().success).isFalse()
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `completion FAIL retains its original assertion when its recording cleanup fails`() {
        val cleanup = IOException("completion recording stop failed")
        withFlow(stopFailure = cleanup, failureResolution = Orchestra.ErrorResolution.FAIL,
            yaml = completionRecordingFlow(bodyFails = false)) { flow ->
            val failure = assertThrows<MaestroException.AssertionFailure> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(failure).isSameInstanceAs(flow.commandFailures.single())
            assertThat(failure.suppressed).asList().containsExactly(cleanup)
            assertThat(flow.traceEnds.single().error).isSameInstanceAs(failure)
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `body FAIL remains primary when completion also fails and cannot close its recording`() {
        val cleanup = IOException("completion recording stop failed")
        withFlow(stopFailure = cleanup, failureResolution = Orchestra.ErrorResolution.FAIL,
            yaml = completionRecordingFlow(bodyFails = true)) { flow ->
            val failure = assertThrows<MaestroException.AssertionFailure> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(flow.commandFailures).hasSize(2)
            assertThat(failure).isSameInstanceAs(flow.commandFailures[0])
            assertThat(failure).isNotSameInstanceAs(flow.commandFailures[1])
            assertThat(failure.suppressed).asList().containsExactly(cleanup)
            assertThat(flow.traceEnds.single().error).isSameInstanceAs(failure)
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `body FAIL remains primary when completion throws and its recording cleanup also fails`() {
        val hookFailure = IllegalStateException("completion input failed")
        val cleanup = IOException("completion recording stop failed")
        withFlow(stopFailure = cleanup, failureResolution = Orchestra.ErrorResolution.FAIL,
            completionCommandFailure = hookFailure, yaml = """
                appId: org.example.harmonyos.flowfixture
                onFlowComplete:
                  - startRecording: completion-failure
                  - inputText: fixture
                ---
                - assertTrue: false
            """) { flow ->
            val failure = assertThrows<MaestroException.AssertionFailure> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(flow.commandFailures).hasSize(2)
            assertThat(failure).isSameInstanceAs(flow.commandFailures[0])
            val observedHookFailure = flow.commandFailures[1]
            // Coroutine stack recovery can copy the IO-thread exception, retaining its cause.
            assertThat(generateSequence(observedHookFailure) { it.cause }.any { it === hookFailure }).isTrue()
            assertThat(failure.suppressed).asList().containsExactly(observedHookFailure, cleanup).inOrder()
            assertThat(flow.traceEnds.single().error).isSameInstanceAs(failure)
            assertOutputClosed(flow.device.output)
        }
    }

    @Test
    fun `recovered retry does not turn its earlier assertion into a recording cleanup failure`() {
        for (cleanup in listOf(null, IOException("recording stop after successful retry failed"))) {
            withFlow(stopFailure = cleanup, failureResolution = Orchestra.ErrorResolution.FAIL, yaml = """
                appId: org.example.harmonyos.flowfixture
                ---
                - startRecording: before-retry
                - evalScript: ${'$'}{output.retryAttempt = 0}
                - retry:
                    maxRetries: 2
                    commands:
                      - assertTrue: ${'$'}{++output.retryAttempt > 1}
            """) { flow ->
                if (cleanup == null) {
                    assertThat(runBlocking { flow.orchestra.runFlow(flow.commands) }.success).isTrue()
                    assertThat(flow.traceEnds.single().error).isNull()
                    assertThat(flow.traceEnds.single().success).isTrue()
                } else {
                    val failure = assertThrows<IOException> { runBlocking { flow.orchestra.runFlow(flow.commands) } }
                    assertThat(generateSequence<Throwable>(failure) { it.cause }.any { it === cleanup }).isTrue()
                    assertThat(flow.traceEnds.single().error).isSameInstanceAs(cleanup)
                    assertThat(cleanup.suppressed).isEmpty()
                }
                assertThat(flow.commandFailures).hasSize(1)
                assertThat(flow.commandFailures.single()).isInstanceOf(MaestroException.AssertionFailure::class.java)
                assertThat(flow.commandFailures.single().suppressed).isEmpty()
                assertOutputClosed(flow.device.output)
            }
        }
    }

    private fun completionRecordingFlow(bodyFails: Boolean) = """
        appId: org.example.harmonyos.flowfixture
        onFlowComplete:
          - startRecording: completion-failure
          - assertTrue: false
        ---
        - assertTrue: ${!bodyFails}
    """

    @Test
    fun `cancellation closes the recording and retains cancellation when cleanup fails`() {
        val cancelled = CancellationException("flow cancelled")
        val cleanupFailure = IOException("recording stop failed")
        withFlow(stopFailure = cleanupFailure, cancellation = cancelled) { flow ->
            val failure = assertThrows<CancellationException> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }

            assertThat(failure).isSameInstanceAs(cancelled)
            assertThat(failure.suppressed).asList().contains(cleanupFailure)
            assertThat(flow.device.stopCalls).isEqualTo(1)
            assertOutputClosed(flow.device.output)
        }
    }

    private fun assertOutputClosed(output: Sink) {
        assertThrows<IllegalStateException> {
            output.write(Buffer().writeUtf8("x"), 1)
        }
    }

    private fun withFlow(
        stopFailure: IOException? = null,
        cancellation: CancellationException? = null,
        failureResolution: Orchestra.ErrorResolution? = null,
        completionCommandFailure: Throwable? = null,
        yaml: String = """
            appId: org.example.harmonyos.flowfixture
            ---
            - startRecording: before-failure
            - assertVisible: never-present
        """,
        block: (FlowFixture) -> Unit,
    ) = TempFileHandler().use { temporary ->
        val root = temporary.createTempDirectory("harmonyos-recording-cleanup-").toPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val artifacts = Files.createDirectory(root.resolve("artifacts"))
        val flowFile = workspace.resolve("recording-failure.yaml")
        Files.writeString(flowFile, yaml.trimIndent())

        val transport = SimulatedHarmonyOSDevice()
        val device = RecordingDevice(
            HdcHarmonyOSDevice(
                SimulatedHarmonyOSDevice.DEVICE_ID,
                HdcClient(executablePath = "simulated-hdc", runner = transport),
            ),
            stopFailure,
            completionCommandFailure,
        )
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(
            abilityName = "EntryAbility",
            enableScreenRecording = true,
            commandTimeout = Duration.ofSeconds(2),
            settleTimeout = Duration.ofMillis(250),
            pollInterval = Duration.ofMillis(1),
        ))
        driver.open()
        fun runFixture() {
            val maestro = Maestro(driver)
            var fixtureFailure: Throwable? = null
            try {
                val scope = FileAccessScope.under(workspace)
                val commands = YamlCommandReader.readCommands(flowFile, scope)
                val commandFailures = mutableListOf<Throwable>()
                val listenerFailures = mutableListOf<Throwable>()
                val traceEnds = mutableListOf<ExecutionTraceEnd>()
                val orchestra = Orchestra(
                    maestro = maestro,
                    artifactsDir = artifacts,
                    lookupTimeoutMs = 0,
                    optionalLookupTimeoutMs = 0,
                    scope = scope,
                    listeners = listOf(object : OrchestraListener {
                        override fun onCommandFinished(cmd: MaestroCommand, outcome: CommandOutcome, startedAt: Long, finishedAt: Long) {
                            if (outcome is CommandOutcome.Failed) listenerFailures += outcome.error
                        }
                        override fun onExecutionTraceEnd(end: ExecutionTraceEnd) { traceEnds += end }
                    }),
                    onCommandStart = { _, command ->
                        if (cancellation != null && command.asCommand() is AssertConditionCommand) throw cancellation
                    },
                    onCommandFailed = { _, _, failure ->
                        commandFailures += failure
                        if (completionCommandFailure != null && failure !is MaestroException) throw failure
                        failureResolution ?: throw failure
                    },
                )
                block(FlowFixture(device, orchestra, commands, commandFailures, listenerFailures, traceEnds))
            } catch (error: Throwable) {
                fixtureFailure = error
                throw error
            } finally {
                try {
                    if (stopFailure == null || device.stopCalls == 0) maestro.close()
                    else assertThat(assertThrows<IOException> { maestro.close() }).isSameInstanceAs(stopFailure)
                } catch (closeError: Throwable) {
                    val original = fixtureFailure
                    if (original == null) throw closeError
                    if (original !== closeError) original.addSuppressed(closeError)
                }
            }
        }
        runFixture()
        assertThat(device.stopCalls).isEqualTo(1)
        assertThat(transport.remoteFiles).isEmpty()
    }

    private class RecordingDevice(
        delegate: HarmonyOSDevice,
        private val stopFailure: IOException?,
        private val completionCommandFailure: Throwable?,
    ) : HarmonyOSDevice by delegate {
        private val delegate = delegate
        lateinit var output: Sink
        var stopCalls = 0

        override fun execute(arguments: List<String>, timeout: Duration): String {
            if (arguments.take(3) == listOf("uitest", "uiInput", "text")) completionCommandFailure?.let { throw it }
            return delegate.execute(arguments, timeout)
        }

        override fun startScreenRecording(out: Sink, config: HarmonyOSDriverConfig): ScreenRecording {
            output = out
            return object : ScreenRecording {
                override val startedAt: Instant = Instant.now()
                override fun close() {
                    stopCalls++
                    stopFailure?.let { throw it }
                }
            }
        }
    }

    private data class FlowFixture(
        val device: RecordingDevice,
        val orchestra: Orchestra,
        val commands: List<MaestroCommand>,
        val commandFailures: List<Throwable>,
        val listenerFailures: List<Throwable>,
        val traceEnds: List<ExecutionTraceEnd>,
    )
}
