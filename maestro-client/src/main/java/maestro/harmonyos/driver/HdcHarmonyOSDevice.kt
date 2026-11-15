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
) : HarmonyOSDevice {
    private var layoutChannel: LoopbackLayoutChannel? = null

    override fun disconnect() {
        layoutChannel?.close()
        layoutChannel = null
    }
    override fun connect() {
        hdc.selectDevice(deviceId)
        val port = layoutPort ?: return
        if (hasLayoutForward(port, Duration.ofSeconds(2))) {
            layoutChannel = LoopbackLayoutChannel(port)
            logger.info("UITest RPC layout enabled for this session")
        } else {
            logger.warn("UITest RPC layout forward unavailable; using HDC layout for this session")
        }
    }

    override fun isAvailable(): Boolean = deviceId in hdc.listDevices()

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
                    return channel.snapshot(rpcBudget)
                } catch (error: HdcException) {
                    disableLayoutRpc(channel, error)
                } catch (error: UitestLayoutException) {
                    disableLayoutRpc(channel, error)
                }
            }
        }
        val budget = remainingBudget(deadline, timeout)
        return HierarchyReader(
            hdc, deviceId,
            commandTimeout = budget,
            cleanupTimeout = Duration.ofSeconds(2),
            totalTimeout = budget,
        ).read()
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

    override fun execute(arguments: List<String>, timeout: Duration): String {
        checkInterrupted()
        require(arguments.isNotEmpty()) { "A device command is required" }
        val marker = "MAESTRO_EXIT_${UUID.randomUUID().toString().replace("-", "")}="
        val script = arguments.joinToString(" ") { HdcClient.quoteShellArgument(it) } +
            "; __maestro_status=\$?; printf '\\n${marker}%s\\n' \"\$__maestro_status\"; exit \"\$__maestro_status\""
        var transportFailure: HdcCommandException? = null
        val result = try {
            hdc.shellFinite(deviceId, listOf("sh", "-c", script), timeout, HdcClient.finiteCommand(arguments), HdcCommandCategory.shell(arguments))
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

    override fun screenshot(timeout: Duration): ByteArray {
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
            execute(listOf("uitest", "screenCap", "-p", remotePath), remainingBudget(deadline, timeout))
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
