package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.Maestro
import maestro.TreeNode
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.driver.HarmonyOSDevice
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcCommandResult
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/** These fake-device bundles also serve as concrete JSON Schema fixtures for the contract checks. */
class HarmonyOsAgentRecorderTest {
    private val mapper = jacksonObjectMapper()

    @Test fun `agent input executes once and persists generated flow with real artifact references`() = fixture { recorder, device, root ->
        val input = mapper.readTree("{\"type\":\"inputText\",\"text\":\"hello\"}")
        val attempt = attempt(root, input)
        recorder.executeAgentAction(input, attempt)
        assertThat(device.inputs).containsExactly("hello")
        val result = json(attempt.result())["data"]
        assertThat(result["status"].asText()).isEqualTo("passed")
        assertThat(Files.readString(attempt.directory.resolve(result["flow"]["path"].asText()))).contains("inputText")
        val available = json(attempt.evidence())["data"]["entries"].flatMap { it["evidence"].toList() }
            .filter { it["state"].asText() == "available" }
        assertThat(available).isNotEmpty()
        available.forEach { assertThat(Files.isRegularFile(attempt.directory.resolve(it["path"].asText()))).isTrue() }
    }

    @Test fun `real Orchestra assertion failure retains assertion and source step with evidence`() = fixture { recorder, _, root ->
        val input = mapper.readTree("{\"type\":\"assertVisible\",\"text\":\"missing label\"}")
        val attempt = attempt(root, input)
        recorder.executeAgentAction(input, attempt)
        val result = json(attempt.result())["data"]
        assertThat(result["status"].asText()).isEqualTo("failed")
        assertThat(result["error"]["kind"].asText()).isEqualTo("assertion")
        val failure = json(attempt.evidence())["data"]["entries"].first { it["stepId"].asText() == result["failedStepId"].asText() }
        assertThat(failure["source"]["flow"].asText()).isEqualTo("flow.yaml")
        assertThat(failure["source"]["commandIndex"].asInt()).isEqualTo(0)
        assertThat(failure["error"]["kind"].asText()).isEqualTo("assertion")
        assertThat(failure["evidence"].map { it["kind"].asText() }).containsAtLeast("screenshot", "hierarchy", "log", "commands")
    }

    @Test fun `stale selection is blocked before flow generation and device mutation`() = fixture { recorder, device, root ->
        val input = mapper.readTree("{\"type\":\"tapOn\",\"snapshotId\":\"missing\",\"nodeId\":\"0\"}")
        val attempt = attempt(root, input)
        recorder.executeAgentAction(input, attempt)
        val result = json(attempt.result())["data"]
        assertThat(result["status"].asText()).isEqualTo("blocked")
        assertThat(result["effects"].asText()).isEqualTo("none")
        assertThat(result["flow"].isNull).isTrue()
        assertThat(device.commands).isEmpty()
        val evidence = json(attempt.evidence())["data"]["entries"][0]
        assertThat(evidence["source"].isNull).isTrue()
        assertThat(evidence["error"]["code"].asText()).isEqualTo("STALE_SNAPSHOT")
    }

    @Test fun `timed out mutation is unknown and never automatically resent`() = fixture { recorder, device, root ->
        device.failure = HdcTimeoutException(Duration.ofMillis(10))
        val input = mapper.readTree("{\"type\":\"inputText\",\"text\":\"once\"}")
        val attempt = attempt(root, input)
        recorder.executeAgentAction(input, attempt)
        val result = json(attempt.result())["data"]
        assertThat(result["status"].asText()).isEqualTo("unknown")
        assertThat(result["error"]["kind"].asText()).isEqualTo("timeout")
        assertThat(result["effects"].asText()).isEqualTo("possible")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
        assertThat(device.commands.count { it.take(3) == listOf("uitest", "uiInput", "text") }).isEqualTo(1)
    }

    @Test fun `agent cancellation reaches blocking HDC on the Orchestra IO thread`() {
        val root = Path.of("build", "agent-contract-fixtures", UUID.randomUUID().toString()).toAbsolutePath().normalize()
        Files.createDirectories(root)
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val blockNextHierarchy = AtomicBoolean()
        val ioThread = AtomicReference<Thread>()
        val interrupted = AtomicBoolean()
        val image = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }
        val runner = HdcCommandRunner { command, _ ->
            when {
                command.takeLast(2) == listOf("list", "targets") -> HdcCommandResult(0, "fixture", "")
                command.getOrNull(3) == "file" -> {
                    val target = Path.of(command.last())
                    if (command[5].endsWith(".png")) Files.write(target, image)
                    else Files.writeString(target, """{"attributes":{"bounds":"[0,0][80,120]","text":"Ready"},"children":[]}""")
                    HdcCommandResult(0, "", "")
                }
                else -> {
                    val script = command.last()
                    if (script.contains("dumpLayout") && blockNextHierarchy.compareAndSet(true, false)) {
                        ioThread.set(Thread.currentThread())
                        entered.countDown()
                        try { check(unblock.await(30, TimeUnit.SECONDS)) { "Cancellation did not interrupt HDC." } }
                        catch (error: InterruptedException) { interrupted.set(true); throw error }
                        finally { exited.countDown() }
                    }
                    val marker = Regex("MAESTRO_EXIT_[0-9a-f]+=").find(script)?.value
                    HdcCommandResult(0, if (marker == null) "" else "No Error\n${marker}0\n", "")
                }
            }
        }
        val driver = HarmonyOSDriver("fixture", HdcClient("fake-hdc", runner), HarmonyOSDriverConfig(
            abilityName = "EntryAbility", commandTimeout = Duration.ofSeconds(30), settleTimeout = Duration.ofMillis(30),
            pollInterval = Duration.ofMillis(1),
        ))
        driver.open()
        Maestro(driver).use { maestro ->
            val recorder = HarmonyOsRecorder(maestro, RecorderConfig("fixture", "org.example.fixture", "EntryAbility", null, root),
                agentLookupTimeoutMs = 30_000)
            val input = mapper.readTree("{\"type\":\"assertVisible\",\"text\":\"never appears\"}")
            val attempt = attempt(root, input)
            blockNextHierarchy.set(true)
            val workerError = AtomicReference<Throwable?>()
            val worker = Thread {
                try { recorder.executeAgentAction(input, attempt) }
                catch (error: Throwable) { workerError.set(error) }
            }.apply { isDaemon = true; start() }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(ioThread.get()).isNotSameInstanceAs(worker)
                assertThat(attempt.requestCancel()).isTrue() // Intentionally do not interrupt the owner thread.
                assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue()
                worker.join(5_000)
                assertThat(worker.isAlive).isFalse()
                assertThat(interrupted.get()).isTrue()
                val result = json(attempt.result())["data"]
                assertThat(result["status"].asText()).isEqualTo("cancelled")
                assertThat(result["error"]["kind"].asText()).isEqualTo("cancelled")
                assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
                assertThat(result["effects"].asText()).isEqualTo("none")
                workerError.get()?.let { assertThat(HarmonyOsAgentProtocol.error(it).kind).isEqualTo("cancelled") }
            } finally { unblock.countDown(); worker.join(5_000) }
        }
    }

    private fun attempt(root: Path, input: JsonNode) = HarmonyOsAgentAttemptStore(root).register(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), "fixture", "action", input,
    )
    private fun json(value: Any): JsonNode = mapper.valueToTree(value)

    private fun fixture(block: (HarmonyOsRecorder, Device, Path) -> Unit) {
        // Keep each attempt independently so a later run cannot replace a previous failing fixture.
        val root = Path.of("build", "agent-contract-fixtures", UUID.randomUUID().toString()).toAbsolutePath().normalize()
        Files.createDirectories(root)
        val device = Device()
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(abilityName = "EntryAbility",
            commandTimeout = Duration.ofSeconds(1), settleTimeout = Duration.ofMillis(30), pollInterval = Duration.ofMillis(1)))
        driver.open()
        Maestro(driver).use { maestro -> block(HarmonyOsRecorder(maestro,
            RecorderConfig("fixture", "org.example.fixture", "EntryAbility", null, root), agentLookupTimeoutMs = 50), device, root) }
    }

    private class Device : HarmonyOSDevice {
        var failure: Exception? = null
        val commands = mutableListOf<List<String>>()
        val inputs = mutableListOf<String>()
        private val image = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB), "png", output)
            output.toByteArray()
        }
        override fun connect() = Unit
        override fun isAvailable() = true
        override fun screenshot(timeout: Duration) = image
        override fun hierarchy(timeout: Duration) = TreeNode(mutableMapOf("bounds" to "[0,0][80,120]"),
            listOf(TreeNode(mutableMapOf("bounds" to "[5,5][75,30]", "text" to "Ready"))))
        override fun execute(arguments: List<String>, timeout: Duration): String {
            commands += arguments
            failure?.let { throw it }
            if (arguments.take(3) == listOf("uitest", "uiInput", "text")) inputs += arguments[3]
            return when (arguments.take(2)) {
                listOf("aa", "start") -> "start ability successfully."
                listOf("aa", "force-stop") -> "force stop process successfully."
                else -> "No Error"
            }
        }
    }
}
