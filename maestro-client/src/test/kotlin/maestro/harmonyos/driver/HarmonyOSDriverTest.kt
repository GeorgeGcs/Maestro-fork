package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import maestro.KeyCode
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO

class HarmonyOSDriverTest {
    @Test
    fun `open verifies screenshot and layout then close rejects actions`() {
        val device = Device()
        val driver = driver(device)
        assertThrows(IllegalStateException::class.java) { driver.inputText("hello") }
        driver.open()
        driver.open()
        assertThat(device.connections).isEqualTo(1)
        assertThat(device.layoutReads).isEqualTo(1)
        assertThat(device.screenshots).isEqualTo(1)
        assertThat(driver.deviceInfo().platform).isEqualTo(Platform.HARMONYOS)
        assertThat(driver.deviceInfo().widthGrid).isEqualTo(80)
        assertThat(driver.deviceInfo().heightGrid).isEqualTo(120)
        driver.close()
        driver.close()
        assertThat(driver.isShutdown()).isTrue()
        assertThrows(IllegalStateException::class.java) { driver.tap(Point(1, 1)) }
        assertThat(device.commands).isEmpty()
    }

    @Test
    fun `failed open preserves original error and can be retried`() {
        val device = Device()
        val error = HdcException("UITest access denied")
        device.layout = { throw error }
        val driver = driver(device)
        assertThat(assertThrows(HdcException::class.java) { driver.open() }).isSameInstanceAs(error)
        driver.close()
        assertThrows(IllegalStateException::class.java) { driver.inputText("hello") }
        device.layout = { tree("ready") }
        driver.open()
        assertThat(driver.isShutdown()).isFalse()
    }

    @Test
    fun `screen size comes from fresh PNG rather than layout bounds`() {
        val device = Device()
        device.layout = { TreeNode(attributes = mutableMapOf("bounds" to "[0,0][1,1]")) }
        val driver = driver(device)
        driver.open()
        device.image = png(120, 80)
        val info = driver.deviceInfo()
        assertThat(info.widthPixels).isEqualTo(120)
        assertThat(info.heightPixels).isEqualTo(80)
        assertThrows(IllegalArgumentException::class.java) { driver.tap(Point(120, 0)) }
        assertThrows(IllegalArgumentException::class.java) { driver.tap(Point(0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { driver.swipe(Point(1, 0), Point(30, 40), 200) }
        assertThat(device.commands).isEmpty()
    }

    @Test
    fun `malformed screenshot prevents opening the session`() {
        val device = Device().apply { image = "not a PNG".toByteArray() }
        val driver = driver(device)
        assertThrows(HdcException::class.java) { driver.open() }
        assertThat(driver.isShutdown()).isTrue()
        assertThat(device.layoutReads).isEqualTo(0)
    }

    @Test
    fun `launch requires an explicit ability and validates arguments before starting app`() {
        val device = Device()
        val driver = HarmonyOSDriver(device)
        driver.open()
        val missing = assertThrows(IllegalStateException::class.java) { driver.launchApp("org.example.demo", emptyMap()) }
        assertThat(missing.message).contains("MAESTRO_HARMONYOS_ABILITY")
        assertThat(device.commands).isEmpty()

        val configured = driver(device)
        configured.open()
        assertThrows(IllegalArgumentException::class.java) {
            configured.launchApp("org.example.demo", mapOf("valid" to "yes", "invalid" to -1))
        }
        assertThat(device.commands).isEmpty()
        configured.launchApp("org.example.demo", mapOf("name" to "熊猫", "count" to 2, "active" to true, "empty" to ""))
        assertThat(device.commands.single()).containsExactly(
            "aa", "start", "-b", "org.example.demo", "-a", "TestAbility", "-m", "entry",
            "--ps", "name", "熊猫", "--pi", "count", "2", "--pb", "active", "true", "--psn", "empty",
        ).inOrder()
    }

    @Test
    fun `aa must confirm success even when transport reports successful execution`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        device.output = "The device screen is locked (10106102)"
        val error = assertThrows(HarmonyOSCommandException::class.java) {
            driver.launchApp("org.example.demo", emptyMap())
        }
        assertThat(error.stdout).contains("10106102")
        assertThat(device.commands).hasSize(1)
    }

    @Test
    fun `core gestures text and stop use target device commands once`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        driver.tap(Point(10, 20))
        driver.longPress(Point(30, 40))
        driver.swipe(Point(10, 20), Point(70, 100), 500)
        driver.inputText("中文 'quotes' \"double\"; \$HOME\nnext")
        driver.backPress()
        driver.pressKey(KeyCode.ENTER)
        driver.eraseText(2)
        driver.stopApp("org.example.demo")
        assertThat(device.commands).containsExactly(
            listOf("uitest", "uiInput", "click", "10", "20"),
            listOf("uitest", "uiInput", "longClick", "30", "40"),
            listOf("uitest", "uiInput", "swipe", "10", "20", "70", "100", "200"),
            listOf("uitest", "uiInput", "text", "中文 'quotes' \"double\"; \$HOME\nnext"),
            listOf("uitest", "uiInput", "keyEvent", "Back"),
            listOf("uitest", "uiInput", "keyEvent", "2054"),
            listOf("uitest", "uiInput", "keyEvent", "2055"),
            listOf("uitest", "uiInput", "keyEvent", "2055"),
            listOf("aa", "force-stop", "org.example.demo"),
        ).inOrder()
    }

    @Test
    fun `input failure is preserved without retrying side effects`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        val failure = HdcException("device disconnected after accepting input")
        device.executeFailure = failure
        assertThat(assertThrows(HdcException::class.java) { driver.inputText("hello") }).isSameInstanceAs(failure)
        assertThat(device.commands).hasSize(1)
    }

    @Test
    fun `swiping from an element near the screen edge preserves requested direction`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        driver.swipe(Point(10, 10), SwipeDirection.UP, 400)
        driver.swipe(Point(70, 110), SwipeDirection.RIGHT, 400)
        assertThat(device.commands).containsExactly(
            listOf("uitest", "uiInput", "swipe", "10", "10", "10", "1", "200"),
            listOf("uitest", "uiInput", "swipe", "70", "110", "79", "110", "200"),
        ).inOrder()
    }

    @Test
    fun `unsupported capabilities fail without changing device state`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        driver.setPermissions("org.example.demo", emptyMap())
        assertThrows(UnsupportedOperationException::class.java) { driver.setPermissions("org.example.demo", mapOf("all" to "allow")) }
        assertThrows(UnsupportedOperationException::class.java) { driver.hideKeyboard() }
        assertThrows(UnsupportedOperationException::class.java) { driver.startScreenRecording(Buffer()) }
        assertThrows(UnsupportedOperationException::class.java) { driver.clearAppState("org.example.demo") }
        assertThrows(UnsupportedOperationException::class.java) { driver.contentDescriptor(true) }
        assertThat(driver.capabilities()).isEmpty()
        assertThat(device.commands).isEmpty()
    }

    @Test
    fun `recording requires explicit enablement even when backend can record`() {
        val device = Device().apply { recordingFactory = { _, _ -> Recording() } }
        val driver = driver(device)
        driver.open()
        assertThrows(UnsupportedOperationException::class.java) { driver.startScreenRecording(Buffer()) }
        assertThat(device.recordingStarts).isEqualTo(0)
        driver.close()
    }

    @Test
    fun `enabled recording delegates sink and configuration to backend`() {
        val backendRecording = Recording()
        val device = Device().apply { recordingFactory = { _, _ -> backendRecording } }
        val config = HarmonyOSDriverConfig(enableScreenRecording = true, recordingMaxDuration = Duration.ofSeconds(10))
        val driver = HarmonyOSDriver(device, config)
        val sink = Buffer()
        driver.open()

        val recording = driver.startScreenRecording(sink)

        assertThat(device.recordingStarts).isEqualTo(1)
        assertThat(device.recordingSink).isSameInstanceAs(sink)
        assertThat(device.recordingConfig).isSameInstanceAs(config)
        assertThat(recording.startedAt).isEqualTo(backendRecording.startedAt)
        recording.close()
        driver.close()
    }

    @Test
    fun `duplicate recording start is rejected before calling backend`() {
        val backendRecording = Recording()
        val device = Device().apply { recordingFactory = { _, _ -> backendRecording } }
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(enableScreenRecording = true))
        driver.open()
        driver.startScreenRecording(Buffer())

        assertThrows(IllegalStateException::class.java) { driver.startScreenRecording(Buffer()) }
        assertThat(device.recordingStarts).isEqualTo(1)
        driver.close()
        assertThat(backendRecording.closeCalls).isEqualTo(1)
    }

    @Test
    fun `closing returned recording prevents driver close from stopping it again`() {
        val backendRecording = Recording()
        val device = Device().apply { recordingFactory = { _, _ -> backendRecording } }
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(enableScreenRecording = true))
        driver.open()
        val recording = driver.startScreenRecording(Buffer())

        recording.close()
        driver.close()
        driver.close()

        assertThat(backendRecording.closeCalls).isEqualTo(1)
        assertThat(driver.isShutdown()).isTrue()
    }

    @Test
    fun `driver close stops an unfinished recording exactly once`() {
        val backendRecording = Recording()
        val device = Device().apply { recordingFactory = { _, _ -> backendRecording } }
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(enableScreenRecording = true))
        driver.open()
        driver.startScreenRecording(Buffer())
        assertThat(backendRecording.closeCalls).isEqualTo(0)

        driver.close()
        driver.close()

        assertThat(backendRecording.closeCalls).isEqualTo(1)
        assertThat(driver.isShutdown()).isTrue()
        assertThrows(IllegalStateException::class.java) { driver.startScreenRecording(Buffer()) }
    }

    @Test
    fun `recording close failure preserves original error and shuts down driver safely`() {
        val error = HdcException("recording export failed")
        val backendRecording = Recording(closeFailure = error)
        val device = Device().apply { recordingFactory = { _, _ -> backendRecording } }
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(enableScreenRecording = true))
        driver.open()
        driver.startScreenRecording(Buffer())

        assertThat(assertThrows(HdcException::class.java) { driver.close() }).isSameInstanceAs(error)
        assertThat(driver.isShutdown()).isTrue()
        assertThat(assertThrows(HdcException::class.java) { driver.close() }).isSameInstanceAs(error)
        assertThat(backendRecording.closeCalls).isEqualTo(1)
        assertThrows(IllegalStateException::class.java) { driver.inputText("after close") }
    }

    @Test
    fun `failed recording start remains unconfirmed through retries and final close`() {
        val error = HdcException("recording start failed")
        val device = Device().apply { recordingFactory = { _, _ -> throw error } }
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(enableScreenRecording = true))
        driver.open()
        assertThat(assertThrows(HdcException::class.java) {
            driver.startScreenRecording(Buffer())
        }).isSameInstanceAs(error)

        val backendRecording = Recording()
        device.recordingFactory = { _, _ -> backendRecording }
        assertThat(assertThrows(HdcException::class.java) { driver.startScreenRecording(Buffer()) }).isSameInstanceAs(error)
        assertThat(device.recordingStarts).isEqualTo(1)
        assertThat(assertThrows(HdcException::class.java) { driver.close() }).isSameInstanceAs(error)
        assertThat(backendRecording.closeCalls).isEqualTo(0)
    }

    @Test
    fun `closing a failed recording handle cannot erase final driver cleanup failure`() {
        val error = HdcException("recording service stop unconfirmed")
        val backendRecording = Recording(closeFailure = error)
        val device = Device().apply { recordingFactory = { _, _ -> backendRecording } }
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(enableScreenRecording = true))
        driver.open()
        val handle = driver.startScreenRecording(Buffer())

        assertThat(assertThrows(HdcException::class.java) { handle.close() }).isSameInstanceAs(error)
        handle.close()
        assertThat(assertThrows(HdcException::class.java) { driver.close() }).isSameInstanceAs(error)
        assertThat(backendRecording.closeCalls).isEqualTo(1)
        assertThat(driver.isShutdown()).isTrue()
    }

    @Test
    fun `settling requires two fresh matching hierarchies`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        val samples = ArrayDeque(listOf(tree("old"), tree("new"), tree("new")))
        device.layout = { samples.removeFirst() }
        val readsBefore = device.layoutReads
        val settled = driver.waitForAppToSettle(ViewHierarchy(tree("old")), null, 1000)
        assertThat(settled?.root).isEqualTo(tree("new"))
        assertThat(device.layoutReads - readsBefore).isEqualTo(3)
    }

    @Test
    fun `unstable hierarchy times out and device failures remain errors`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        device.layout = { tree(device.layoutReads.toString()) }
        assertThat(driver.waitForAppToSettle(null, null, 20)).isNull()
        val error = HdcException("layout transport failed")
        device.layout = { throw error }
        assertThat(assertThrows(HdcException::class.java) {
            driver.waitForAppToSettle(null, null, 1000)
        }).isSameInstanceAs(error)
    }

    @Test
    fun `query timeout is terminal even when temporary file cleanup succeeded`() {
        for (screenshot in listOf(false, true)) {
            for (cleanupFailed in listOf(false, true)) {
                val device = Device()
                val driver = driver(device)
                driver.open()
                val error = HdcTimeoutException(Duration.ofSeconds(15)).apply {
                    if (cleanupFailed) addSuppressed(HdcException("remote cleanup failed"))
                }
                if (screenshot) device.screenshotFailure = error else device.layout = { throw error }
                assertThat(assertThrows(HdcTimeoutException::class.java) {
                    if (screenshot) driver.waitUntilScreenIsStatic(1000)
                    else driver.waitForAppToSettle(null, null, 1000)
                }).isSameInstanceAs(error)
                val reads = device.layoutReads + device.screenshots
                assertThat(driver.isShutdown()).isTrue()
                assertThat(assertThrows(HdcTimeoutException::class.java) { driver.inputText("later action") }).isSameInstanceAs(error)
                assertThrows(HdcTimeoutException::class.java) { driver.takeScreenshot(Buffer(), false) }
                assertThat(device.layoutReads + device.screenshots).isEqualTo(reads)
                assertThat(device.commands).isEmpty()
                driver.close()
                assertThrows(HdcTimeoutException::class.java) { driver.open() }
                assertThat(device.connections).isEqualTo(1)
            }
        }
    }

    @Test
    fun `query timeout still allows exactly one recording close and preserves its failure`() {
        for (closeFailure in listOf(null, HdcException("recording stop unconfirmed"))) {
            val recording = Recording(closeFailure)
            val device = Device().apply { recordingFactory = { _, _ -> recording } }
            val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(enableScreenRecording = true))
            driver.open()
            driver.startScreenRecording(Buffer())
            device.layout = { throw HdcTimeoutException(Duration.ofSeconds(15)) }
            assertThrows(HdcTimeoutException::class.java) { driver.waitForAppToSettle(null, null, 1000) }
            repeat(2) {
                if (closeFailure == null) driver.close()
                else assertThat(assertThrows(HdcException::class.java) { driver.close() }).isSameInstanceAs(closeFailure)
            }
            assertThat(recording.closeCalls).isEqualTo(1)
            assertThat(driver.isShutdown()).isTrue()
        }
    }

    @Test
    fun `sampling window never becomes a tiny device command timeout`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        assertThat(driver.waitForAppToSettle(null, null, 1000)).isNotNull()
        assertThat(driver.waitUntilScreenIsStatic(1000)).isTrue()
        assertThat(device.layoutBudgets.toSet()).containsExactly(Duration.ofSeconds(15))
        assertThat(device.screenshotBudgets.toSet()).containsExactly(Duration.ofSeconds(15))
    }

    @Test
    fun `window tail shorter than current sample starts no further query`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        device.layout = { Thread.sleep(80); tree("ready") }
        device.beforeScreenshot = { Thread.sleep(80) }
        val layoutReads = device.layoutReads
        val screenReads = device.screenshots
        assertThat(driver.waitForAppToSettle(null, null, 100)).isNull()
        assertThat(driver.waitUntilScreenIsStatic(100)).isFalse()
        assertThat(device.layoutReads - layoutReads).isEqualTo(1)
        assertThat(device.screenshots - screenReads).isEqualTo(1)
        device.layout = { tree("ready") }
        device.beforeScreenshot = {}
        assertThat(driver.waitForAppToSettle(null, null, 1000)).isNotNull()
        assertThat(driver.waitUntilScreenIsStatic(1000)).isTrue()
    }

    @Test
    fun `completed query beyond window cannot report settled and does not poison session`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        device.layout = { Thread.sleep(30); tree("ready") }
        val reads = device.layoutReads
        assertThat(driver.waitForAppToSettle(null, null, 10)).isNull()
        assertThat(device.layoutReads - reads).isEqualTo(1)
        driver.inputText("still usable after confirmed completion")
        assertThat(device.commands).hasSize(1)
    }

    @Test
    fun `full command timeout during settling remains a device error`() {
        val device = Device()
        val driver = HarmonyOSDriver(device, HarmonyOSDriverConfig(commandTimeout = Duration.ofMillis(10)))
        driver.open()
        val error = HdcTimeoutException(Duration.ofMillis(10))
        device.layout = { throw error }
        assertThat(assertThrows(HdcTimeoutException::class.java) {
            driver.waitForAppToSettle(null, null, 1000)
        }).isSameInstanceAs(error)
        device.screenshotFailure = error
        assertThat(assertThrows(HdcTimeoutException::class.java) {
            driver.waitUntilScreenIsStatic(1000)
        }).isSameInstanceAs(error)
    }

    @Test
    fun `screen static check compares pixels and zero timeout makes no reads`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        val before = device.screenshots
        val layouts = device.layoutReads
        assertThat(driver.waitUntilScreenIsStatic(0)).isFalse()
        assertThat(driver.waitForAppToSettle(null, null, 0)).isNull()
        assertThat(device.screenshots).isEqualTo(before)
        assertThat(device.layoutReads).isEqualTo(layouts)
        assertThat(driver.waitUntilScreenIsStatic(1000)).isTrue()
        assertThat(device.screenshots - before).isEqualTo(2)
    }

    @Test
    fun `screenshot remains valid PNG and does not close caller sink`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        val buffer = Buffer()
        driver.takeScreenshot(buffer, false)
        assertThat(buffer.readByteArray()).isEqualTo(device.image)
        buffer.writeUtf8("still open")
        assertThat(buffer.readUtf8()).isEqualTo("still open")
    }

    @Test
    fun `interrupted session does not send commands`() {
        val device = Device()
        val driver = driver(device)
        driver.open()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { driver.inputText("hello") }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(device.commands).isEmpty()
        } finally {
            Thread.interrupted()
        }
    }

    private fun driver(device: Device) = HarmonyOSDriver(
        device,
        HarmonyOSDriverConfig(abilityName = "TestAbility", moduleName = "entry", pollInterval = Duration.ofMillis(1)),
    )

    private class Device : HarmonyOSDevice {
        var connections = 0
        var layoutReads = 0
        var screenshots = 0
        var image = png(80, 120)
        var layout: () -> TreeNode = { tree("ready") }
        var output: String? = null
        var executeFailure: Throwable? = null
        var screenshotFailure: Throwable? = null
        var recordingFactory: ((Sink, HarmonyOSDriverConfig) -> ScreenRecording)? = null
        var recordingStarts = 0
        var recordingSink: Sink? = null
        var recordingConfig: HarmonyOSDriverConfig? = null
        val commands = mutableListOf<List<String>>()
        val layoutBudgets = mutableListOf<Duration>()
        val screenshotBudgets = mutableListOf<Duration>()
        var beforeScreenshot: () -> Unit = {}
        override fun connect() { connections++ }
        override fun isAvailable(): Boolean = true
        override fun hierarchy(timeout: Duration): TreeNode { layoutReads++; layoutBudgets += timeout; return layout() }
        override fun screenshot(timeout: Duration): ByteArray {
            screenshots++
            screenshotBudgets += timeout
            beforeScreenshot()
            screenshotFailure?.let { throw it }
            return image
        }
        override fun execute(arguments: List<String>, timeout: Duration): String {
            commands += arguments
            executeFailure?.let { throw it }
            return output ?: when (arguments.take(2)) {
                listOf("aa", "start") -> "start ability successfully."
                listOf("aa", "force-stop") -> "force stop process successfully."
                else -> ""
            }
        }
        override fun startScreenRecording(out: Sink, config: HarmonyOSDriverConfig): ScreenRecording {
            recordingStarts++
            recordingSink = out
            recordingConfig = config
            return recordingFactory?.invoke(out, config) ?: super<HarmonyOSDevice>.startScreenRecording(out, config)
        }
    }

    private class Recording(private val closeFailure: Throwable? = null) : ScreenRecording {
        override val startedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
        var closeCalls = 0
        override fun close() {
            closeCalls++
            closeFailure?.let { throw it }
        }
    }

    companion object {
        private fun tree(text: String) = TreeNode(attributes = mutableMapOf("text" to text))

        private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
            out.toByteArray()
        }
    }
}
