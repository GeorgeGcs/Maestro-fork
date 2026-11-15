package maestro.harmonyos

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.driver.HarmonyOSCommandException
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.hdc.HdcClient
import maestro.orchestra.Orchestra
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.debug.RetryExecutionEvent
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.FileAccessScope
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.time.Duration

/** Real HarmonyOSDriver and standard YAML, with only HDC replaced by an in-process fixture. */
class HarmonyOSRetryTraceIntegrationTest {
    @Test
    fun `standard retry recovers a deterministic assertion failure after actual HarmonyOS actions`() {
        val device = SimulatedHarmonyOSDevice()
        val events = mutableListOf<RetryExecutionEvent>()
        withFlow(device, events) { orchestra, commands ->
            assertThat(runBlocking { orchestra.runFlow(commands) }.success).isTrue()
        }
        assertThat(device.tapCount).isEqualTo(2)
        assertThat(device.launchedApps).hasSize(2)
        assertThat(events.filter { it.kind == "retryTryStarted" }.map { it.tryNo }).containsExactly(1, 2).inOrder()
        assertThat(events.filter { it.kind == "retryTryFinished" }.map { it.outcome }).containsExactly("failed", "completed").inOrder()
        assertThat(device.remoteFiles).isEmpty()
    }

    @Test
    fun `HDC command failure is retained and does not replay the retry block`() {
        val device = SimulatedHarmonyOSDevice(rejectTap = true)
        val events = mutableListOf<RetryExecutionEvent>()
        withFlow(device, events) { orchestra, commands ->
            assertThrows<HarmonyOSCommandException> { runBlocking { orchestra.runFlow(commands) } }
        }
        assertThat(device.tapCount).isEqualTo(1)
        assertThat(device.launchedApps).hasSize(1)
        assertThat(events.filter { it.kind == "retryTryStarted" }).hasSize(1)
        assertThat(events.any { it.kind == "retryScheduled" }).isFalse()
        assertThat(events.last().outcome).isEqualTo("interrupted")
    }

    private fun withFlow(device: SimulatedHarmonyOSDevice, events: MutableList<RetryExecutionEvent>,
        block: (Orchestra, List<maestro.orchestra.MaestroCommand>) -> Unit) {
        TempFileHandler().use { temporary ->
            val root = temporary.createTempDirectory("harmonyos-retry-").toPath()
            val workspace = Files.createDirectory(root.resolve("workspace"))
            val artifacts = Files.createDirectory(root.resolve("artifacts"))
            val source = workspace.resolve("retry.yaml")
            Files.writeString(source, """
                appId: org.example.harmonyos.flowfixture
                ---
                - evalScript: ${'$'}{output.retryAttempt = 0}
                - retry:
                    maxRetries: 2
                    commands:
                      - launchApp
                      - tapOn:
                          id: open-form
                          retryTapIfNoChange: false
                      - assertVisible: 姓名
                      - assertTrue: ${'$'}{++output.retryAttempt > 1}
            """.trimIndent())
            val scope = FileAccessScope.under(workspace)
            val commands = YamlCommandReader.readCommands(source, scope)
            val driver = HarmonyOSDriver(deviceId = SimulatedHarmonyOSDevice.DEVICE_ID,
                hdc = HdcClient(executablePath = "simulated-hdc", runner = device),
                config = HarmonyOSDriverConfig(abilityName = "EntryAbility", commandTimeout = Duration.ofSeconds(2),
                    settleTimeout = Duration.ofMillis(250), pollInterval = Duration.ofMillis(1)))
            driver.open()
            Maestro(driver).use { maestro ->
                val observer = object : OrchestraListener {
                    override fun onRetryExecution(event: RetryExecutionEvent) { events += event }
                }
                block(Orchestra(maestro, artifactsDir = artifacts, lookupTimeoutMs = 0, optionalLookupTimeoutMs = 0,
                    scope = scope, listeners = listOf(observer)), commands)
            }
        }
    }
}
