package maestro.cli.harmonyos

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Optional controls for the local process that owns this attempt. */
internal class HarmonyOsInteractiveRun(root: Path, attemptId: String) {
    private val release = root.resolve("$attemptId.released")
    private val stopFile = root.resolve("$attemptId.stop")
    private val previewFile = root.resolve("$attemptId.preview")
    private val frame = root.resolve("$attemptId.png")
    private var lastPreview = 0L
    var stopRequested: Boolean = false
        private set

    init {
        Files.createFile(root.resolve("$attemptId.interactive"))
    }

    fun markReleased() { try { Files.createFile(release) } catch (_: IOException) { /* Release is unconfirmed for the caller. */ } }

    fun shouldStop(): Boolean {
        if (Files.isRegularFile(stopFile, LinkOption.NOFOLLOW_LINKS)) stopRequested = true
        return stopRequested
    }

    // The driver has already acquired and validated these bytes. No extra device I/O.
    fun preview(bytes: ByteArray) {
        val now = System.nanoTime()
        if (lastPreview != 0L && now - lastPreview < TimeUnit.SECONDS.toNanos(2)) return
        lastPreview = now
        if (bytes.size > 4 * 1024 * 1024 ||
            !Files.isRegularFile(previewFile, LinkOption.NOFOLLOW_LINKS) || shouldStop()) return
        try {
            val staging = Files.createTempFile(frame.parent, ".preview-", ".tmp")
            try {
                Files.write(staging, bytes)
                Files.move(staging, frame, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staging)
            }
        } catch (_: IOException) {
            // A dropped preview frame must not change a Flow's outcome.
        }
    }
}

