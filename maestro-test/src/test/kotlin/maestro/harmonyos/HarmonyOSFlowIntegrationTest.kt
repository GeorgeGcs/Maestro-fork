package maestro.harmonyos

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.Point
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.driver.HarmonyOSCommandException
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.hdc.HdcClient
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.FileAccessScope
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

/** Real Maestro execution with a simulated HDC device, not a simulated Driver. */
class HarmonyOSFlowIntegrationTest {
    @Test
    fun `standard YAML runs variables conditional subflow input assertions and screenshot`() {
        withFlow("flow.yaml") { flow ->
            val result = runBlocking { flow.orchestra.runFlow(flow.commands) }

            assertThat(result.success).isTrue()
            assertThat(flow.device.inputValues).containsExactly(INPUT_TEXT)
            assertThat(flow.device.tapCount).isEqualTo(3)
            assertThat(flow.device.launchedApps).containsExactly(APP_ID)
            assertThat(flow.device.stoppedApps).containsExactly(APP_ID)
            assertThat(flow.device.state).isEqualTo(SimulatedHarmonyOSDevice.State.RESULT)
            assertThat(flow.device.remoteFiles).isEmpty()

            val screenshots = Files.walk(flow.artifacts).use { paths ->
                paths.filter { it.fileName.toString() == "flow-complete.png" }.toList()
            }
            assertThat(screenshots).hasSize(1)
            val screenshot = ImageIO.read(screenshots.single().toFile())
            assertThat(screenshot.width).isEqualTo(SimulatedHarmonyOSDevice.WIDTH)
            assertThat(screenshot.height).isEqualTo(SimulatedHarmonyOSDevice.HEIGHT)
            val manifest = ObjectMapper().readTree(flow.artifacts.resolve("manifest.json").toFile())
            // The manifest registers takeScreenshot as a collection, with one entry per folder.
            val screenshotEntries = manifest["entries"].filter { it["kind"].asText() == "TAKE_SCREENSHOT" }
            assertThat(screenshotEntries).hasSize(1)
            val screenshotEntry = screenshotEntries.single()
            assertThat(screenshotEntry["count"].asInt()).isEqualTo(1)
            assertThat(flow.artifacts.resolve(screenshotEntry["relativePath"].asText()).normalize())
                .isEqualTo(screenshots.single().parent)
            val workspaceFiles = Files.walk(flow.workspace).use { paths ->
                paths.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
            }
            assertThat(workspaceFiles).containsExactlyElementsIn(RESOURCES)
        }
    }

    @Test
    fun `positive assertions with a parent reuse the original full layout`() {
        withFlow("scoped-assertions.yaml") { flow ->
            val before = flow.device.layoutCount
            assertThat(runBlocking { flow.orchestra.runFlow(flow.commands) }.success).isTrue()
            // One read serves both scoped assertions and the plain assertion after them.
            assertThat(flow.device.layoutCount - before).isEqualTo(1)
            assertThat(flow.device.remoteFiles).isEmpty()
        }
    }

    @Test
    fun `scrollUntilVisible waits for full visibility and taps the refreshed input bounds`() {
        withFlow("scroll-flow.yaml") { flow ->
            val result = runBlocking { flow.orchestra.runFlow(flow.commands) }

            assertThat(result.success).isTrue()
            assertThat(flow.device.observedScrollPositions).containsExactly(0, 1, 2)
            assertThat(flow.device.swipes).hasSize(2)
            flow.device.swipes.forEach { swipe ->
                assertThat(swipe.start.y).isGreaterThan(swipe.end.y)
            }
            assertThat(flow.device.tapPoints).containsExactly(Point(200, 480), Point(200, 280)).inOrder()
            assertThat(flow.device.inputTargets).containsExactly("scroll-name-input")
            assertThat(flow.device.inputValues).containsExactly(INPUT_TEXT)
            assertThat(flow.device.state).isEqualTo(SimulatedHarmonyOSDevice.State.SCROLL_FORM)
            assertThat(flow.device.remoteFiles).isEmpty()
        }
    }

    @Test
    fun `scrollUntilVisible failure prevents later taps and text input`() {
        withFlow("scroll-not-found.yaml") { flow ->
            val failure = assertThrows<MaestroException.ElementNotFound> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }

            assertThat(failure.message).contains("missing-scroll-target")
            assertThat(flow.device.swipes).isNotEmpty()
            assertThat(flow.device.tapPoints).containsExactly(Point(200, 480))
            assertThat(flow.device.inputValues).isEmpty()
            assertThat(flow.device.inputTargets).isEmpty()
            assertThat(flow.device.remoteFiles).isEmpty()
        }
    }

    @Test
    fun `sequential YAML subflows relaunch the app and keep input in the intended form`() {
        withFlow("sequential-flows.yaml") { flow ->
            val result = runBlocking { flow.orchestra.runFlow(flow.commands) }

            assertThat(result.success).isTrue()
            assertThat(flow.device.launchedApps).containsExactly(APP_ID, APP_ID)
            assertThat(flow.device.stoppedApps).containsExactly(APP_ID, APP_ID)
            assertThat(flow.device.inputTargets).containsExactly("name-input", "scroll-name-input").inOrder()
            assertThat(flow.device.inputValues).containsExactly(INPUT_TEXT, INPUT_TEXT)
            assertThat(flow.device.tapCount).isEqualTo(5)
            assertThat(flow.device.swipes).hasSize(2)
            val screenshots = Files.walk(flow.artifacts).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".png") }.map { it.fileName.toString() }.toList()
            }
            assertThat(screenshots).containsExactly("flow-complete.png", "scroll-complete.png")
            assertThat(flow.device.remoteFiles).isEmpty()
        }
    }

    @Test
    fun `a failed YAML assertion stops later device actions`() {
        withFlow("assertion-failure.yaml") { flow ->
            assertThrows<MaestroException.AssertionFailure> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(flow.device.tapCount).isEqualTo(0)
            assertThat(flow.device.inputValues).isEmpty()
            assertThat(flow.device.remoteFiles).isEmpty()
        }
    }

    @Test
    fun `a nonzero device command status fails the flow even when host HDC exits zero`() {
        withFlow("remote-failure.yaml", SimulatedHarmonyOSDevice(rejectTap = true)) { flow ->
            val failure = assertThrows<HarmonyOSCommandException> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(failure.remoteExitCode).isEqualTo(37)
            assertThat(flow.device.tapCount).isEqualTo(1)
            assertThat(flow.device.state).isEqualTo(SimulatedHarmonyOSDevice.State.HOME)
            assertThat(flow.device.inputValues).isEmpty()
            assertThat(flow.device.remoteFiles).isEmpty()
        }
    }

    @Test
    fun `a failed UITest response stops the flow even when both exit codes are zero`() {
        withFlow("remote-failure.yaml", SimulatedHarmonyOSDevice(tapFailureOutput = "Initialization failed.")) { flow ->
            val failure = assertThrows<HarmonyOSCommandException> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(failure.stdout).contains("Initialization failed.")
            assertThat(flow.device.tapCount).isEqualTo(1)
            assertThat(flow.device.state).isEqualTo(SimulatedHarmonyOSDevice.State.HOME)
            assertThat(flow.device.inputValues).isEmpty()
            assertThat(flow.device.remoteFiles).isEmpty()
        }
    }

    @Test
    fun `an unsupported YAML operation fails instead of reporting success`() {
        withFlow("unsupported.yaml") { flow ->
            assertThrows<UnsupportedOperationException> {
                runBlocking { flow.orchestra.runFlow(flow.commands) }
            }
            assertThat(flow.device.tapCount).isEqualTo(0)
            assertThat(flow.device.inputValues).isEmpty()
        }
    }

    private fun withFlow(
        filename: String,
        device: SimulatedHarmonyOSDevice = SimulatedHarmonyOSDevice(),
        block: (FlowFixture) -> Unit,
    ) {
        TempFileHandler().use { temporary ->
            val root = temporary.createTempDirectory("harmonyos-flow-").toPath()
            val workspace = Files.createDirectory(root.resolve("workspace"))
            val artifacts = Files.createDirectory(root.resolve("artifacts"))
            RESOURCES.forEach { resource ->
                checkNotNull(javaClass.getResourceAsStream("/harmonyos/$resource")).use { input ->
                    Files.copy(input, workspace.resolve(resource))
                }
            }
            val scope = FileAccessScope.under(workspace)
            val commands = YamlCommandReader.readCommands(workspace.resolve(filename), scope)
                .withEnv(mapOf("APP_ID" to APP_ID, "INPUT_TEXT" to INPUT_TEXT))
            val driver = HarmonyOSDriver(
                deviceId = SimulatedHarmonyOSDevice.DEVICE_ID,
                hdc = HdcClient(executablePath = "simulated-hdc", runner = device),
                config = HarmonyOSDriverConfig(
                    abilityName = "EntryAbility",
                    commandTimeout = Duration.ofSeconds(2),
                    settleTimeout = Duration.ofMillis(250),
                    pollInterval = Duration.ofMillis(1),
                ),
            )
            driver.open()
            Maestro(driver).use { maestro ->
                val orchestra = Orchestra(
                    maestro = maestro,
                    artifactsDir = artifacts,
                    lookupTimeoutMs = 0L,
                    optionalLookupTimeoutMs = 0L,
                    scope = scope,
                )
                block(FlowFixture(device, commands, orchestra, workspace, artifacts))
            }
        }
    }

    private data class FlowFixture(
        val device: SimulatedHarmonyOSDevice,
        val commands: List<MaestroCommand>,
        val orchestra: Orchestra,
        val workspace: Path,
        val artifacts: Path,
    )

    private companion object {
        const val APP_ID = "org.example.harmonyos.flowfixture"
        const val INPUT_TEXT = "熊猫 O'Reilly \"测试\""
        val RESOURCES = listOf(
            "flow.yaml", "enter-name.yaml", "assertion-failure.yaml", "remote-failure.yaml", "unsupported.yaml",
            "scroll-flow.yaml", "enter-scroll-name.yaml", "scroll-not-found.yaml", "sequential-flows.yaml",
            "scoped-assertions.yaml",
        )
    }
}
