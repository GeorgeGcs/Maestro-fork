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
import maestro.filterOutOfBounds
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.harmonyos.driver.HarmonyOSBundleMetadata
import maestro.harmonyos.driver.HarmonyOSCommandException
import maestro.harmonyos.driver.HarmonyOSDevice
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.driver.HarmonyOSFlowFaults
import maestro.harmonyos.driver.HarmonyOSHostFrameRecording
import maestro.harmonyos.driver.HarmonyOSScreenFrame
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
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.concurrent.withLock

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
    }, config.arkWebPort, config.arkWebBundle, config.arkWebTarget, config.automaticLayoutRpc), config)

    @Volatile private var opened = false
    private var recording: ScreenRecording? = null
    private var recordingFailure: Throwable? = null
    private class FramePreview(val wantsFrame: () -> Boolean, val publish: (ByteArray) -> Unit)
    @Volatile private var preview: FramePreview? = null
    @Volatile private var hostRecording: HarmonyOSHostFrameRecording? = null
    @Volatile private var frameSampler: ScheduledExecutorService? = null
    private val pendingFrame = AtomicReference<HarmonyOSScreenFrame?>()
    private val transport = ReentrantLock(true)
    @Volatile private var lastForegroundIo = 0L
    @Volatile private var queryFailure: HdcTimeoutException? = null
    private var useDisplaySize = false
    private var lastDeviceInfo: DeviceInfo? = null
    private data class HierarchySample(val root: TreeNode, val finishedAt: Long)
    private var lastHierarchy: HierarchySample? = null

    override fun name(): String = "HarmonyOS"

    val strictFaultDiagnostics: Boolean get() = config.strictFaultDiagnostics

    fun flowFaults(appId: String, startMs: Long, endMs: Long): HarmonyOSFlowFaults? = transport.withLock {
        if (!opened || queryFailure != null) null else
            device.flowFaults(appId, startMs, endMs, minOf(config.commandTimeout, Duration.ofSeconds(6)))
    }

    @Synchronized
    override fun open() {
        queryFailure?.let { throw it }
        if (opened) return
        checkInterrupted()
        device.connect()
        try {
            val image = capture(config.commandTimeout).image
            useDisplaySize = query { device.displaySize(config.commandTimeout) } == (image.width to image.height)
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
        lastHierarchy = null
        lastDeviceInfo = null
        val current = recording
        recording = null
        preview = null
        val sampler = frameSampler
        frameSampler = null
        try {
            stopFrameSampler(sampler)
        } finally {
            try {
                current?.close()
            } catch (error: Throwable) {
                rememberRecordingFailure(error)
            } finally {
                pendingFrame.set(null)
                device.disconnect()
            }
        }
        recordingFailure?.let { throw it }
    }

    override fun deviceInfo(): DeviceInfo {
        requireOpen()
        // UITest coordinates and bounds are physical pixels, including system bars.
        val size = if (useDisplaySize) {
            query { device.displaySize(config.commandTimeout) }
        } else null
        val (width, height) = size ?: capture(config.commandTimeout).image.let { it.width to it.height }
        return DeviceInfo(Platform.HARMONYOS, width, height, width, height).also { lastDeviceInfo = it }
    }

    private var activeAppId: String? = null

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        requireOpen()
        requireIdentifier(appId, "Bundle name")
        val discovered = if (config.abilityName == null) {
            val output = query { device.execute(listOf("bm", "dump", "-n", appId), config.commandTimeout) }
            val bundle = HarmonyOSBundleMetadata.parse(output, appId)
            bundle?.let { HarmonyOSBundleMetadata.launchTarget(it, config.moduleName) }
                ?: throw IllegalStateException(
                    "Could not determine a unique main Ability for $appId. Set MAESTRO_HARMONYOS_ABILITY to choose one explicitly.",
                )
        } else null
        val ability = config.abilityName ?: checkNotNull(discovered).abilityName
        // https://github.com/openharmony/docs/blob/master/en/application-dev/tools/aa-tool.md
        val arguments = mutableListOf("aa", "start", "-b", appId, "-a", ability)
        (config.moduleName ?: discovered?.moduleName)?.let { arguments += listOf("-m", it) }
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
        val launchWait = minOf(config.commandTimeout, Duration.ofSeconds(12))
        val firstWait = if (launchArguments.isEmpty()) launchWait.dividedBy(2) else launchWait
        if (awaitAppFocus(appId, firstWait)) { activeAppId = appId; return }
        if (launchArguments.isEmpty()) {
            // Activation can be acknowledged before the window manager finishes switching.
            aa(arguments, "start ability successfully.")
            if (awaitAppFocus(appId, launchWait.minus(firstWait))) { activeAppId = appId; return }
        }
        throw HarmonyOSCommandException("Application $appId did not acquire window focus after aa start.", "", "")
    }

    private fun awaitAppFocus(appId: String, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        var previousSampleFocused = false
        while (System.nanoTime() < deadline) {
            val remaining = Duration.ofNanos(deadline - System.nanoTime())
            if (remaining <= Duration.ZERO) return false
            val focused = query {
                try {
                    device.hasAppFocus(appId, minOf(Duration.ofSeconds(2), remaining))
                } catch (error: maestro.harmonyos.driver.HarmonyOSWindowFormatException) {
                    throw error
                } catch (_: HdcException) {
                    checkInterrupted()
                    false
                }
            }
            if (focused && previousSampleFocused) return true
            previousSampleFocused = focused
            if (!pause(deadline)) return false
        }
        return false
    }

    override fun stopApp(appId: String) {
        requireOpen()
        requireIdentifier(appId, "Bundle name")
        aa(listOf("aa", "force-stop", appId), "force stop process successfully.")
        if (activeAppId == appId) activeAppId = null
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
        return hierarchy().also { lastHierarchy = HierarchySample(it, System.nanoTime()) }
    }

    override fun scrollVertical() = swipe(SwipeDirection.UP, 400)

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        swipe(start, end, durationMs, deviceInfo())
    }

    private fun swipe(start: Point, end: Point, durationMs: Long, info: DeviceInfo) {
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
        swipe(start, end, durationMs, info)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val info = deviceInfo()
        val end = when (direction) {
            SwipeDirection.UP -> Point(elementPoint.x, maxOf(1, elementPoint.y - info.heightGrid * 3 / 5))
            SwipeDirection.DOWN -> Point(elementPoint.x, minOf(info.heightGrid - 1, elementPoint.y + info.heightGrid * 3 / 5))
            SwipeDirection.LEFT -> Point(maxOf(1, elementPoint.x - info.widthGrid * 3 / 5), elementPoint.y)
            SwipeDirection.RIGHT -> Point(minOf(info.widthGrid - 1, elementPoint.x + info.widthGrid * 3 / 5), elementPoint.y)
        }
        swipe(elementPoint, end, durationMs, info)
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
        val sample = lastHierarchy
        lastHierarchy = null
        val info = lastDeviceInfo
        var previous = sample?.root?.takeIf { root ->
            initialHierarchy != null && info != null &&
                System.nanoTime() - sample.finishedAt <= config.pollInterval.toNanos().coerceAtLeast(250_000_000) &&
                (root.filterOutOfBounds(info.widthGrid, info.heightGrid) ?: root) == initialHierarchy.root
        }
        var previousSampleNanos = 0L
        // Reuse a matching pre-action sample from this driver. Input invalidates the sample.
        // Post-action settling still needs two fresh samples.
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

    override fun clearAppState(appId: String) {
        requireOpen()
        requireIdentifier(appId, "Bundle name")
        val metadata = query { device.execute(listOf("bm", "dump", "-n", appId), config.commandTimeout) }
        when (HarmonyOSBundleMetadata.dataClearSupport(HarmonyOSBundleMetadata.parse(metadata, appId))) {
            HarmonyOSBundleMetadata.DataClearSupport.SUPPORTED -> Unit
            HarmonyOSBundleMetadata.DataClearSupport.UNSUPPORTED -> throw UnsupportedOperationException(
                "clearState requires a regular application whose user data can be cleared. Atomic services are unsupported.",
            )
            HarmonyOSBundleMetadata.DataClearSupport.UNKNOWN -> throw IllegalStateException(
                "The installed bundle did not confirm its application type and data-clear support for clearState.",
            )
        }
        lastHierarchy = null
        // Close the app before removal so a running process cannot save its previous state again.
        stopApp(appId)
        val output = query { device.execute(listOf("bm", "clean", "-d", "-n", appId), config.commandTimeout) }
        if (output.lineSequence().none { it.trim() == "clean bundle data files successfully." }) {
            throw HarmonyOSCommandException("bm did not confirm that application data was cleared", output, "")
        }
    }
    override fun clearKeychain(): Unit = unsupported("clearKeychain")
    override fun isKeyboardVisible(): Boolean {
        requireOpen()
        return query { device.keyboardVisible(config.commandTimeout) }
    }
    override fun hideKeyboard() {
        requireOpen()
        if (!isKeyboardVisible()) return
        val appId = activeAppId ?: throw IllegalStateException("Launch the target app before dismissing its keyboard.")
        if (!query { device.hasAppFocus(appId, config.commandTimeout) }) {
            throw HarmonyOSCommandException("Cannot dismiss a keyboard outside the target application $appId.", "", "")
        }
        input("keyEvent", "Back")
        val end = System.nanoTime() + config.commandTimeout.toNanos()
        while (isKeyboardVisible()) {
            if (!pause(end)) throw HarmonyOSCommandException("The keyboard remained visible after Back.", "", "")
        }
        if (!query { device.hasAppFocus(appId, config.commandTimeout) }) {
            throw HarmonyOSCommandException("The keyboard closed but $appId did not retain window focus.", "", "")
        }
    }
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        requireOpen()
        if (autoVerify) unsupported("openLink autoVerify")
        val uri = try { URI(link) } catch (_: URISyntaxException) {
            throw IllegalArgumentException("openLink requires a valid absolute URI.")
        }
        require(uri.isAbsolute && link.length <= 4096 && link.none(Char::isISOControl)) {
            "openLink requires a valid absolute URI of at most 4096 characters."
        }
        val web = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
        require(!browser || web) { "openLink browser mode requires an HTTP or HTTPS URL." }
        if (web) require(uri.host != null) { "openLink HTTP and HTTPS URLs require a host." }
        val arguments = listOf("aa", "start") + (if (web) listOf("-A", "ohos.want.action.viewData") else emptyList()) +
            listOf("-U", uri.toASCIIString())
        lastDeviceInfo = null
        aa(arguments, "start ability successfully.")
    }
    @Synchronized
    override fun startScreenRecording(out: Sink): ScreenRecording {
        requireOpen()
        if (!config.enableScreenRecording && config.hostRecordingFfmpeg == null) unsupported("screen recording without an enabled backend")
        recordingFailure?.let { throw it }
        check(recording == null) { "This driver already owns a recording" }
        val host = config.hostRecordingFfmpeg?.let {
            HarmonyOSHostFrameRecording(out, it, config.recordingMaxDuration, config.recordingMaxBytes, config.recordingStopTimeout)
        }
        try {
            val current = host ?: transport.withLock { device.startScreenRecording(out, config) }
            val handle = object : ScreenRecording {
                override val startedAt = current.startedAt
                private var closed = false
                override fun close() {
                    synchronized(this@HarmonyOSDriver) {
                        if (closed) return
                        closed = true
                        if (host != null) hostRecording = null
                        try {
                            if (host != null) current.close() else transport.withLock { current.close() }
                        } catch (error: Throwable) {
                            // The host encoder owns no system recording or remote media.
                            if (host == null) rememberRecordingFailure(error)
                            throw error
                        } finally {
                            if (recording === this) recording = null
                        }
                    }
                }
            }
            recording = handle
            if (host != null) {
                hostRecording = host
                startFrameSampler()
            }
            return handle
        } catch (error: Throwable) {
            if (host == null) rememberRecordingFailure(error)
            throw error
        }
    }

    @Synchronized
    fun startFramePreview(wantsFrame: () -> Boolean, publish: (ByteArray) -> Unit) {
        requireOpen()
        preview = FramePreview(wantsFrame, publish)
        startFrameSampler()
    }

    fun stopFramePreview() { preview = null }

    private fun startFrameSampler() {
        if (frameSampler != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "maestro-harmonyos-screen-frames").apply { isDaemon = true }
        }
        frameSampler = executor
        executor.scheduleWithFixedDelay(::sampleFrame, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun stopFrameSampler(sampler: ScheduledExecutorService?) {
        if (sampler == null) return
        sampler.shutdown()
        if (!sampler.awaitTermination(5, TimeUnit.SECONDS)) {
            sampler.shutdownNow()
            check(sampler.awaitTermination(2, TimeUnit.SECONDS)) { "Screenshot sampling did not stop before device release." }
        }
    }

    private fun sampleFrame() {
        if (!opened || queryFailure != null) return
        val viewer = preview?.takeIf { it.wantsFrame() }
        val recorder = hostRecording?.takeIf { it.wantsFrame() }
        if (viewer == null && recorder == null) return
        val saved = pendingFrame.getAndSet(null)?.takeIf { System.nanoTime() - it.capturedAt <= 2_000_000_000 }
        val frame = saved ?: try {
            if (!transport.tryLock(0, TimeUnit.NANOSECONDS)) return
            try {
                if (!opened || queryFailure != null || System.nanoTime() - lastForegroundIo < 350_000_000) return
                val bytes = try {
                    device.previewFrame(minOf(config.commandTimeout, Duration.ofSeconds(3)))
                } catch (error: HdcTimeoutException) {
                    // Like an explicit screenshot, a timed-out command may still be running remotely.
                    queryFailure = error
                    throw error
                }
                HarmonyOSScreenFrame(bytes)
            } finally {
                transport.unlock()
            }
        } catch (_: HdcException) {
            return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        if (frame.bytes.size !in 8..8 * 1024 * 1024 ||
            !frame.bytes.copyOf(8).contentEquals(PNG_SIGNATURE)) return
        recorder?.offer(frame)
        if (viewer != null && preview === viewer) viewer.publish(frame.bytes)
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    private fun rememberRecordingFailure(error: Throwable) {
        val first = recordingFailure
        if (first == null) recordingFailure = error
        else if (first !== error && error !in first.suppressed) first.addSuppressed(error)
    }
    override fun setLocation(latitude: Double, longitude: Double): Unit = unsupported("setLocation")
    override fun setOrientation(orientation: DeviceOrientation) {
        requireOpen()
        lastHierarchy = null
        lastDeviceInfo = null
        val rotation = when (orientation) {
            DeviceOrientation.PORTRAIT -> 0
            DeviceOrientation.LANDSCAPE_LEFT -> 1
            DeviceOrientation.UPSIDE_DOWN -> 2
            DeviceOrientation.LANDSCAPE_RIGHT -> 3
        }
        query { device.setDisplayRotation(rotation, config.commandTimeout) }
    }
    override fun setProxy(host: String, port: Int): Unit = unsupported("setProxy")
    override fun resetProxy(): Unit = unsupported("resetProxy")
    override fun addMedia(mediaFiles: List<File>) {
        requireOpen()
        query { device.addMedia(mediaFiles.map(File::toPath), config.commandTimeout) }
    }
    override fun isAirplaneModeEnabled(): Boolean = unsupported("airplane mode detection")
    override fun setAirplaneMode(enabled: Boolean): Unit = unsupported("setAirplaneMode")
    override fun isDarkModeEnabled(): Boolean = unsupported("dark mode detection")
    override fun setDarkMode(enabled: Boolean): Unit = unsupported("setDarkMode")

    private fun input(vararg arguments: String) {
        requireOpen()
        lastHierarchy = null
        query { device.execute(listOf("uitest", "uiInput") + arguments, config.commandTimeout) }
    }

    private fun aa(arguments: List<String>, expectedSuccess: String) {
        lastHierarchy = null
        val output = query { device.execute(arguments, config.commandTimeout) }
        if (output.lineSequence().none { it.trim() == expectedSuccess }) {
            throw HarmonyOSCommandException("aa did not confirm success", output, "")
        }
    }

    private data class Screenshot(val bytes: ByteArray, val image: BufferedImage)

    private fun capture(timeout: Duration): Screenshot {
        return query {
            val bytes = device.screenshot(timeout)
            val image = decodeScreenshot(bytes)
            if (frameSampler != null && (preview != null || hostRecording != null)) pendingFrame.set(HarmonyOSScreenFrame(bytes))
            Screenshot(bytes, image)
        }
    }

    private fun hierarchy(): TreeNode {
        return query { device.hierarchy(config.commandTimeout) }
    }

    private fun <T> query(action: () -> T): T = transport.withLock {
        queryFailure?.let { throw it }
        try {
            action()
        } catch (error: HdcTimeoutException) {
            // Deleting a capture file does not prove the timed-out remote command ended.
            // Keep completion hooks, failure screenshots and external callers from
            // issuing more device work in this session. close() can still finish recordings.
            queryFailure = error
            throw error
        } finally {
            lastForegroundIo = System.nanoTime()
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
