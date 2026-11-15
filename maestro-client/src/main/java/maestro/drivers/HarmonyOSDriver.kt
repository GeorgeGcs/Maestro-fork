package maestro.drivers

import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.harmonyos.driver.HarmonyOSCommandException
import maestro.harmonyos.driver.HarmonyOSDevice
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.harmonyos.driver.requireIdentifier
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import okio.Buffer
import okio.Sink
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Host driver for devices exposing the OpenHarmony UITest shell commands. */
class HarmonyOSDriver(
    private val device: HarmonyOSDevice,
    private val config: HarmonyOSDriverConfig = HarmonyOSDriverConfig(),
) : Driver {
    constructor(
        deviceId: String,
        hdc: HdcClient = HdcClient(),
        config: HarmonyOSDriverConfig = HarmonyOSDriverConfig(),
    ) : this(HdcHarmonyOSDevice(deviceId, hdc, config.layoutRpcPort.also {
        require(it == null || deviceId == config.layoutRpcDevice) { "Layout RPC target differs from the selected device" }
    }), config)

    @Volatile private var opened = false
    private var recording: ScreenRecording? = null
    private var recordingFailure: Throwable? = null
    var previewFrame: ((ByteArray) -> Unit)? = null
    private var queryFailure: HdcTimeoutException? = null

    override fun name(): String = "HarmonyOS"

    @Synchronized
    override fun open() {
        queryFailure?.let { throw it }
        if (opened) return
        checkInterrupted()
        device.connect()
        try {
            capture(config.commandTimeout)
            hierarchy()
            opened = true
        } catch (error: Throwable) {
            device.disconnect()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        opened = false
        val current = recording
        recording = null
        try {
            current?.close()
        } catch (error: Throwable) {
            rememberRecordingFailure(error)
        } finally {
            device.disconnect()
        }
        recordingFailure?.let { throw it }
    }

    override fun deviceInfo(): DeviceInfo {
        requireOpen()
        val image = capture(config.commandTimeout).image
        // UITest coordinates and bounds are physical pixels, including system bars.
        return DeviceInfo(Platform.HARMONYOS, image.width, image.height, image.width, image.height)
    }

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        requireOpen()
        requireIdentifier(appId, "Bundle name")
        val ability = config.abilityName ?: throw IllegalStateException(
            "Set MAESTRO_HARMONYOS_ABILITY (or HarmonyOSDriverConfig.abilityName) to the app's launch ability",
        )
        // https://github.com/openharmony/docs/blob/master/en/application-dev/tools/aa-tool.md
        val arguments = mutableListOf("aa", "start", "-b", appId, "-a", ability)
        config.moduleName?.let { arguments += listOf("-m", it) }
        launchArguments.forEach { (key, value) ->
            requireIdentifier(key, "Launch argument name")
            arguments += when (value) {
                is String -> {
                    require('\u0000' !in value && !value.startsWith('-')) {
                        "aa string launch arguments cannot contain NUL or start with '-'"
                    }
                    if (value.isEmpty()) listOf("--psn", key) else listOf("--ps", key, value)
                }
                is Boolean -> listOf("--pb", key, value.toString())
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    require(number in 0..Int.MAX_VALUE.toLong()) { "aa integer launch arguments must be between 0 and ${Int.MAX_VALUE}" }
                    listOf("--pi", key, number.toString())
                }
                else -> unsupported("launch arguments of type ${value.javaClass.simpleName}")
            }
        }
        aa(arguments, "start ability successfully.")
    }

    override fun stopApp(appId: String) {
        requireOpen()
        requireIdentifier(appId, "Bundle name")
        aa(listOf("aa", "force-stop", appId), "force stop process successfully.")
    }

    override fun killApp(appId: String) = stopApp(appId)

    override fun tap(point: Point) {
        validatePoint(point, deviceInfo())
        input("click", point.x.toString(), point.y.toString())
    }

    override fun longPress(point: Point) {
        validatePoint(point, deviceInfo())
        input("longClick", point.x.toString(), point.y.toString())
    }

    override fun pressKey(code: KeyCode) {
        val key = when (code) {
            KeyCode.BACK -> "Back"
            KeyCode.HOME -> "Home"
            KeyCode.POWER -> "Power"
            // https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-input-kit/js-apis-keycode.md
            KeyCode.ENTER -> "2054"
            KeyCode.BACKSPACE -> "2055"
            KeyCode.TAB -> "2049"
            KeyCode.ESCAPE -> "2070"
            KeyCode.VOLUME_UP -> "16"
            KeyCode.VOLUME_DOWN -> "17"
            else -> unsupported("pressKey ${code.description}")
        }
        input("keyEvent", key)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        requireOpen()
        if (excludeKeyboardElements) unsupported("excluding keyboard elements from the hierarchy")
        return hierarchy()
    }

    override fun scrollVertical() = swipe(SwipeDirection.UP, 400)

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        val info = deviceInfo()
        validatePoint(start, info)
        validatePoint(end, info)
        require(durationMs > 0) { "Swipe duration must be positive" }
        require(start != end) { "Swipe endpoints must differ" }
        // UITest accepts velocity, not duration; requested duration is approximate at its limits.
        val velocity = (hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()) * 1000 / durationMs)
            .roundToInt().coerceIn(200, 40000)
        input("swipe", start.x.toString(), start.y.toString(), end.x.toString(), end.y.toString(), velocity.toString())
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val info = deviceInfo()
        val center = Point(info.widthGrid / 2, info.heightGrid / 2)
        val (start, end) = when (swipeDirection) {
            SwipeDirection.UP -> Point(center.x, info.heightGrid * 4 / 5) to Point(center.x, info.heightGrid / 5)
            SwipeDirection.DOWN -> Point(center.x, info.heightGrid / 5) to Point(center.x, info.heightGrid * 4 / 5)
            SwipeDirection.LEFT -> Point(info.widthGrid * 4 / 5, center.y) to Point(info.widthGrid / 5, center.y)
            SwipeDirection.RIGHT -> Point(info.widthGrid / 5, center.y) to Point(info.widthGrid * 4 / 5, center.y)
        }
        swipe(start, end, durationMs)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val info = deviceInfo()
        val end = when (direction) {
            SwipeDirection.UP -> Point(elementPoint.x, maxOf(1, elementPoint.y - info.heightGrid * 3 / 5))
            SwipeDirection.DOWN -> Point(elementPoint.x, minOf(info.heightGrid - 1, elementPoint.y + info.heightGrid * 3 / 5))
            SwipeDirection.LEFT -> Point(maxOf(1, elementPoint.x - info.widthGrid * 3 / 5), elementPoint.y)
            SwipeDirection.RIGHT -> Point(minOf(info.widthGrid - 1, elementPoint.x + info.widthGrid * 3 / 5), elementPoint.y)
        }
        swipe(elementPoint, end, durationMs)
    }

    override fun backPress() = pressKey(KeyCode.BACK)

    override fun inputText(text: String) {
        requireOpen()
        require('\u0000' !in text) { "Input text cannot contain NUL" }
        if (text.isNotEmpty()) input("text", text)
    }

    override fun eraseText(charactersToErase: Int) {
        requireOpen()
        require(charactersToErase in 0..10000) { "eraseText requires a count between 0 and 10000" }
        repeat(charactersToErase) { pressKey(KeyCode.BACKSPACE) }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        requireOpen()
        val bytes = capture(config.commandTimeout).bytes
        val buffer = Buffer().write(bytes)
        out.write(buffer, bytes.size.toLong())
        out.flush()
    }

    override fun isShutdown(): Boolean = !opened || queryFailure != null || !device.isAvailable()

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        requireOpen()
        val deadline = deadline(timeoutMs)
        var previous: BufferedImage? = null
        var previousSampleNanos = 0L
        while (true) {
            if (!canSample(deadline, previousSampleNanos)) return false
            val started = System.nanoTime()
            val current = capture(config.commandTimeout).image
            previousSampleNanos = System.nanoTime() - started
            if (System.nanoTime() >= deadline) return false
            if (previous != null && imagesEqual(previous, current)) return true
            previous = current
            if (!pause(deadline)) return false
        }
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        requireOpen()
        val deadline = deadline(timeoutMs?.toLong() ?: config.settleTimeout.toMillis())
        var previous: TreeNode? = null
        var previousSampleNanos = 0L
        // Require two fresh samples. A caller's snapshot can predate the last input event.
        while (true) {
            if (!canSample(deadline, previousSampleNanos)) return null
            val started = System.nanoTime()
            val current = hierarchy()
            previousSampleNanos = System.nanoTime() - started
            if (System.nanoTime() >= deadline) return null
            if (previous == current) return ViewHierarchy(current)
            previous = current
            if (!pause(deadline)) return null
        }
    }

    override fun capabilities(): List<Capability> = emptyList()

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        requireOpen()
        if (permissions.isNotEmpty()) unsupported("setting app permissions")
    }

    override fun clearAppState(appId: String): Unit = unsupported("clearState")
    override fun clearKeychain(): Unit = unsupported("clearKeychain")
    override fun isKeyboardVisible(): Boolean = unsupported("keyboard visibility detection")
    override fun hideKeyboard(): Unit = unsupported("hideKeyboard; use an app control to dismiss the keyboard")
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean): Unit = unsupported("openLink")
    @Synchronized
    override fun startScreenRecording(out: Sink): ScreenRecording {
        requireOpen()
        if (!config.enableScreenRecording) unsupported("screen recording without explicit enableScreenRecording configuration")
        recordingFailure?.let { throw it }
        check(recording == null) { "This driver already owns a recording" }
        try {
            val current = device.startScreenRecording(out, config)
            val handle = object : ScreenRecording {
                override val startedAt = current.startedAt
                private var closed = false
                override fun close() {
                    synchronized(this@HarmonyOSDriver) {
                        if (closed) return
                        closed = true
                        try {
                            current.close()
                        } catch (error: Throwable) {
                            rememberRecordingFailure(error)
                            throw error
                        } finally {
                            if (recording === this) recording = null
                        }
                    }
                }
            }
            recording = handle
            return handle
        } catch (error: Throwable) {
            rememberRecordingFailure(error)
            throw error
        }
    }

    private fun rememberRecordingFailure(error: Throwable) {
        val first = recordingFailure
        if (first == null) recordingFailure = error
        else if (first !== error && error !in first.suppressed) first.addSuppressed(error)
    }
    override fun setLocation(latitude: Double, longitude: Double): Unit = unsupported("setLocation")
    override fun setOrientation(orientation: DeviceOrientation): Unit = unsupported("setOrientation")
    override fun setProxy(host: String, port: Int): Unit = unsupported("setProxy")
    override fun resetProxy(): Unit = unsupported("resetProxy")
    override fun addMedia(mediaFiles: List<File>): Unit = unsupported("addMedia")
    override fun isAirplaneModeEnabled(): Boolean = unsupported("airplane mode detection")
    override fun setAirplaneMode(enabled: Boolean): Unit = unsupported("setAirplaneMode")
    override fun isDarkModeEnabled(): Boolean = unsupported("dark mode detection")
    override fun setDarkMode(enabled: Boolean): Unit = unsupported("setDarkMode")

    private fun input(vararg arguments: String) {
        requireOpen()
        device.execute(listOf("uitest", "uiInput") + arguments, config.commandTimeout)
    }

    private fun aa(arguments: List<String>, expectedSuccess: String) {
        val output = device.execute(arguments, config.commandTimeout)
        if (output.lineSequence().none { it.trim() == expectedSuccess }) {
            throw HarmonyOSCommandException("aa did not confirm success", output, "")
        }
    }

    private data class Screenshot(val bytes: ByteArray, val image: BufferedImage)

    private fun capture(timeout: Duration): Screenshot {
        return query {
            val bytes = device.screenshot(timeout)
            val image = decodeScreenshot(bytes)
            previewFrame?.invoke(bytes)
            Screenshot(bytes, image)
        }
    }

    private fun hierarchy(): TreeNode {
        return query { device.hierarchy(config.commandTimeout) }
    }

    private fun <T> query(action: () -> T): T {
        queryFailure?.let { throw it }
        try {
            return action()
        } catch (error: HdcTimeoutException) {
            // Deleting a capture file does not prove the timed-out remote command ended.
            // Keep completion hooks, failure screenshots and external callers from
            // issuing more device work in this session. close() can still finish recordings.
            queryFailure = error
            throw error
        }
    }

    private fun decodeScreenshot(bytes: ByteArray): BufferedImage {
        if (bytes.size > 32 * 1024 * 1024) throw HdcException("Screenshot exceeds 32 MiB")
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) throw HdcException("UITest screenshot is not a readable PNG")
            val reader = readers.next()
            try {
                if (!reader.formatName.equals("png", ignoreCase = true)) throw HdcException("UITest screenshot is not PNG")
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) {
                    throw HdcException("Screenshot dimensions exceed the supported pixel limit")
                }
                return reader.read(0) ?: throw HdcException("UITest screenshot could not be decoded")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun imagesEqual(first: BufferedImage, second: BufferedImage): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val width = first.width
        val height = first.height
        return first.getRGB(0, 0, width, height, null, 0, width)
            .contentEquals(second.getRGB(0, 0, width, height, null, 0, width))
    }

    private fun validatePoint(point: Point, info: DeviceInfo) {
        // UITest GetPoint/GetPoints reject zero as well as negative coordinates.
        require(point.x in 1 until info.widthGrid && point.y in 1 until info.heightGrid) {
            "UITest point (${point.x}, ${point.y}) must be positive and inside ${info.widthGrid} x ${info.heightGrid} screen"
        }
    }

    private fun deadline(timeoutMs: Long): Long {
        require(timeoutMs in 0..Duration.ofMinutes(5).toMillis()) { "Wait timeout must be between 0 and 5 minutes" }
        return System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos()
    }

    private fun canSample(deadline: Long, previousNanos: Long): Boolean {
        checkInterrupted()
        // The window admits samples; it must not truncate a remote query to a few ms.
        // A final sample can overrun the window: its full command budget plus the
        // backend's bounded termination/cleanup still apply. Skip a tail that cannot fit even
        // the last observed sample, without inventing a device-specific threshold.
        return deadline - System.nanoTime() >= maxOf(1L, previousNanos)
    }

    private fun pause(deadline: Long): Boolean {
        val nanos = minOf(config.pollInterval.toNanos(), deadline - System.nanoTime())
        if (nanos <= 0) return false
        try {
            Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
        return true
    }

    private fun requireOpen() {
        check(opened) { "HarmonyOS driver is not open" }
        checkInterrupted()
        queryFailure?.let { throw it }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("HarmonyOS operation interrupted")
    }

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException("HarmonyOS driver does not support $operation yet")
}
