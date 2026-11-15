package maestro.harmonyos.layout

import maestro.TreeNode
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.utils.TempFileHandler
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration

/**
 * Reads a fresh dump using the OpenHarmony UITest `dumpLayout -p` interface.
 * Dump and transfer can share a deadline; cleanup has its own bounded allowance.
 */
class HierarchyReader(
    private val hdc: HdcClient,
    private val deviceId: String,
    private val parser: UitestLayoutParser = UitestLayoutParser(),
    private val commandTimeout: Duration = Duration.ofSeconds(15),
    private val cleanupTimeout: Duration = Duration.ofSeconds(2),
    private val maxLayoutBytes: Int = 8 * 1024 * 1024,
    private val totalTimeout: Duration? = null,
) {
    init {
        require(deviceId.isNotBlank()) { "A target device is required" }
        require(!commandTimeout.isNegative && !commandTimeout.isZero) { "Command timeout must be positive" }
        require(!cleanupTimeout.isNegative && !cleanupTimeout.isZero) { "Cleanup timeout must be positive" }
        require(maxLayoutBytes in 1..8 * 1024 * 1024) { "Layout limit must be between 1 byte and 8 MiB" }
        totalTimeout?.let { require(it > Duration.ZERO && it <= Duration.ofMinutes(5)) { "Total timeout must be positive and at most 5 minutes" } }
    }

    @Synchronized
    fun read(): TreeNode {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Hierarchy read interrupted before creating temporary files")
        }
        val deadline = totalTimeout?.let { System.nanoTime() + it.toNanos() }
        fun remainingBudget(): Duration {
            val remaining = deadline?.let { Duration.ofNanos(it - System.nanoTime()) } ?: return commandTimeout
            if (remaining <= Duration.ZERO) throw HdcTimeoutException(checkNotNull(totalTimeout))
            return minOf(commandTimeout, remaining)
        }
        val files = TempFileHandler()
        var localDirectory: Path? = null
        var localFile: Path? = null
        val remotePath = hdc.allocateTemporaryFile("layout", "json")
        var dumpAttempted = false
        var failure: Throwable? = null
        try {
            localDirectory = files.createTempDirectory("maestro-harmonyos-layout-").toPath()
            // No pre-existing local file can satisfy a failed or incomplete transfer.
            localFile = localDirectory.resolve("layout.json")
            checkInterrupted()
            dumpAttempted = true
            val result = hdc.shell(deviceId, listOf("uitest", "dumpLayout", "-p", remotePath), remainingBudget())
            if (DUMP_FAILURE.containsMatchIn(result.stdout) || DUMP_FAILURE.containsMatchIn(result.stderr)) {
                throw HierarchyReadException("UITest failed to create the requested layout")
            }
            checkInterrupted()
            hdc.receiveFile(deviceId, remotePath, localFile, remainingBudget())
            checkInterrupted()
            val root = parser.parse(readLayout(localFile))
            checkInterrupted()
            remainingBudget()
            return root
        } catch (error: Throwable) {
            failure = error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            var cleanupInterruption: InterruptedException? = null
            if (dumpAttempted) {
                if (Thread.currentThread().isInterrupted) {
                    cleanupErrors += HierarchyReadException(
                        "Device cleanup skipped after interruption; temporary layout may remain at $remotePath",
                    )
                } else {
                    try {
                        val result = hdc.shell(deviceId, listOf("rm", "-f", remotePath), cleanupTimeout)
                        if (result.stdout.isNotBlank() || result.stderr.isNotBlank()) {
                            throw HierarchyReadException("Device cleanup returned diagnostics for $remotePath")
                        }
                        hdc.forgetTemporaryFile(remotePath)
                    } catch (error: Throwable) {
                        if (error is InterruptedException) {
                            Thread.currentThread().interrupt()
                            cleanupInterruption = error
                            cleanupErrors += HierarchyReadException(
                                "Device cleanup interrupted; temporary layout may remain at $remotePath",
                            )
                        } else {
                            cleanupErrors += HierarchyReadException("Could not remove temporary layout $remotePath", error)
                        }
                    }
                }
            }
            for (path in listOfNotNull(localFile, localDirectory)) {
                try {
                    Files.deleteIfExists(path)
                } catch (error: Exception) {
                    cleanupErrors += HierarchyReadException("Could not remove a local layout temporary file", error)
                }
            }
            // Keep upstream's lifecycle cleanup as a fallback if explicit deletion failed.
            files.close()
            if (cleanupErrors.isNotEmpty()) {
                val originalFailure = failure
                val interruption = cleanupInterruption
                if (originalFailure != null) {
                    interruption?.let(originalFailure::addSuppressed)
                    cleanupErrors.forEach(originalFailure::addSuppressed)
                } else if (interruption != null) {
                    cleanupErrors.forEach(interruption::addSuppressed)
                    throw interruption
                } else {
                    val error = HierarchyReadException("Layout was read but temporary file cleanup failed")
                    cleanupErrors.forEach(error::addSuppressed)
                    throw error
                }
            }
        }
    }

    private fun readLayout(path: Path): String {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw HierarchyReadException("HDC did not create the requested local layout file")
        }
        val size = Files.size(path)
        if (size <= 0 || size > maxLayoutBytes) {
            throw HierarchyReadException("Layout file must contain between 1 and $maxLayoutBytes bytes")
        }
        val bytes = Files.newInputStream(path).use { it.readNBytes(maxLayoutBytes + 1) }
        if (bytes.size.toLong() != size || Files.size(path) != size) {
            throw HierarchyReadException("Layout file changed while it was being read")
        }
        if (bytes.size > maxLayoutBytes) throw HierarchyReadException("Layout file exceeds $maxLayoutBytes bytes")
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw HierarchyReadException("Layout file is not valid UTF-8", error)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Hierarchy read interrupted")
    }

    private companion object {
        val DUMP_FAILURE = Regex("(?im)^\\s*DumpLayout failed:")
    }
}

class HierarchyReadException(message: String, cause: Throwable? = null) : HdcException(message, cause)
