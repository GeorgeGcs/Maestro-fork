package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.TreeNode
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.driver.HarmonyOSDevice
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.FileAccessScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

class HarmonyOsRecorderTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    fun `snapshot provides real pixel dimensions and hierarchical selectable nodes`() = fixture { recorder, _, _ ->
        val snapshot = json(recorder.snapshot())
        assertThat(snapshot["width"].asInt()).isEqualTo(80)
        assertThat(snapshot["height"].asInt()).isEqualTo(120)
        assertThat(snapshot["screenshot"]["mimeType"].asText()).isEqualTo("image/png")
        val input = snapshot["nodes"].first { it["resourceId"].asText() == "field.[1]" }
        assertThat(input["parentId"].asText()).isEqualTo("0")
        assertThat(input["bounds"]["left"].asInt()).isEqualTo(5)
        assertThat(input["hintText"].asText()).isEqualTo("请输入姓名")
    }

    @Test
    fun `node tap uses a literal unique selector and executes only once`() = fixture { recorder, device, _ ->
        val snapshot = json(recorder.snapshot())
        val result = json(recorder.action(nodeAction("tapOn", snapshot, "0.1")))
        assertThat(result["yaml"].asText()).contains("id:")
        assertThat(result["yaml"].asText()).contains("retryTapIfNoChange: false")
        assertThat(device.commands.count { it.take(3) == listOf("uitest", "uiInput", "click") }).isEqualTo(1)
        assertThat(assertThrows<RecorderError> { recorder.action(nodeAction("tapOn", snapshot, "0.1")) }.code)
            .isEqualTo("STALE_SNAPSHOT")
    }

    @Test
    fun `changed page is rejected before tapping and old coordinates cannot be used`() = fixture { recorder, device, _ ->
        val snapshot = json(recorder.snapshot())
        device.title = "Different page"
        assertThat(assertThrows<RecorderError> { recorder.action(nodeAction("tapOn", snapshot, "0.1")) }.code)
            .isEqualTo("STALE_SNAPSHOT")
        assertThat(device.commands).isEmpty()
    }

    @Test
    fun `ambiguous node needs explicit coordinate selection`() = fixture { recorder, device, _ ->
        device.duplicate = true
        val snapshot = json(recorder.snapshot())
        assertThat(assertThrows<RecorderError> { recorder.action(nodeAction("tapOn", snapshot, "0.1")) }.code)
            .isEqualTo("AMBIGUOUS_SELECTOR")
        assertThat(device.commands).isEmpty()
        val response = json(recorder.action(json(mapOf("type" to "tapOn", "snapshotId" to snapshot["snapshotId"].asText(),
            "point" to mapOf("x" to 20, "y" to 50)))))
        assertThat(response["yaml"].asText()).contains("point: \"20,50\"")
        assertThat(device.commands.count { it.take(3) == listOf("uitest", "uiInput", "click") }).isEqualTo(1)
    }

    @Test
    fun `recorded input preserves Unicode quotes newlines slashes and literal expressions through actual Orchestra`() = fixture { recorder, device, maestro ->
        val texts = listOf("中文 'quoted' \"double\"", "line 1\nline 2", "C:\\work\\", "\${1+1}", "\\\${doNotExecute()}", "成本$42 😀")
        for (text in texts) {
            val response = json(recorder.action(json(mapOf("type" to "inputText", "text" to text))))
            assertThat(device.inputs.last()).isEqualTo(text)
            val replay = directory.resolve("replay-${device.inputs.size}.yaml")
            Files.writeString(replay, "appId: org.example.recorder\n---\n${response["yaml"].asText()}\n")
            val replayDir = Files.createDirectory(directory.resolve("replay-${device.inputs.size}"))
            val result = runBlocking { Orchestra(maestro, artifactsDir = replayDir, scope = FileAccessScope.under(directory))
                .runFlow(YamlCommandReader.readCommands(replay, FileAccessScope.under(directory))) }
            assertThat(result.success).isTrue()
            assertThat(device.inputs.takeLast(2)).containsExactly(text, text).inOrder()
        }
    }

    @Test
    fun `unrepresentable interpolation and invalid action parameters fail before device queries`() = fixture { recorder, device, _ ->
        val invalid = listOf(
            mapOf("type" to "inputText", "text" to "\${outer\${inner}}"),
            mapOf("type" to "inputText", "text" to "\${unfinished"),
            mapOf("type" to "inputText", "text" to "\${a\$b}"),
            mapOf("type" to "takeScreenshot", "name" to "../outside"),
            mapOf("type" to "swipe", "direction" to "UP", "duration" to -1),
            mapOf("type" to "back", "shell" to "ignored"),
        )
        val reads = device.reads
        for (params in invalid) assertThat(assertThrows<RecorderError> { recorder.action(json(params)) }.code).isEqualTo("INVALID_PARAMS")
        assertThat(device.commands).isEmpty()
        assertThat(device.reads).isEqualTo(reads)
    }

    @Test
    fun `launch back swipe assertion and screenshots run through standard YAML commands`() = fixture { recorder, device, _ ->
        val actions = listOf(mapOf("type" to "launchApp"), mapOf("type" to "back"),
            mapOf("type" to "swipe", "direction" to "UP", "duration" to 600),
            mapOf("type" to "assertVisible", "text" to "Ready"), mapOf("type" to "takeScreenshot", "name" to "recorded-page"))
        for (action in actions) {
            val response = json(recorder.action(json(action)))
            assertThat(response["yaml"].asText()).startsWith("- ")
            assertThat(Files.isDirectory(Path.of(response["artifactsDir"].asText()))).isTrue()
        }
        assertThat(device.commands.any { it.take(2) == listOf("aa", "start") }).isTrue()
        assertThat(device.commands.any { it.take(3) == listOf("uitest", "uiInput", "swipe") }).isTrue()
        assertThat(Files.walk(directory).use { paths -> paths.anyMatch { it.fileName.toString() == "recorded-page.png" } }).isTrue()
    }

    @Test
    fun `action failure is preserved and does not retry input`() = fixture { recorder, device, _ ->
        val failure = IllegalStateException("fixture command failed")
        device.failure = failure
        val error = assertThrows<Exception> { recorder.action(json(mapOf("type" to "inputText", "text" to "once"))) }
        assertThat(generateSequence(error as Throwable?) { it.cause }.any { it === failure || it.message == failure.message }).isTrue()
        assertThat(device.commands.count { it.take(3) == listOf("uitest", "uiInput", "text") }).isEqualTo(1)
    }

    @Test
    fun `rotation requires reconnect instead of using cached dimensions`() = fixture { recorder, device, _ ->
        recorder.snapshot()
        device.image = png(120, 80)
        assertThat(assertThrows<RecorderError> { recorder.snapshot() }.code).isEqualTo("ORIENTATION_CHANGED")
    }

    @Test
    fun `non overlapping recognized status bar text may change but cannot be selected or coordinate tapped`() = fixture { recorder, device, _ ->
        device.statusBar = true
        device.changeStatusOnScreenshot = true
        val snapshot = json(recorder.snapshot())
        assertThat(snapshot["nodes"].none { it["resourceId"].asText().contains("StatusBar") }).isTrue()
        assertThat(assertThrows<RecorderError> {
            recorder.action(json(mapOf("type" to "tapOn", "snapshotId" to snapshot["snapshotId"].asText(), "point" to mapOf("x" to 20, "y" to 2))))
        }.code).isEqualTo("INVALID_PARAMS")
        assertThat(device.commands).isEmpty()
        val response = json(recorder.action(nodeAction("assertVisible", snapshot, "0.0.0")))
        assertThat(response["yaml"].asText()).contains("assertVisible")
    }

    @Test
    fun `ignored status bar still participates in selector uniqueness checks`() = fixture { recorder, device, _ ->
        device.statusBar = true
        val snapshot = json(recorder.snapshot())
        device.statusText = "Ready"
        assertThat(assertThrows<RecorderError> { recorder.action(nodeAction("tapOn", snapshot, "0.0.0")) }.code)
            .isEqualTo("STALE_SNAPSHOT")
        assertThat(device.commands).isEmpty()
    }

    @Test
    fun `status bar geometry and new overlapping windows invalidate the snapshot`() {
        for (change in listOf<(Device) -> Unit>({ it.statusBounds = "[0,0][80,10]" }, { it.overlay = true })) fixture { recorder, device, _ ->
            device.statusBar = true
            val snapshot = json(recorder.snapshot())
            change(device)
            assertThat(assertThrows<RecorderError> { recorder.action(nodeAction("tapOn", snapshot, "0.0.1")) }.code)
                .isEqualTo("STALE_SNAPSHOT")
            assertThat(device.commands).isEmpty()
        }
    }

    @Test
    fun `overlapping or unrecognized system windows cannot be excluded from capture stability`() {
        for (change in listOf<(Device) -> Unit>({ it.statusBounds = "[0,0][80,10]" }, { it.statusId = "UnknownSystemContent" })) fixture { recorder, device, _ ->
            device.statusBar = true
            device.changeStatusOnScreenshot = true
            change(device)
            assertThat(assertThrows<RecorderError> { recorder.snapshot() }.code).isEqualTo("UNSTABLE_PAGE")
        }
    }

    @Test
    fun `local snapshot settles despite continuous status updates that prevent global driver stability`() = fixture { recorder, device, maestro ->
        device.statusBar = true
        device.changeStatusOnHierarchy = true
        assertThat(runBlocking { maestro.waitForAppToSettle(appId = "org.example.recorder") }).isNull()
        val snapshot = json(recorder.snapshot())
        assertThat(snapshot["nodes"].any { it["text"].asText() == "Ready" }).isTrue()
        assertThat(device.commands).isEmpty()
    }

    @Test
    fun `continuous application changes exhaust the snapshot budget and discard the previous snapshot`() = fixture { recorder, device, _ ->
        val previous = json(recorder.snapshot())
        device.changeAppOnHierarchy = true
        val started = System.nanoTime()
        val failure = assertThrows<RecorderError> { recorder.snapshot() }
        assertThat(failure.code).isEqualTo("UNSTABLE_PAGE")
        assertThat(failure.message).contains("budget")
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(3000)
        device.changeAppOnHierarchy = false
        assertThat(assertThrows<RecorderError> { recorder.action(nodeAction("tapOn", previous, "0.1")) }.code).isEqualTo("STALE_SNAPSHOT")
        assertThat(device.commands).isEmpty()
    }

    private fun nodeAction(type: String, snapshot: JsonNode, nodeId: String) = json(mapOf(
        "type" to type, "snapshotId" to snapshot["snapshotId"].asText(), "nodeId" to nodeId))
    private fun json(value: Any): JsonNode = mapper.valueToTree(value)

    private fun fixture(block: (HarmonyOsRecorder, Device, Maestro) -> Unit) {
        val device = Device()
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(abilityName = "EntryAbility",
            commandTimeout = Duration.ofSeconds(2), settleTimeout = Duration.ofMillis(100), pollInterval = Duration.ofMillis(1)))
        driver.open()
        Maestro(driver).use { maestro -> block(HarmonyOsRecorder(maestro,
            RecorderConfig("fixture-device", "org.example.recorder", "EntryAbility", null, directory),
            snapshotTimeoutMs = 500, snapshotPollMs = 1), device, maestro) }
    }

    private class Device : HarmonyOSDevice {
        var title = "Ready"
        var duplicate = false
        var reads = 0
        var image = png(80, 120)
        var statusBar = false
        var statusText = "250"
        var statusId = "StatusBarBox"
        var statusBounds = "[0,0][80,4]"
        var changeStatusOnScreenshot = false
        var changeStatusOnHierarchy = false
        var changeAppOnHierarchy = false
        var overlay = false
        private var statusUpdates = 0
        var failure: Exception? = null
        val commands = mutableListOf<List<String>>()
        val inputs = mutableListOf<String>()
        override fun connect() = Unit
        override fun isAvailable() = true
        override fun screenshot(timeout: Duration): ByteArray {
            if (changeStatusOnScreenshot) statusText = (++statusUpdates).toString()
            return image
        }
        override fun hierarchy(timeout: Duration): TreeNode {
            reads++
            if (changeStatusOnHierarchy) statusText = (++statusUpdates).toString()
            if (changeAppOnHierarchy) title = "Changed $reads"
            val input = TreeNode(mutableMapOf("bounds" to "[5,35][75,65]", "resource-id" to "field.[1]", "hintText" to "请输入姓名"), enabled = true)
            val children = mutableListOf(TreeNode(mutableMapOf("bounds" to "[5,5][75,30]", "text" to title)), input)
            if (duplicate) children += input.copy(attributes = input.attributes.toMutableMap().apply { put("bounds", "[5,70][75,100]") })
            if (!statusBar) return TreeNode(mutableMapOf("bounds" to "[0,0][80,120]"), children)
            val app = TreeNode(mutableMapOf("bounds" to "[0,5][80,120]", "bundleName" to "org.example.recorder"), children)
            val bar = TreeNode(mutableMapOf("bounds" to statusBounds, "bundleName" to "com.ohos.sceneboard", "class" to "WindowScene"),
                listOf(TreeNode(mutableMapOf("bounds" to statusBounds, "resource-id" to statusId),
                    listOf(TreeNode(mutableMapOf("bounds" to statusBounds, "text" to statusText))))))
            val windows = mutableListOf(app, bar)
            if (overlay) windows += TreeNode(mutableMapOf("bounds" to "[0,10][80,110]", "bundleName" to "org.example.permissions", "text" to "Permission dialog"))
            return TreeNode(mutableMapOf("bounds" to "[0,0][80,120]"), windows)
        }
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

    companion object {
        private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
            out.toByteArray()
        }
    }
}
