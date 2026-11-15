package maestro.harmonyos.hdc

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class HarmonyOSJournalFileWriterTest {
    @TempDir lateinit var directory: Path
    private fun target() = directory.resolve("journal.json")
    private fun replace(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Test fun `transient access denial retries identical flushed staging bytes and commits once`() {
        Files.writeString(target(), "old")
        val sources = mutableListOf<Path>()
        var now = 0L
        val pauses = mutableListOf<Long>()
        HarmonyOSJournalFileWriter(move = { source, destination ->
            sources.add(source)
            assertThat(Files.readString(source)).isEqualTo("new")
            if (sources.size < 3) {
                assertThat(Files.readString(destination)).isEqualTo("old")
                throw AccessDeniedException(source.toString(), destination.toString(), null)
            }
            replace(source, destination)
        }, nanoTime = { now }, pause = { pauses += it; now += TimeUnit.MILLISECONDS.toNanos(it) }).write(target(), "new".toByteArray())
        assertThat(sources).hasSize(3)
        assertThat(sources.distinct()).hasSize(1)
        assertThat(pauses).containsExactly(10L, 20L).inOrder()
        assertThat(Files.readString(target())).isEqualTo("new")
        Files.list(directory).use { assertThat(it.count()).isEqualTo(1) }
    }

    @Test fun `persistent access denial is bounded and preserves committed bytes`() {
        Files.writeString(target(), "old")
        var attempts = 0
        var now = 0L
        val writer = HarmonyOSJournalFileWriter(move = { source, destination ->
            attempts++
            throw AccessDeniedException(source.toString(), destination.toString(), null)
        }, nanoTime = { now }, pause = { now += TimeUnit.MILLISECONDS.toNanos(it) })
        assertThrows(AccessDeniedException::class.java) { writer.write(target(), "new".toByteArray()) }
        assertThat(attempts).isEqualTo(5)
        assertThat(now).isEqualTo(TimeUnit.MILLISECONDS.toNanos(150))
        assertThat(Files.readString(target())).isEqualTo("old")
        Files.list(directory).use { assertThat(it.count()).isEqualTo(1) }
    }

    @Test fun `elapsed move time consumes the same monotonic retry budget`() {
        Files.writeString(target(), "old")
        var attempts = 0
        var now = 0L
        val writer = HarmonyOSJournalFileWriter(move = { source, destination ->
            attempts++; now += TimeUnit.MILLISECONDS.toNanos(195)
            throw AccessDeniedException(source.toString(), destination.toString(), null)
        }, nanoTime = { now }, pause = { now += TimeUnit.MILLISECONDS.toNanos(it) })
        assertThrows(AccessDeniedException::class.java) { writer.write(target(), "new".toByteArray()) }
        assertThat(attempts).isEqualTo(1)
        assertThat(now).isEqualTo(TimeUnit.MILLISECONDS.toNanos(200))
        assertThat(Files.readString(target())).isEqualTo("old")
    }

    @Test fun `other IO failures never retry and cleanup cannot hide the original`() {
        Files.writeString(target(), "old")
        val primary = IOException("replacement failed")
        val cleanup = IOException("staging cleanup failed")
        var attempts = 0
        val writer = HarmonyOSJournalFileWriter(move = { _, _ -> attempts++; throw primary },
            pause = { error("Must not wait") }, delete = { throw cleanup })
        assertThat(assertThrows(IOException::class.java) { writer.write(target(), "new".toByteArray()) }).isSameInstanceAs(primary)
        assertThat(primary.suppressed.toList()).containsExactly(cleanup)
        assertThat(attempts).isEqualTo(1)
        assertThat(Files.readString(target())).isEqualTo("old")
    }

    @Test fun `preexisting interruption prevents staging or a move and preserves interrupt status`() {
        Files.writeString(target(), "old")
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                HarmonyOSJournalFileWriter(move = { _, _ -> error("Must not move") }).write(target(), "new".toByteArray())
            }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            Files.list(directory).use { assertThat(it.count()).isEqualTo(1) }
        } finally { Thread.interrupted() }
    }

    @Test fun `interruption during backoff stops before another attempt and restores status`() {
        Files.writeString(target(), "old")
        var attempts = 0
        val writer = HarmonyOSJournalFileWriter(move = { source, destination ->
            attempts++; throw AccessDeniedException(source.toString(), destination.toString(), null)
        }, pause = { throw InterruptedException("wait interrupted") })
        try {
            assertThrows(InterruptedException::class.java) { writer.write(target(), "new".toByteArray()) }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(attempts).isEqualTo(1)
        } finally { Thread.interrupted() }
        assertThat(Files.readString(target())).isEqualTo("old")
    }
}
