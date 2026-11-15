package maestro.harmonyos.driver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.driver.HarmonyOSRecordingDiagnostics.Fields
import maestro.harmonyos.driver.HarmonyOSRecordingDiagnostics.Result
import maestro.harmonyos.driver.HarmonyOSRecordingDiagnostics.Stage
import maestro.harmonyos.hdc.HarmonyOSRecordingResourceJournal
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class HarmonyOSRecordingDiagnosticsTest {
    @Test
    fun `PID observations use the same smallest bounded subset regardless of set iteration order`() {
        val lines = mutableListOf<String>()
        val diagnostics = HarmonyOSRecordingDiagnostics(FILE, null, emitLine = lines::add)
        diagnostics.state(Stage.CAPTURES, linkedSetOf(71, 51))
        diagnostics.state(Stage.CAPTURES, linkedSetOf(51, 71))
        diagnostics.state(Stage.PROCESSES, (100 downTo 1).toSet())
        diagnostics.state(Stage.PROCESSES, (1..100).toSet())
        assertThat(lines).hasSize(2)
        val events = lines.map { mapper.readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
        assertThat(events[0]["fields"]["pids"].map { it.asInt() }).containsExactly(51, 71).inOrder()
        assertThat(events[1]["fields"]["pids"].map { it.asInt() }).containsExactlyElementsIn(1..8).inOrder()
        assertThat(events[1]["fields"]["count"].asInt()).isEqualTo(100)
        assertThat(events[1]["fields"]["pidsTruncated"].asBoolean()).isTrue()
    }

    @Test
    fun `archive metadata failures and untrusted hashes are explicit without leaking their values`() {
        val lines = mutableListOf<String>()
        val diagnostics = HarmonyOSRecordingDiagnostics(FILE, null, emitLine = lines::add)
        diagnostics.archived(1) { HarmonyOSRecordingResourceJournal.Archive("not-logged", 24, "a".repeat(64)) }
        diagnostics.archived(2) { null }
        diagnostics.archived(3) { throw IOException("secret archive failure") }
        diagnostics.archived(4) { HarmonyOSRecordingResourceJournal.Archive("not-logged", 25, "secret".repeat(200)) }
        val events = lines.map { mapper.readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
        assertThat(events).hasSize(4)
        assertThat(events[0]["fields"]["archiveBytes"].asLong()).isEqualTo(24L)
        assertThat(events[0]["fields"]["archiveSha256"].asText()).isEqualTo("a".repeat(64))
        assertThat(events[0]["fields"]["archiveMetadataUnavailable"].asBoolean()).isFalse()
        assertThat(events.drop(1).all { it["fields"]["archiveMetadataUnavailable"].asBoolean() }).isTrue()
        assertThat(events.drop(1).all { it["fields"]["archiveSha256"] == null && it["fields"]["archiveBytes"] == null }).isTrue()
        assertThat(lines.joinToString()).doesNotContain("secret")
        assertThat(lines.joinToString()).doesNotContain("not-logged")
    }

    @Test
    fun `records host UTC and monotonic elapsed times despite wall clock moving backwards`() {
        val lines = mutableListOf<String>()
        var nanos = 9_000_000L
        var wall = Instant.parse("2026-01-02T03:04:05Z")
        val diagnostics = HarmonyOSRecordingDiagnostics(FILE, null, emitLine = lines::add, wallClock = { wall }, nanoClock = { nanos })
        diagnostics.event(Stage.CREATED, Result.OBSERVED)
        val began = diagnostics.mark()
        nanos += 25_000_000
        wall = wall.minusSeconds(30)
        diagnostics.event(Stage.STAT, Result.OBSERVED, Fields(expectedBytes = 123), since = began)
        diagnostics.finish(Stage.CLOSE, null)
        val events = lines.map { mapper.readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
        assertThat(events.map { it["elapsedMs"].asLong() }).containsExactly(0L, 25L, 25L).inOrder()
        assertThat(events[1]["observedAt"].asText()).isEqualTo("2026-01-02T03:03:35Z")
        assertThat(events[1]["fields"]["stageElapsedMs"].asLong()).isEqualTo(25L)
        assertThat(events.last()["fields"]["expectedEventCount"].asInt()).isEqualTo(3)
    }

    @Test
    fun `unchanged polling is deduplicated and changing polling leaves bounded terminal evidence`() {
        val lines = mutableListOf<String>()
        val diagnostics = HarmonyOSRecordingDiagnostics(FILE, null, emitLine = lines::add)
        repeat(5_000) { diagnostics.state(Stage.CAPTURES, setOf(51)) }
        repeat(5_000) { diagnostics.queryStarted(); diagnostics.query(null, null) }
        assertThat(lines).hasSize(3)
        repeat(5_000) { diagnostics.state(Stage.CAPTURES, setOf(51 + it % 2)) }
        repeat(50) { diagnostics.event(Stage.CLEANUP, Result.RETURNED) }
        diagnostics.finish(Stage.CLOSE, null)
        diagnostics.finish(Stage.ABORT, IOException())
        assertThat(lines).hasSize(HarmonyOSRecordingDiagnostics.DETAIL_EVENTS + HarmonyOSRecordingDiagnostics.FINAL_EVENTS + 2)
        val events = lines.map { mapper.readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
        assertThat(events.count { it["stage"].asText() == "truncated" }).isEqualTo(1)
        assertThat(events.last()["stage"].asText()).isEqualTo("close")
        assertThat(events.last()["fields"]["truncated"].asBoolean()).isTrue()
        assertThat(events.last()["fields"]["droppedEvents"].asInt()).isGreaterThan(4_000)
        assertThat(events.last()["fields"]["expectedEventCount"].asInt()).isEqualTo(lines.size)
        assertThat(events.map { it["seq"].asInt() }).containsExactlyElementsIn(1..lines.size).inOrder()
    }

    @Test
    fun `untrusted identities and exception text are excluded and PID fields stay bounded`() {
        val secret = "serial-secret proof-nonce\nstdout stderr " + "x".repeat(4_000)
        val lines = mutableListOf<String>()
        val diagnostics = HarmonyOSRecordingDiagnostics(secret, secret, secret, emitLine = lines::add)
        diagnostics.state(Stage.PROCESSES, (1..10_000).toSet())
        diagnostics.finish(Stage.ABORT, IOException(secret))
        assertThat(lines.joinToString()).doesNotContain("secret")
        assertThat(lines.joinToString()).doesNotContain("proof")
        assertThat(lines.all { it.length < 1_000 && '\n' !in it }).isTrue()
        val event = mapper.readTree(lines.first().removePrefix(HarmonyOSRecordingDiagnostics.PREFIX))
        assertThat(event["recordingId"].asText()).isEqualTo("unavailable")
        assertThat(event["resourceId"]).isNull()
        assertThat(event["contextId"].isNull).isTrue()
        assertThat(event["fields"]["pids"].size()).isEqualTo(8)
        assertThat(event["fields"]["count"].asInt()).isEqualTo(10_000)
        assertThat(event["fields"]["pidsTruncated"].asBoolean()).isTrue()
    }

    @Test
    fun `failed emission leaves a sequence gap and does not mask the operation exception`() {
        val lines = mutableListOf<String>()
        var calls = 0
        val diagnostics = HarmonyOSRecordingDiagnostics(FILE, null, emitLine = {
            if (++calls == 1) throw IOException("logger unavailable")
            lines += it
        })
        val original = IOException("operation failed")
        val error = assertThrows(IOException::class.java) { diagnostics.step(Stage.FLUSH) { throw original } }
        diagnostics.finish(Stage.CLOSE, error)
        assertThat(error).isSameInstanceAs(original)
        assertThat(error.suppressed).isEmpty()
        val events = lines.map { mapper.readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
        assertThat(events.map { it["seq"].asInt() }).containsExactly(2, 3).inOrder()
        assertThat(events.last()["fields"]["emissionFailures"].asInt()).isEqualTo(1)
        assertThat(events.last()["fields"]["expectedEventCount"].asInt()).isEqualTo(3)
    }

    @Test
    fun `broken diagnostic clocks and sinks do not change operation results or interrupts`() {
        val original = IOException("original")
        val diagnostics = HarmonyOSRecordingDiagnostics(FILE, null,
            emitLine = { throw IllegalStateException("log failed") },
            nanoClock = { throw IllegalStateException("clock failed") },
            wallClock = { throw IllegalStateException("clock failed") })
        Thread.currentThread().interrupt()
        try {
            assertThat(diagnostics.step(Stage.EXPORT) { 7 }).isEqualTo(7)
            assertThat(assertThrows(IOException::class.java) { diagnostics.step(Stage.RECEIVE) { throw original } }).isSameInstanceAs(original)
            diagnostics.finish(Stage.CLOSE, original)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `only bounded owned URIs are exposed and deletion refers to an earlier observation`() {
        val lines = mutableListOf<String>()
        val diagnostics = HarmonyOSRecordingDiagnostics(FILE, null, emitLine = lines::add)
        val first = "file://media/Photo/123/VID_123/$FILE"
        val changed = "file://media/Photo/124/VID_124/$FILE"
        diagnostics.query(first, null)
        diagnostics.query(first, null)
        diagnostics.query(changed, null)
        diagnostics.query(null, null)
        diagnostics.query("file://media/Photo/125/private-proof-token/another-recording.mp4", null)
        diagnostics.query(null, null)
        diagnostics.query("file://media/Photo/125/" + "secret".repeat(200) + "/$FILE", null)
        diagnostics.finish(Stage.CLOSE, null)
        val events = lines.map { mapper.readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
        assertThat(events.take(3).map { it["fields"]["mediaUri"].asText() }).containsExactly(first, changed, changed).inOrder()
        assertThat(events[2]["fields"]["mediaUriPreviouslyObserved"].asBoolean()).isTrue()
        assertThat(events[2]["fields"]["result"].asText()).isEqualTo("absent")
        assertThat(events[3]["fields"]["uriUnavailable"].asBoolean()).isTrue()
        assertThat(events[5]["fields"]["uriUnavailable"].asBoolean()).isTrue()
        assertThat(lines.joinToString()).doesNotContain("private-proof-token")
        assertThat(lines.joinToString()).doesNotContain("secret")
    }

    private companion object {
        const val FILE = "maestro-recording-0123456789abcdef0123456789abcdef.mp4"
        val mapper = jacksonObjectMapper()
    }
}
