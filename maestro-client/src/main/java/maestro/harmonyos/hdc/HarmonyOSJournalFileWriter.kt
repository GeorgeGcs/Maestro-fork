package maestro.harmonyos.hdc

import java.io.FileOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Retries only replacement of the same already-flushed bytes, never device work. */
internal class HarmonyOSJournalFileWriter(
    private val move: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val delete: (Path) -> Unit = { Files.deleteIfExists(it); Unit },
) {
    fun write(path: Path, bytes: ByteArray) {
        interrupted()
        val staging = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.pending")
        var primary: Throwable? = null
        try {
            FileOutputStream(staging.toFile()).use { stream -> stream.write(bytes); stream.fd.sync() }
            val started = nanoTime()
            var attempts = 0
            while (true) {
                interrupted()
                try {
                    attempts++
                    move(staging, path)
                    return
                } catch (error: AccessDeniedException) {
                    val remaining = RETRY_BUDGET_NANOS - (nanoTime() - started)
                    if (attempts >= MAX_ATTEMPTS || remaining <= 0) throw error
                    val waitMillis = minOf(10L shl (attempts - 1), TimeUnit.NANOSECONDS.toMillis(remaining))
                    if (waitMillis <= 0) throw error
                    interrupted()
                    try { pause(waitMillis) } catch (interruption: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw interruption
                    }
                    interrupted()
                    if (nanoTime() - started >= RETRY_BUDGET_NANOS) throw error
                }
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try { delete(staging) } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup
                if (cleanup !== original) original.addSuppressed(cleanup)
            }
        }
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Interrupted while persisting the recovery journal")
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        val RETRY_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(200)
    }
}
