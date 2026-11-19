package maestro.harmonyos.driver

import maestro.TreeNode
import maestro.ScreenRecording
import okio.Sink
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandCategory
import maestro.harmonyos.hdc.HdcCommandException
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.layout.HierarchyReader
import maestro.harmonyos.layout.ArkWebLoopback
import maestro.harmonyos.layout.LoopbackLayoutChannel
import maestro.harmonyos.layout.UitestLayoutException
import org.slf4j.LoggerFactory
import maestro.utils.TempFileHandler
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class HarmonyOSCommandException(
    message: String,
    val stdout: String,
    val stderr: String,
    val remoteExitCode: Int? = null,
    cause: Throwable? = null,
) : HdcException("$message\n${stdout.trim()}\n${stderr.trim()}".trim(), cause)

/**
 * UITest command reference:
 * https://github.com/openharmony/testfwk_arkxtest/blob/f59296c77aac5037a9335c317af58de3a0c22ecb/uitest/server/server_main.cpp
 */
class HdcHarmonyOSDevice(
    private val deviceId: String,
    private val hdc: HdcClient = HdcClient(),
    private val layoutPort: Int? = null,
    private val arkWebPort: Int? = null,
    private val arkWebBundle: String? = null,
    private val arkWebTarget: HarmonyOSArkWebTarget? = null,
    private val automaticLayoutRpc: Boolean = false,
) : HarmonyOSDevice {
    private var layoutChannel: LoopbackLayoutChannel? = null
    private var displayRpcUnsupported = false
    private var reportedArkWebSource: String? = null
    private val arkWeb = arkWebPort?.let { ArkWebLoopback(it, arkWebTarget) }
    private var arkWebWindow: String? = null

    override fun disconnect() {
        layoutChannel?.close()
        layoutChannel = null
        displayRpcUnsupported = false
        reportedArkWebSource = null
        arkWeb?.close()
        arkWebWindow = null
    }
    override fun connect() {
        hdc.selectDevice(deviceId)
        val timeout = Duration.ofSeconds(2)
        val port = when {
            layoutPort != null -> layoutPort.takeIf { hasLayoutForward(it, timeout) }
            automaticLayoutRpc -> try {
                hdc.openAutomaticUitestLayoutForward(deviceId, timeout)
            } catch (error: HdcException) {
                checkInterrupted()
                logger.debug("Automatic UITest forward failed ({})", error.javaClass.simpleName)
                null
            }
            else -> return
        }
        val mode = if (layoutPort == null) "automatic" else "explicit"
        if (port == null) logger.info("UITest RPC ({}) unavailable; using HDC layout for this session", mode)
        else {
            layoutChannel = LoopbackLayoutChannel(port)
            logger.info("UITest RPC layout enabled for this session ({})", mode)
        }
    }

    override fun isAvailable(): Boolean = deviceId in hdc.listDevices()

    override fun hasAppFocus(appId: String, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        val windows = diagnostic(listOf("hidumper", "-s", "WindowManagerService", "-a", "-a"), timeout)
        val pid = HarmonyOSWindowState(windows).focusedPid()
        val processes = diagnostic(listOf("ps", "-A", "-o", "PID,NAME"), remainingBudget(deadline, timeout))
        return processes.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
            .any { it.size == 2 && it[0] == pid && (it[1] == appId || it[1].startsWith("$appId:")) }
    }

    override fun keyboardVisible(timeout: Duration): Boolean =
        HarmonyOSWindowState(diagnostic(listOf("hidumper", "-s", "WindowManagerService", "-a", "-a"), timeout)).keyboardVisible()

    override fun flowFaults(appId: String, startMs: Long, endMs: Long, timeout: Duration): HarmonyOSFlowFaults {
        val deadline = System.nanoTime() + timeout.toNanos()
        val before = System.currentTimeMillis()
        val clockStarted = System.nanoTime()
        val deviceTime = try {
            diagnostic(listOf("date", "+%s%3N"), minOf(Duration.ofSeconds(1), timeout.dividedBy(3)))
                .trim().takeIf { it.matches(Regex("[0-9]{13}")) }?.toLongOrNull()
        } catch (_: HdcException) {
            checkInterrupted()
            null
        }
        val after = System.currentTimeMillis()
        val elapsed = Duration.ofNanos(System.nanoTime() - clockStarted).toMillis()
        val clock = if (deviceTime != null && after >= before && elapsed <= 1000 &&
            kotlin.math.abs(after - before - elapsed) <= 10
        ) HarmonyOSFaultClock(before - deviceTime - 2, after - deviceTime + 2) else null
        // Broaden the device-side query for the offset interval. The parser admits only events provably in the Flow window.
        val deviceStart = startMs - (clock?.maximumOffsetMs ?: 0)
        val deviceEnd = endMs - (clock?.minimumOffsetMs ?: 0)
        val output = diagnostic(
            listOf("hisysevent", "-l", "-s", deviceStart.toString(), "-e", deviceEnd.toString(), "-g", "FAULT", "-m", "256"),
            remainingBudget(deadline, timeout),
        )
        return parseFlowFaults(output, appId, startMs, endMs, clock)
    }

    override fun displaySize(timeout: Duration): Pair<Int, Int>? {
        val channel = layoutChannel
        if (channel != null && !displayRpcUnsupported && timeout >= Duration.ofMillis(2)) {
            val deadline = System.nanoTime() + timeout.toNanos()
            try {
                return channel.displaySize(minOf(timeout.dividedBy(2), Duration.ofSeconds(2)))
            } catch (error: HdcException) {
                checkInterrupted()
                displayRpcUnsupported = true
                logger.info("UITest RPC display size unavailable ({}); using the display service", error.javaClass.simpleName)
            }
            return displaySizeFromService(remainingBudget(deadline, timeout))
        }
        return displaySizeFromService(timeout)
    }

    override fun setDisplayRotation(rotation: Int, timeout: Duration) {
        require(rotation in 0..3)
        val channel = layoutChannel
            ?: throw UnsupportedOperationException("setOrientation requires an active UITest RPC endpoint.")
        val deadline = System.nanoTime() + timeout.toNanos()
        fun budget() = minOf(Duration.ofSeconds(3), remainingBudget(deadline, timeout))
        var observed = channel.displayRotation(budget())
        if (observed == rotation) return
        hdc.externalMutation(listOf("uitest-rpc", "setDisplayRotation", rotation.toString())) {
            channel.setDisplayRotation(rotation, budget())
        }
        fun confirmationTimeout(detail: String) = HdcTimeoutException(timeout,
            "UITest could not confirm ${rotation * 90} degrees (last observed: ${observed * 90} degrees). $detail " +
                "Keep a rotatable application in the foreground.")
        fun readbackBudget(): Duration {
            checkInterrupted()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw confirmationTimeout("Rotation did not settle within ${timeout.toMillis()} ms.")
            }
            return Duration.ofNanos(remaining)
        }
        while (true) {
            val remaining = readbackBudget()
            observed = try {
                channel.displayRotation(minOf(Duration.ofSeconds(3), remaining))
            } catch (error: HdcTimeoutException) {
                throw confirmationTimeout(error.message.orEmpty()).also { it.addSuppressed(error) }
            }
            if (observed == rotation) return
            Thread.sleep(minOf(150, readbackBudget().toMillis() + 1))
        }
    }

    override fun addMedia(files: List<Path>, timeout: Duration) {
        if (files.isEmpty()) return
        require(files.size <= 32) { "addMedia supports at most 32 files per command." }
        val supported = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "mp4", "mov")
        val candidates = files.map { source ->
            val name = source.fileName?.toString().orEmpty()
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,180}")) &&
                name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in supported) {
                "addMedia requires a supported image/video file with a simple filename."
            }
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "addMedia requires an existing local file." }
            source.toAbsolutePath().normalize() to Files.size(source)
        }
        require(candidates.all { (_, size) -> size in 1..(128L * 1024 * 1024) } &&
            candidates.sumOf { it.second } <= 128L * 1024 * 1024) { "addMedia input must be nonempty and total at most 128 MiB." }
        require(candidates.map { it.first.fileName.toString().lowercase(java.util.Locale.ROOT) }.distinct().size == candidates.size) {
            "addMedia filenames must be distinct."
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        fun budget() = remainingBudget(deadline, timeout)
        val root = execute(listOf("sh", "-c", "ls -d /mnt/data/[0-9]*/media_fuse/Photo"), budget())
            .lineSequence().map(String::trim).filter { it.matches(Regex("/mnt/data/[0-9]+/media_fuse/Photo")) }.singleOrNull()
            ?: throw HdcException("addMedia requires exactly one accessible Gallery directory.")
        fun lookup(name: String): String? {
            val output = try {
                execute(listOf("mediatool", "query", name, "-u"), budget())
            } catch (error: HarmonyOSCommandException) {
                if (error.remoteExitCode == 255 && error.stdout.trim() == "find 0 result" && error.stderr.isEmpty()) return null
                throw error
            }
            val rows = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (rows == listOf("find 0 result")) return null
            val resource = Regex("file://media/Photo/[0-9]+/[A-Za-z0-9_.-]+/" + Regex.escape(name))
            val uri = rows.getOrNull(2)?.removeSurrounding("\"")
            if (rows.size != 3 || rows.take(2) != listOf("find 1 result", "uri") || uri == null || !uri.matches(resource)) {
                throw HdcException("Gallery did not confirm a unique URI for '$name'.")
            }
            return uri
        }
        candidates.forEach { (file, _) ->
            val name = file.fileName.toString()
            require(lookup(name) == null) { "Gallery already contains '$name'; addMedia does not overwrite it." }
        }
        var confirmed = 0
        try {
            candidates.forEach { (source, size) ->
                val name = source.fileName.toString()
                hdc.sendGalleryFile(deviceId, source, "$root/$name", size, budget())
                while (lookup(name) == null) Thread.sleep(minOf(200, budget().toMillis().coerceAtLeast(1)))
                confirmed++
            }
        } catch (error: Exception) {
            // Imported Gallery assets are deliberate outputs. Report the confirmed prefix and keep it inspectable.
            error.addSuppressed(HdcException("Gallery confirmed $confirmed of ${candidates.size} requested imports."))
            throw error
        }
    }

    private fun displaySizeFromService(timeout: Duration): Pair<Int, Int>? {
        val dump = try {
            execute(listOf("hidumper", "-s", "DisplayManagerService", "-a", "-a"), timeout)
        } catch (error: HdcTimeoutException) {
            throw error
        } catch (_: HdcException) {
            checkInterrupted()
            return null
        }
        return primaryDisplaySize(dump)
    }

    override fun startScreenRecording(out: Sink, config: HarmonyOSDriverConfig): ScreenRecording =
        HarmonyOSSystemScreenRecorder(deviceId, this, { remote, local, timeout ->
            hdc.receiveFile(deviceId, remote, local, timeout)
        }, config, hdc.recordingResources()).start(out)

    override fun hierarchy(timeout: Duration): TreeNode {
        val deadline = System.nanoTime() + timeout.toNanos()
        layoutChannel?.let { channel ->
            val rpcBudget = minOf(timeout.dividedBy(2), Duration.ofSeconds(2))
            if (rpcBudget >= Duration.ofMillis(1)) {
                try {
                    return enhanceArkWeb(channel.snapshot(rpcBudget), deadline, timeout)
                } catch (error: HdcException) {
                    disableLayoutRpc(channel, error)
                } catch (error: UitestLayoutException) {
                    disableLayoutRpc(channel, error)
                }
            }
        }
        val budget = remainingBudget(deadline, timeout)
        val nativeTree = HierarchyReader(
            hdc, deviceId,
            commandTimeout = budget,
            cleanupTimeout = Duration.ofSeconds(2),
            totalTimeout = budget,
        ).read()
        return enhanceArkWeb(nativeTree, deadline, timeout)
    }

    private fun enhanceArkWeb(nativeTree: TreeNode, deadline: Long, timeout: Duration): TreeNode {
        val port = arkWebPort ?: return nativeTree
        val bundle = arkWebBundle ?: return nativeTree
        val webViews = mutableListOf<TreeNode>()
        fun findWebViews(node: TreeNode, owner: String?) {
            val actualOwner = node.attributes["bundleName"]?.takeIf { it.isNotBlank() } ?: owner
            if (node.attributes["class"] == "Web" && actualOwner == bundle &&
                (arkWebTarget == null || node.attributes["resource-id"] == arkWebTarget.webViewId)
            ) webViews += node
            node.children.forEach { findWebViews(it, actualOwner) }
        }
        findWebViews(nativeTree, null)
        val webView = webViews.singleOrNull()
        val bounds = webView?.attributes?.get("bounds")
            ?.let { Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").matchEntire(it) }
            ?.groupValues?.drop(1)?.map(String::toInt)
        if (webView == null || bounds == null) return arkWebFallback(nativeTree)
        val window = webView.attributes["hostWindowId"]
        if (arkWebWindow != window) {
            arkWeb?.close()
            arkWebWindow = window
        }

        val dom = try {
            val budget = minOf(remainingBudget(deadline, timeout), Duration.ofSeconds(3))
            arkWeb?.snapshot(bounds, budget) { verificationBudget ->
                val webDeadline = System.nanoTime() + verificationBudget.toNanos()
                val pid = execute(listOf("pidof", bundle), remainingBudget(webDeadline, verificationBudget))
                pid.matches(Regex("[1-9][0-9]*")) &&
                    hdc.hasArkWebForward(deviceId, port, pid, remainingBudget(webDeadline, verificationBudget))
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            checkInterrupted()
            logger.debug("ArkWeb lookup fell back after {}", error.javaClass.simpleName)
            null
        } ?: return arkWebFallback(nativeTree)

        reportArkWebSource("DOM")
        fun merge(node: TreeNode): TreeNode = if (node === webView) {
            node.copy(children = listOf(dom))
        } else {
            node.copy(children = node.children.map(::merge))
        }
        return merge(nativeTree)
    }

    private fun arkWebFallback(nativeTree: TreeNode): TreeNode {
        arkWeb?.close()
        arkWebWindow = null
        reportArkWebSource("UITest")
        return nativeTree
    }

    private fun reportArkWebSource(source: String) {
        if (source != reportedArkWebSource) logger.info("ArkWeb hierarchy source: {}", source)
        reportedArkWebSource = source
    }

    private fun hasLayoutForward(port: Int, timeout: Duration): Boolean = try {
        hdc.hasUitestLayoutForward(deviceId, port, timeout)
    } catch (_: HdcException) {
        checkInterrupted()
        false
    }

    private fun disableLayoutRpc(channel: LoopbackLayoutChannel, error: Exception) {
        channel.close()
        layoutChannel = null
        checkInterrupted()
        logger.warn("UITest RPC layout failed ({}); using HDC layout for the rest of this session", error.javaClass.simpleName)
    }

    override fun execute(arguments: List<String>, timeout: Duration): String = executeWithReceipt(arguments, timeout, false)

    private fun diagnostic(arguments: List<String>, timeout: Duration): String = executeWithReceipt(arguments, timeout, true)

    private fun executeWithReceipt(arguments: List<String>, timeout: Duration, readOnly: Boolean): String {
        checkInterrupted()
        require(arguments.isNotEmpty()) { "A device command is required" }
        if (arguments.first() == "aa") {
            arkWeb?.close()
            arkWebWindow = null
        }
        val marker = "MAESTRO_EXIT_${UUID.randomUUID().toString().replace("-", "")}="
        val script = arguments.joinToString(" ") { HdcClient.quoteShellArgument(it) } +
            "; __maestro_status=\$?; printf '\\n${marker}%s\\n' \"\$__maestro_status\"; exit \"\$__maestro_status\""
        var transportFailure: HdcCommandException? = null
        val result = try {
            val category = HdcCommandCategory.shell(arguments)
            if (readOnly) hdc.shellDiagnostic(deviceId, script, timeout, category)
            else hdc.shellFinite(deviceId, listOf("sh", "-c", script), timeout, HdcClient.finiteCommand(arguments), category)
        } catch (error: HdcCommandException) {
            // HDC's text heuristic can identify a remote tool's [FAIL] output as a
            // transport error. Only this invocation's final status proves it ran.
            val confirmedStatus = remoteStatus(error.result.stdout, marker)
            if (confirmedStatus == null || error.result.exitCode != 0 &&
                (error.confirmedRemoteExitCode != confirmedStatus || error.result.exitCode != confirmedStatus)) throw error
            transportFailure = error
            error.result
        }
        checkInterrupted()
        val lines = result.stdout.trimEnd().lines()
        val status = remoteStatus(result.stdout, marker)
            ?: throw HarmonyOSCommandException(
                "Device command did not return its exit status", result.stdout, result.stderr,
            )
        val output = lines.dropLast(1).joinToString("\n").trimEnd()
        if (status != 0) {
            throw HarmonyOSCommandException("Device command failed (exit $status)", output, result.stderr, status, transportFailure)
        }
        if (DEVICE_FAILURE.containsMatchIn(output) || DEVICE_FAILURE.containsMatchIn(result.stderr)) {
            throw HarmonyOSCommandException("Device command reported a failure", output, result.stderr, status, transportFailure)
        }
        // ui_input.cpp returns EXIT_SUCCESS even for an ApiCallErr. Its successful message is
        // defined as "No Error" in uitest/core/frontend_error_defines.h at the revision above.
        if (arguments.take(2) == listOf("uitest", "uiInput") &&
            (output.trim() != "No Error" || result.stderr.isNotBlank())
        ) {
            throw HarmonyOSCommandException("UITest did not confirm input success", output, result.stderr, status, transportFailure)
        }
        return output
    }

    private fun remoteStatus(stdout: String, marker: String): Int? {
        val lastLine = stdout.trimEnd().lineSequence().lastOrNull().orEmpty()
        return lastLine.takeIf { it.startsWith(marker) }?.removePrefix(marker)?.toIntOrNull()
    }

    private var scaledFramesAvailable = true

    override fun previewFrame(timeout: Duration): ByteArray {
        if (!scaledFramesAvailable) return screenshot(timeout)
        val started = System.nanoTime()
        val size = displaySize(minOf(timeout.dividedBy(3), Duration.ofSeconds(1))) ?: return screenshot(timeout)
        val scale = minOf(1.0, 960.0 / maxOf(size.first, size.second))
        val width = maxOf(2, (size.first * scale).toInt() / 2 * 2)
        val height = maxOf(2, (size.second * scale).toInt() / 2 * 2)
        return try {
            captureScreen(remainingBudget(started + timeout.toNanos(), timeout), width to height)
        } catch (error: HarmonyOSCommandException) {
            checkInterrupted()
            // Only a confirmed remote rejection permits the legacy capture backend.
            scaledFramesAvailable = false
            logger.info("Scaled frame capture unavailable; using the original screenshot backend.")
            screenshot(remainingBudget(started + timeout.toNanos(), timeout))
        }
    }

    override fun screenshot(timeout: Duration): ByteArray = captureScreen(timeout, null)

    private fun captureScreen(timeout: Duration, dimensions: Pair<Int, Int>?): ByteArray {
        checkInterrupted()
        require(!timeout.isZero && !timeout.isNegative) { "Screenshot timeout must be positive" }
        val deadline = System.nanoTime() + timeout.toNanos()
        val remotePath = hdc.allocateTemporaryFile("screen", "png")
        val files = TempFileHandler()
        var localDirectory: Path? = null
        var localPath: Path? = null
        var captureAttempted = false
        var failure: Throwable? = null
        try {
            localDirectory = files.createTempDirectory("maestro-harmonyos-screen-").toPath()
            localPath = localDirectory.resolve("screen.png")
            captureAttempted = true
            val command = if (dimensions == null) listOf("uitest", "screenCap", "-p", remotePath)
                else listOf("snapshot_display", "-f", remotePath, "-w", dimensions.first.toString(),
                    "-h", dimensions.second.toString(), "-t", "png")
            execute(command, remainingBudget(deadline, timeout))
            hdc.receiveFile(deviceId, remotePath, localPath, remainingBudget(deadline, timeout))
            checkInterrupted()
            if (!Files.isRegularFile(localPath, LinkOption.NOFOLLOW_LINKS)) {
                throw HdcException("HDC did not create the requested screenshot file")
            }
            val size = Files.size(localPath)
            if (size !in 1..MAX_SCREENSHOT_BYTES.toLong()) throw HdcException("Screenshot file exceeds its size limit or is empty")
            val bytes = Files.newInputStream(localPath).use { it.readNBytes(MAX_SCREENSHOT_BYTES + 1) }
            if (bytes.size.toLong() != size || Files.size(localPath) != size) {
                throw HdcException("Screenshot file changed while it was being read")
            }
            remainingBudget(deadline, timeout)
            return bytes
        } catch (error: Throwable) {
            failure = error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        } finally {
            val errors = mutableListOf<Throwable>()
            if (captureAttempted) {
                if (Thread.currentThread().isInterrupted) {
                    errors += HdcException("Device cleanup skipped after interruption; temporary screenshot may remain at $remotePath")
                } else {
                    try {
                        // Cleanup has its own small allowance even when the capture budget expired.
                        execute(listOf("rm", "-f", remotePath), Duration.ofSeconds(2))
                        hdc.forgetTemporaryFile(remotePath)
                    } catch (error: Throwable) {
                        if (error is InterruptedException) {
                            Thread.currentThread().interrupt()
                            error.addSuppressed(HdcException("Temporary screenshot may remain at $remotePath"))
                            errors += error
                        } else {
                            errors += HdcException("Could not remove temporary screenshot $remotePath: ${error.message}", error)
                        }
                    }
                }
            }
            for (path in listOfNotNull(localPath, localDirectory)) {
                try {
                    Files.deleteIfExists(path)
                } catch (error: Exception) {
                    errors += error
                }
            }
            files.close()
            if (errors.isNotEmpty()) {
                val original = failure
                if (original != null) {
                    errors.forEach(original::addSuppressed)
                } else {
                    val cleanupFailure = errors.firstOrNull { it is InterruptedException }
                        ?: HdcException("Could not clean up temporary screenshot $remotePath")
                    errors.filter { it !== cleanupFailure }.forEach(cleanupFailure::addSuppressed)
                    throw cleanupFailure
                }
            }
        }
    }

    private fun remainingBudget(deadline: Long, timeout: Duration): Duration {
        checkInterrupted()
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw HdcTimeoutException(timeout)
        return Duration.ofNanos(remaining)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("HarmonyOS operation interrupted")
    }

    private companion object {
        val logger = LoggerFactory.getLogger(HdcHarmonyOSDevice::class.java)
        const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
        val DEVICE_FAILURE = Regex("(?im)^\\s*(?:ScreenCap failed:|DumpLayout failed:|\\[\\s*(?:fail|error)\\s*]|error:|failed:)")
    }
}
