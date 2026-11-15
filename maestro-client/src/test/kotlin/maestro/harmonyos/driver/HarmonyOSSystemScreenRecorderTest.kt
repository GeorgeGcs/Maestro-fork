package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.debuglog.ScopedLogCapture
import maestro.debuglog.RecordingDiagnosticContext
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.harmonyos.hdc.HarmonyOSRecordingResourceJournal
import maestro.harmonyos.hdc.HarmonyOSRecordingResourceJournalTest.MediaRemote
import maestro.harmonyos.hdc.RecordingResourceFailure
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.utils.TempFileHandler
import okio.Buffer
import okio.Sink
import okio.Timeout
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HarmonyOSSystemScreenRecorderTest {
    @Test
    fun `stage diagnostics reach scoped logs in operation order without additional device calls`(): Unit = TempFileHandler().use { files ->
        val log = files.createTempDirectory("recording-diagnostics").resolve("maestro.log")
        val device = Device()
        ScopedLogCapture.start(log).use {
            recorder(device, config().copy(recordingStopTimeout = Duration.ofSeconds(3)), "sensitive-device-serial").start(Buffer()).close()
        }
        val lines = log.readLines().filter { HarmonyOSRecordingDiagnostics.PREFIX in it }
        val events = lines.map { jacksonObjectMapper().readTree(it.substringAfter(HarmonyOSRecordingDiagnostics.PREFIX)) }
        val stages = events.map { it["stage"].asText() to it["fields"]["result"].asText() }
        assertThat(stages).containsAtLeast(
            "start" to "begin", "start" to "acknowledged", "ready" to "observed",
            "stop" to "begin", "stop" to "acknowledged", "capture_ended" to "observed",
            "query" to "present", "export" to "begin", "stat" to "observed",
            "receive" to "observed", "stat_after_receive" to "observed", "validate" to "returned", "flush" to "returned",
            "delete" to "acknowledged", "media_removed" to "observed", "cleanup" to "returned", "close" to "returned",
        ).inOrder()
        val stat = events.single { it["stage"].asText() == "stat" && it["fields"]["result"].asText() == "observed" }
        val received = events.single { it["stage"].asText() == "receive" && it["fields"]["result"].asText() == "observed" }
        assertThat(stat["fields"]["expectedBytes"].asLong()).isEqualTo(device.video.size.toLong())
        assertThat(received["fields"]["receivedBytes"].asLong()).isEqualTo(device.video.size.toLong())
        val after = events.single { it["stage"].asText() == "stat_after_receive" && it["fields"]["result"].asText() == "observed" }
        assertThat(after["fields"]["remoteAfterBytes"].asLong()).isEqualTo(device.video.size.toLong())
        assertThat(after["fields"]["attempt"].asInt()).isEqualTo(received["fields"]["attempt"].asInt())
        assertThat(events.map { it["seq"].asInt() }).containsExactlyElementsIn(1..events.size).inOrder()
        assertThat(events.map { it["recordingId"].asText() }.toSet()).containsExactly(device.fileName!!.removePrefix("maestro-recording-").removeSuffix(".mp4"))
        assertThat(events.last()["fields"]["expectedEventCount"].asInt()).isEqualTo(events.size)
        assertThat(events.last()["fields"]["truncated"].asBoolean()).isFalse()
        assertThat(events.last()["fields"]["emissionFailures"].asInt()).isEqualTo(0)
        assertThat(events.map { it["elapsedMs"].asLong() }.zipWithNext().all { (a, b) -> a <= b }).isTrue()
        events.forEach { Instant.parse(it["observedAt"].asText()) }
        assertThat(lines.joinToString()).doesNotContain("sensitive-device-serial")
        assertThat(events.single { it["stage"].asText() == "query" && it["fields"]["result"].asText() == "present" }
            ["fields"]["mediaUri"].asText()).isEqualTo(device.uri)
        assertThat(device.commands.map { it.first() }).containsExactly(
            "bm", "ps", "hidumper", "mediatool", "aa", "ps", "hidumper",
            "ps", "hidumper", "aa", "hidumper", "mediatool", "rm", "mediatool", "stat", "stat", "mediatool", "mediatool", "rm",
        ).inOrder()
    }

    @Test
    fun `lost acknowledgements and throwing logger preserve the exact error and never repeat a toggle`() {
        for (loseStart in listOf(true, false)) {
            val original = IOException("serial-secret raw-command proof-token stdout stderr")
            val device = Device()
            var logCalls = 0
            val recorder = diagnosticRecorder(device) { ++logCalls; throw IOException("log failed") }
            if (loseStart) {
                device.response = { if (it.first() == "aa") throw original else null }
                assertThat(assertThrows(IOException::class.java) { recorder.start(Buffer()) }).isSameInstanceAs(original)
            } else {
                val recording = recorder.start(Buffer())
                device.response = { if (it.first() == "aa") { device.finishExternally(); throw original } else null }
                assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
                assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
            }
            assertThat(logCalls).isGreaterThan(0)
            assertThat(device.commands.count { it.first() == "aa" }).isEqualTo(if (loseStart) 1 else 2)
            assertThat(device.deletes).isEmpty()
            assertThat(original.suppressed.none { it.message == "log failed" }).isTrue()
        }
    }

    @Test
    fun `diagnostics distinguish a short transfer from a sink flush failure without leaking errors`() {
        for (short in listOf(true, false)) {
            val device = Device().apply { if (short) receivedVideo = video.copyOf(video.size / 2) }
            val sink = RecordingSink().apply { if (!short) flushFailure = IOException("private-proof-token") }
            val lines = mutableListOf<String>()
            val recording = diagnosticRecorder(device, lines::add).start(sink)
            val failure = if (short) assertThrows(HdcException::class.java) { recording.close() }
                else assertThrows(IOException::class.java) { recording.close() }
            if (!short) assertThat(failure).isSameInstanceAs(sink.flushFailure)
            val events = lines.map { jacksonObjectMapper().readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
            val received = events.single { it["stage"].asText() == "receive" && it["fields"]["result"].asText() == "observed" }
            assertThat(received["fields"]["receivedBytes"].asInt()).isEqualTo(if (short) device.video.size / 2 else device.video.size)
            assertThat(events.any { it["stage"].asText() == "flush" && it["fields"]["result"].asText() == "failed" }).isEqualTo(!short)
            assertThat(events.last()["fields"]["result"].asText()).isEqualTo("failed")
            val cleanup = events.single { it["stage"].asText() == "cleanup" }
            assertThat(cleanup["fields"]["result"].asText()).isEqualTo("returned")
            assertThat(cleanup["fields"]["priorFailure"].asBoolean()).isTrue()
            assertThat(cleanup["fields"]["cleanupErrors"].asInt()).isEqualTo(0)
            assertThat(lines.joinToString()).doesNotContain("private-proof-token")
            assertThat(device.deletes).isEmpty()
            assertThat(device.stops).isEqualTo(1)
        }
    }

    @Test
    fun `diagnostic context is requested once and a broken context does not affect recording`() {
        for (broken in listOf(true, false)) {
            val id = UUID.randomUUID().toString()
            var calls = 0
            val sink = object : Sink by Buffer(), RecordingDiagnosticContext {
                override fun recordingDiagnosticContext(): String? {
                    calls++
                    if (broken) throw IOException("private context failure")
                    return id
                }
            }
            val lines = mutableListOf<String>()
            val device = Device()
            diagnosticRecorder(device, lines::add).start(sink).close()
            assertThat(calls).isEqualTo(1)
            val events = lines.map { jacksonObjectMapper().readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
            assertThat(events.all { if (broken) it["contextId"].isNull else it["contextId"].asText() == id }).isTrue()
            assertThat(device.deletes).containsExactly(device.uri)
        }
    }

    private fun diagnosticRecorder(device: Device, emit: (String) -> Unit) = HarmonyOSSystemScreenRecorder(
        UUID.randomUUID().toString(), device, device::receive, config().copy(recordingStopTimeout = Duration.ofSeconds(3)), null,
        { fileName, resourceId, contextId -> HarmonyOSRecordingDiagnostics(fileName, resourceId, contextId, emitLine = emit) },
    )

    @Test
    fun `malformed query responses never enter diagnostic fields`() {
        val device = Device()
        val lines = mutableListOf<String>()
        val recording = diagnosticRecorder(device, lines::add).start(Buffer())
        device.response = { args ->
            if (args.take(2) == listOf("mediatool", "query")) "private-serial proof-nonce\nstdout stderr" else null
        }
        val failure = assertThrows(HdcException::class.java) { recording.close() }
        assertThat(failure.message).contains("proof-nonce")
        assertThat(lines.joinToString()).doesNotContain("proof-nonce")
        assertThat(lines.joinToString()).doesNotContain("private-serial")
        assertThat(lines.joinToString()).doesNotContain("stdout")
        assertThat(lines.joinToString()).doesNotContain("stderr")
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `recording starts only after readiness and exports exactly its own file`() {
        val device = Device()
        val sink = RecordingSink()
        val before = Instant.now()
        val recording = recorder(device).start(sink)
        assertThat(recording.startedAt).isAtLeast(before)
        assertThat(device.starts).isEqualTo(1)
        assertThat(sink.bytes.size).isEqualTo(0)
        assertThat(device.fileName).matches("maestro-recording-[0-9a-f]{32}\\.mp4")

        recording.close()
        recording.close()

        assertThat(device.stops).isEqualTo(1)
        assertThat(device.receives).isEqualTo(1)
        assertThat(device.deletes).containsExactly(device.uri)
        assertThat(sink.bytes.readByteArray()).isEqualTo(device.video)
        assertThat(sink.closed).isFalse()
        assertThat(sink.flushed).isTrue()
        assertThat(device.localPath?.let { Files.exists(it) }).isFalse()
        assertThat(device.commands.filter { it.first() == "rm" }.map { it.last() }.toSet())
            .containsExactly("/data/local/tmp/${device.fileName}")
        assertThat(device.commands.indexOfFirst { it.first() == "stat" })
            .isLessThan(device.commands.indexOfFirst { it.first() == "mediatool" && it[1] == "delete" })
    }

    @Test
    fun `existing recorder process or another capture prevents any start`() {
        for (existingCapture in listOf(false, true)) {
            val device = Device().apply {
                if (existingCapture) capturePids = setOf(91) else recorderPids = setOf(91)
            }
            val error = assertThrows(HdcException::class.java) { recorder(device).start(Buffer()) }
            assertThat(error.message).contains("already exists")
            assertThat(device.starts).isEqualTo(0)
            assertThat(device.stops).isEqualTo(0)
            assertThat(device.deletes).isEmpty()
        }
    }

    @Test
    fun `unknown process or capture formats prevent starting`() {
        for (prefix in listOf(listOf("ps"), listOf("hidumper"))) {
            val device = Device().apply { response = { args -> if (args.take(prefix.size) == prefix) "unknown status" else null } }
            assertThrows(HdcException::class.java) { recorder(device).start(Buffer()) }
            assertThat(device.starts).isEqualTo(0)
        }
    }

    @Test
    fun `missing Huawei service reports unsupported before side effects`() {
        val device = Device().apply { response = { args -> if (args.first() == "bm") "unavailable" else null } }
        assertThrows(UnsupportedOperationException::class.java) { recorder(device).start(Buffer()) }
        assertThat(device.starts).isEqualTo(0)
    }

    @Test
    fun `a lost start acknowledgement is preserved and never blindly toggled`() {
        val original = HdcException("start reply lost")
        val device = Device().apply { response = { args -> if (args.first() == "aa") throw original else null } }
        val error = assertThrows(HdcException::class.java) { recorder(device).start(Buffer()) }
        assertThat(error).isSameInstanceAs(original)
        assertThat(error.suppressed.map { it.message }.joinToString()).contains("no stop toggle")
        assertThat(device.commands.count { it.first() == "aa" }).isEqualTo(1)
    }

    @Test
    fun `only the exact empty query result permits exit 255`() {
        val failures = listOf(
            HarmonyOSCommandException("query failed", "permission denied", "", 255),
            HarmonyOSCommandException("query failed", "find 0 result\nerror: denied", "", 255),
            HarmonyOSCommandException("query failed", "find 0 result", "permission denied", 255),
            HarmonyOSCommandException("query failed", "find 0 result", "\n", 255),
            HarmonyOSCommandException("query failed", "find 0 result", "", 1),
        )
        for (original in failures) {
            val device = Device().apply {
                response = { args -> if (args.take(2) == listOf("mediatool", "query")) throw original else null }
            }
            val error = assertThrows(HarmonyOSCommandException::class.java) { recorder(device).start(Buffer()) }
            assertThat(error).isSameInstanceAs(original)
            assertThat(device.starts).isEqualTo(0)
            assertThat(device.deletes).isEmpty()
        }
    }

    @Test
    fun `readiness failure followed by export failure still stops the confirmed recording`() {
        val readiness = HdcException("readiness status unavailable")
        val query = HdcException("query temporarily unavailable")
        val device = Device()
        var readinessFailed = false
        var queryFailed = false
        device.response = { args ->
            if (device.starts > 0 && args.first() == "ps" && !readinessFailed) {
                readinessFailed = true
                throw readiness
            }
            if (device.starts > 0 && args.take(2) == listOf("mediatool", "query") && !queryFailed) {
                queryFailed = true
                throw query
            }
            null
        }
        val error = assertThrows(HdcException::class.java) { recorder(device).start(Buffer()) }
        assertThat(error).isSameInstanceAs(readiness)
        assertThat(error.suppressed).asList().contains(query)
        assertThat(error.suppressed.any { it.message.orEmpty().contains(checkNotNull(device.fileName)) }).isTrue()
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `a readable file does not permit export when a replacement recorder owns capture`() {
        val device = Device()
        val recording = recorder(device).start(Buffer())
        device.finishExternally()
        device.recorderPids = setOf(900)
        device.capturePids = setOf(900)
        assertThat(assertThrows(HdcException::class.java) { recording.close() }.message).contains("refusing to toggle")
        assertThat(device.stops).isEqualTo(0)
        assertThat(device.receives).isEqualTo(0)
        assertThat(device.deletes).isEmpty()
        assertThat(device.recorderPids).containsExactly(900)
        assertThat(device.capturePids).containsExactly(900)
    }

    @Test
    fun `changed process or capture ownership refuses a stop toggle`() {
        for (changedCapture in listOf(false, true)) {
            val device = Device()
            val recording = recorder(device).start(Buffer())
            if (changedCapture) device.capturePids = setOf(900) else device.recorderPids = setOf(900)
            val error = assertThrows(HdcException::class.java) { recording.close() }
            assertThat(error.message).contains("refusing to toggle")
            assertThat(device.stops).isEqualTo(0)
            assertThat(device.deletes).isEmpty()
        }
    }

    @Test
    fun `an active recording may publish its URI before the MP4 is finalized`() {
        val device = Device().apply { video = "unfinished movie".toByteArray() }
        val recording = recorder(device).start(Buffer())
        device.finished = true // query already sees the active recording's unique URI.
        device.response = { args ->
            if (args.first() == "aa" && !args.contains("--ps")) {
                device.video = Mp4RecordingValidatorTest.minimalVideoRecording()
            }
            null
        }
        recording.close()
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.receives).isEqualTo(1)
        assertThat(device.deletes).containsExactly(device.uri)
    }

    @Test
    fun `an ended capture can still need another export before its media becomes readable`() {
        val device = Device()
        val sink = RecordingSink()
        val recording = recorder(device).start(sink)
        device.finished = true
        val unreadable = HarmonyOSCommandException(
            "media file is not ready", "Table Name: Photos\n[FAIL] open source media file failed.\n", "", 0,
        )
        var exportAttempts = 0
        device.response = { args ->
            if (args.take(2) == listOf("mediatool", "recv")) {
                assertThat(device.stops).isEqualTo(1)
                assertThat(device.capturePids).isEmpty()
                if (++exportAttempts == 1) throw unreadable
            }
            null
        }

        recording.close()

        assertThat(device.stops).isEqualTo(1)
        assertThat(device.commands.count { it.take(2) == listOf("mediatool", "recv") }).isEqualTo(2)
        assertThat(device.receives).isEqualTo(1)
        assertThat(device.deletes).containsExactly(device.uri)
        assertThat(sink.bytes.readByteArray()).isEqualTo(device.video)
    }

    @Test
    fun `other media export errors preserve the original exception`() {
        val unreadable = "Table Name: Photos\n[FAIL] open source media file failed."
        val failures = listOf(
            HarmonyOSCommandException("recv failed", "permission denied", "", 0),
            HarmonyOSCommandException("recv failed", "$unreadable\npermission denied", "", 0),
            HarmonyOSCommandException("recv failed", "Table Name: Photos\n\n[FAIL] open source media file failed.", "", 0),
            HarmonyOSCommandException("recv failed", unreadable, "", 1),
            HarmonyOSCommandException("recv failed", unreadable, "", 255),
            HarmonyOSCommandException("recv failed", unreadable, "", null),
            HarmonyOSCommandException("recv failed", unreadable, "permission denied", 0),
            HarmonyOSCommandException("recv failed", unreadable, "\n", 0),
        )
        for (original in failures) {
            val device = Device()
            val recording = recorder(device).start(Buffer())
            device.finished = true
            device.response = { args ->
                if (args.take(2) == listOf("mediatool", "recv")) throw original
                null
            }

            val error = assertThrows(HarmonyOSCommandException::class.java) { recording.close() }

            assertThat(error).isSameInstanceAs(original)
            assertThat(device.receives).isEqualTo(0)
            assertThat(device.deletes).isEmpty()
        }
    }

    @Test
    fun `a file that remains unreadable times out with the original device diagnostic`() {
        val device = Device()
        val recording = recorder(device).start(Buffer())
        device.finished = true
        val original = HarmonyOSCommandException(
            "media file is not ready", "Table Name: Photos\n[FAIL] open source media file failed.", "", 0,
        )
        device.response = { args ->
            if (args.take(2) == listOf("mediatool", "recv")) throw original
            null
        }

        val error = assertThrows(HdcTimeoutException::class.java) { recording.close() }

        assertThat(error.suppressed).asList().contains(original)
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.receives).isEqualTo(0)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `a lingering process without a capture is not toggled`() {
        val device = Device()
        val recording = recorder(device).start(Buffer())
        device.capturePids = emptySet()
        assertThrows(HdcTimeoutException::class.java) { recording.close() }
        assertThat(device.stops).isEqualTo(0)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `lost stop reply is never retried and leaves the exact media asset recoverable`() {
        val original = HdcException("stop reply lost")
        val device = Device()
        val recording = recorder(device).start(Buffer())
        device.response = { args -> if (args.first() == "aa") throw original else null }
        val error = assertThrows(HdcException::class.java) { recording.close() }
        assertThat(error).isSameInstanceAs(original)
        assertThat(error.suppressed.any { it.message.orEmpty().contains(checkNotNull(device.fileName)) }).isTrue()
        assertThat(assertThrows(HdcException::class.java) { recording.close() }).isSameInstanceAs(original)
        assertThat(device.commands.count { it.first() == "aa" }).isEqualTo(2)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `transient status failure still stops a confirmed owned capture once`() {
        val original = HdcException("query temporarily unavailable")
        val device = Device()
        val recording = recorder(device).start(Buffer())
        var failed = false
        device.response = { args ->
            if (args.take(2) == listOf("mediatool", "query") && !failed) {
                failed = true
                throw original
            }
            null
        }
        assertThat(assertThrows(HdcException::class.java) { recording.close() }).isSameInstanceAs(original)
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `multiple or unrelated media matches are rejected without exporting or deleting`() {
        for (reply in listOf("find 2 result\n", "find 1 result\nuri\n\"file://media/Photo/1/VID_1/another.mp4\"\n")) {
            val device = Device()
            val recording = recorder(device).start(Buffer())
            device.response = { args -> if (args.take(2) == listOf("mediatool", "query")) reply else null }
            assertThrows(HdcException::class.java) { recording.close() }
            assertThat(device.stops).isEqualTo(1)
            assertThat(device.receives).isEqualTo(0)
            assertThat(device.deletes).isEmpty()
        }
    }

    @Test
    fun `size is checked before receiving and failed export retains its media asset`() {
        val device = Device()
        val recording = recorder(device, config().copy(recordingMaxBytes = 1024)).start(Buffer())
        device.finished = true
        device.response = { args -> if (args.first() == "stat") "1025\n" else null }
        val error = assertThrows(HdcException::class.java) { recording.close() }
        assertThat(error.message).contains("byte limit")
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.receives).isEqualTo(0)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `malformed MP4 never reaches the sink and timeout retains validation evidence`() {
        val device = Device().apply { video = "not a movie".toByteArray() }
        val sink = RecordingSink()
        val recording = recorder(device).start(sink)
        val error = assertThrows(HdcTimeoutException::class.java) { recording.close() }
        assertThat(error.suppressed.any { it is InvalidMp4RecordingException }).isTrue()
        assertThat(sink.bytes.size).isEqualTo(0)
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.deletes).isEmpty()
    }

    @Test
    fun `transfer and cleanup failures keep their identities and never delete media`() {
        val original = IOException("file transfer failed")
        val cleanup = HdcException("scratch removal failed")
        val device = Device().apply { receiveFailure = original }
        val recording = recorder(device).start(Buffer())
        device.response = { args -> if (args.first() == "rm" && device.receives > 0) throw cleanup else null }
        val error = assertThrows(IOException::class.java) { recording.close() }
        assertThat(error).isSameInstanceAs(original)
        assertThat(error.suppressed).asList().contains(cleanup)
        assertThat(device.deletes).isEmpty()
        assertThat(device.localPath?.let { Files.exists(it) }).isFalse()
    }

    @Test
    fun `sink failure keeps media and does not close caller sink`() {
        val original = IOException("artifact disk full")
        val device = Device()
        val sink = RecordingSink().apply { writeFailure = original }
        val recording = recorder(device).start(sink)
        assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
        assertThat(device.deletes).isEmpty()
        assertThat(sink.closed).isFalse()
    }

    @Test fun `partial sink write failure preserves the complete original through recovery`(): Unit = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("recording-partial-write").toPath()
        val journalPath = directory.resolve("recovery-journal.json")
        val id = UUID.randomUUID().toString()
        val device = Device().apply { sessionScratch = true }
        val original = IOException("output write failed after partial data")
        val sink = RecordingSink()
        val prefixSize = device.video.size / 2
        var bytesArchivedBeforeWrite: ByteArray? = null
        var writes = 0
        lateinit var marker: RecordingResourceFailure
        lateinit var archivePath: Path
        lateinit var archiveHash: String
        val recording = HarmonyOSRecoveryJournal.create(journalPath, id, UUID.randomUUID().toString()).use { journal ->
            val resources = journal.enableRecordingResources()
            val partialSink = object : Sink by sink {
                override fun write(source: Buffer, byteCount: Long) {
                    writes++
                    val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
                    archivePath = directory.resolve("recording-originals").resolve(entry["archiveName"].asText())
                    archiveHash = entry["archiveSha256"].asText()
                    bytesArchivedBeforeWrite = Files.readAllBytes(archivePath)
                    sink.write(source, minOf(byteCount, prefixSize.toLong()))
                    throw original
                }
            }
            val recording = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                config().copy(recordingStopTimeout = Duration.ofSeconds(3)), journal).start(partialSink)

            assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
            assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
            assertThat(writes).isEqualTo(1)
            assertThat(bytesArchivedBeforeWrite).isEqualTo(device.video)
            assertThat(sink.bytes.snapshot().toByteArray()).isEqualTo(device.video.copyOf(prefixSize))
            assertThat(sink.bytes.size).isGreaterThan(0L)
            assertThat(sink.bytes.size).isLessThan(device.video.size.toLong())
            assertThat(sink.flushCalls).isEqualTo(0)
            assertThat(sink.closed).isFalse()
            assertThat(device.stops).isEqualTo(1)
            assertThat(device.capturePids).isEmpty()
            assertThat(device.receives).isEqualTo(1)
            assertThat(device.deletes).isEmpty()
            assertThat(device.wasDeleted).isFalse()
            assertThat(Files.exists(checkNotNull(device.localPath))).isFalse()
            val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
            assertThat(entry["captureEnded"].booleanValue()).isTrue()
            assertThat(entry["uri"].asText()).isEqualTo(device.uri)
            assertThat(entry["mediaRemoved"].booleanValue()).isFalse()
            assertThat(resources.isComplete()).isFalse()
            assertThat(resources.verifyArchives()).isTrue()
            marker = original.suppressed.filterIsInstance<RecordingResourceFailure>().single()
            recording
        }
        val suppressedBeforeRecovery = original.suppressed.toList()
        val remote = MediaRemote(device.uri).apply { video = device.video }
        HarmonyOSRecoveryJournal.open(journalPath).use { journal ->
            val resources = checkNotNull(journal.recordings)
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(3))).isEmpty()
            assertThat(resources.isComplete()).isTrue()
            assertThat(resources.verifyArchives()).isTrue()
            assertThat(resources.resolvesFailure(marker.recordingId, marker.fingerprint)).isTrue()
            val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
            assertThat(entry["archiveSha256"].asText()).isEqualTo(archiveHash)
        }
        assertThat(remote.deleted).containsExactly(device.uri)
        assertThat(remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
        assertThat(Files.readAllBytes(archivePath)).isEqualTo(device.video)
        assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
        assertThat(original.suppressed.toList()).containsExactlyElementsIn(suppressedBeforeRecovery).inOrder()
        assertThat(sink.bytes.snapshot().toByteArray()).isEqualTo(device.video.copyOf(prefixSize))
        assertThat(sink.flushCalls).isEqualTo(0)
        assertThat(sink.closed).isFalse()
        assertThat(device.stops).isEqualTo(1)
    }

    @Test fun `interruption during receive preserves media and recovers without accepting the cancelled output`(): Unit = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("recording-receive-interrupted").toPath()
        val journalPath = directory.resolve("recovery-journal.json")
        val id = UUID.randomUUID().toString()
        val device = Device().apply { sessionScratch = true }
        val sink = RecordingSink()
        val enteredReceive = CountDownLatch(1)
        val releaseReceive = CountDownLatch(1)
        val receiveFailure = AtomicReference<InterruptedException>()
        val closeFailure = AtomicReference<Throwable>()
        val restoredInterrupt = AtomicBoolean()
        lateinit var marker: RecordingResourceFailure
        val recording: ScreenRecording = HarmonyOSRecoveryJournal.create(journalPath, id, UUID.randomUUID().toString()).use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = HarmonyOSSystemScreenRecorder(id, device, { remote, path, timeout ->
                device.receive(remote, path, timeout)
                enteredReceive.countDown()
                try {
                    check(releaseReceive.await(5, TimeUnit.SECONDS)) { "Receive fixture was not released" }
                } catch (error: InterruptedException) {
                    receiveFailure.set(error)
                    // Match ProcessHdcCommandRunner's restoration when its wait is interrupted.
                    Thread.currentThread().interrupt()
                    throw error
                }
            }, config().copy(recordingStopTimeout = Duration.ofSeconds(10)), journal).start(sink)
            val worker = Thread({
                try { recording.close() } catch (error: Throwable) { closeFailure.set(error) }
                finally {
                    restoredInterrupt.set(Thread.currentThread().isInterrupted)
                    Thread.interrupted()
                }
            }, "recording-close-interruption-test").apply { isDaemon = true; start() }
            try {
                assertThat(enteredReceive.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(worker.isAlive).isTrue()
                worker.interrupt()
                worker.join(5_000)
                assertThat(worker.isAlive).isFalse()
            } finally {
                releaseReceive.countDown()
                if (worker.isAlive) {
                    worker.interrupt()
                    worker.join(5_000)
                }
                check(!worker.isAlive) { "Recording close worker did not terminate" }
            }

            val original = checkNotNull(receiveFailure.get())
            assertThat(closeFailure.get()).isSameInstanceAs(original)
            assertThat(restoredInterrupt.get()).isTrue()
            assertThat(Thread.currentThread().isInterrupted).isFalse()
            assertThat(assertThrows(InterruptedException::class.java) { recording.close() }).isSameInstanceAs(original)
            assertThat(device.stops).isEqualTo(1)
            assertThat(device.capturePids).isEmpty()
            assertThat(device.receives).isEqualTo(1)
            assertThat(device.deletes).isEmpty()
            assertThat(device.wasDeleted).isFalse()
            // Only the pre-export removal ran; interrupted cleanup leaves the export for recovery.
            assertThat(device.commands.count { it.first() == "rm" }).isEqualTo(1)
            assertThat(sink.bytes.size).isEqualTo(0)
            assertThat(sink.flushCalls).isEqualTo(0)
            assertThat(sink.closed).isFalse()
            assertThat(Files.exists(checkNotNull(device.localPath))).isFalse()
            val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
            assertThat(entry["captureEnded"].booleanValue()).isTrue()
            assertThat(entry["archiveName"].isNull).isTrue()
            assertThat(entry["archives"].size()).isEqualTo(0)
            assertThat(entry["mediaRemoved"].booleanValue()).isFalse()
            assertThat(resources.isComplete()).isFalse()
            marker = original.suppressed.filterIsInstance<RecordingResourceFailure>().single()
            recording
        }
        val original = checkNotNull(receiveFailure.get())
        val suppressedBeforeRecovery = original.suppressed.toList()
        val remote = MediaRemote(device.uri).apply {
            video = device.video
            scratchPresent = true
            scratchBytes = device.video.copyOf()
        }
        val archive = HarmonyOSRecoveryJournal.open(journalPath).use { journal ->
            val resources = checkNotNull(journal.recordings)
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(3)).map { it.code })
                .containsExactly("RECORDING_ARCHIVED_RECHECK_REQUIRED")
            assertThat(resources.isComplete()).isFalse()
            assertThat(resources.verifyArchives()).isTrue()
            assertThat(resources.resolvesFailure(marker.recordingId, marker.fingerprint)).isFalse()
            assertThat(remote.exports).isEqualTo(0)
            assertThat(remote.deleted).isEmpty()
            assertThat(remote.scratchRemoved).isEmpty()
            val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
            entry["archiveName"].asText() to entry["archiveSha256"].asText()
        }
        val archivePath = directory.resolve("recording-originals").resolve(archive.first)
        assertThat(Files.readAllBytes(archivePath)).isEqualTo(device.video)
        HarmonyOSRecoveryJournal.open(journalPath).use { journal ->
            val resources = checkNotNull(journal.recordings)
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(3))).isEmpty()
            assertThat(resources.isComplete()).isTrue()
            assertThat(resources.verifyArchives()).isTrue()
            assertThat(resources.resolvesFailure(marker.recordingId, marker.fingerprint)).isTrue()
            val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
            assertThat(entry["archiveName"].asText()).isEqualTo(archive.first)
            assertThat(entry["archiveSha256"].asText()).isEqualTo(archive.second)
        }
        assertThat(remote.exports).isEqualTo(1)
        assertThat(remote.deleted).containsExactly(device.uri)
        assertThat(remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
        assertThat(Files.readAllBytes(archivePath)).isEqualTo(device.video)
        assertThat(assertThrows(InterruptedException::class.java) { recording.close() }).isSameInstanceAs(original)
        assertThat(original.suppressed.toList()).containsExactlyElementsIn(suppressedBeforeRecovery).inOrder()
        assertThat(sink.bytes.size).isEqualTo(0)
        assertThat(sink.flushCalls).isEqualTo(0)
        assertThat(sink.closed).isFalse()
        assertThat(device.stops).isEqualTo(1)
    }

    @Test fun `a short receive keeps device media and cannot authorize recovery without an archive`(): Unit = TempFileHandler().use { files ->
        for (persistent in listOf(false, true)) {
            val directory = files.createTempDirectory("recording-short-receive").toPath()
            val journalPath = directory.resolve("recovery-journal.json")
            val id = UUID.randomUUID().toString()
            val device = Device().apply {
                sessionScratch = persistent
                receivedVideo = video.copyOf(video.size / 2)
            }
            val sink = RecordingSink()
            var marker: RecordingResourceFailure? = null
            val journal = if (persistent) HarmonyOSRecoveryJournal.create(journalPath, id, UUID.randomUUID().toString()) else null
            journal.use {
                val resources = journal?.enableRecordingResources()
                val recording = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                    config().copy(recordingStopTimeout = Duration.ofSeconds(3)), journal).start(sink)

                val failure = assertThrows(HdcException::class.java) { recording.close() }

                assertThat(failure.message).contains("expected file size")
                assertThat(assertThrows(HdcException::class.java) { recording.close() }).isSameInstanceAs(failure)
                assertThat(device.receives).isEqualTo(1)
                assertThat(device.receivedVideo!!.size).isLessThan(device.video.size)
                assertThat(device.stops).isEqualTo(1)
                assertThat(device.capturePids).isEmpty()
                assertThat(device.deletes).isEmpty()
                assertThat(device.wasDeleted).isFalse()
                assertThat(sink.bytes.size).isEqualTo(0)
                assertThat(sink.flushCalls).isEqualTo(0)
                assertThat(sink.closed).isFalse()
                assertThat(Files.exists(checkNotNull(device.localPath))).isFalse()
                resources?.let {
                    val entry = jacksonObjectMapper().readTree(it.path.toFile())["resources"][0]
                    assertThat(entry["captureEnded"].booleanValue()).isTrue()
                    assertThat(entry["archiveName"].isNull).isTrue()
                    assertThat(it.isComplete()).isFalse()
                    marker = failure.suppressed.filterIsInstance<RecordingResourceFailure>().single()
                }
            }
            if (persistent) {
                val remote = MediaRemote(device.uri).apply { video = device.video }
                HarmonyOSRecoveryJournal.open(journalPath).use { reopened ->
                    val resources = checkNotNull(reopened.recordings)
                    val issues = resources.recover(HdcClient("fake", remote), reopened, Duration.ofSeconds(3))
                    assertThat(issues.map { it.code }).containsExactly("RECORDING_ARCHIVED_RECHECK_REQUIRED")
                    assertThat(resources.isComplete()).isFalse()
                    assertThat(resources.verifyArchives()).isTrue()
                    val preserved = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
                    val original = resources.path.parent.resolve("recording-originals").resolve(preserved["archiveName"].asText())
                    assertThat(Files.readAllBytes(original)).isEqualTo(device.video)
                    val failure = checkNotNull(marker)
                    assertThat(resources.resolvesFailure(failure.recordingId, failure.fingerprint)).isFalse()
                }
                assertThat(remote.deleted).isEmpty()
                assertThat(remote.exports).isEqualTo(1)
                assertThat(remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
            }
        }
    }

    @Test fun `flush failure retains media and a complete archive permits resource recovery`(): Unit = TempFileHandler().use { files ->
        for (persistent in listOf(false, true)) {
            val directory = files.createTempDirectory("recording-flush-failure").toPath()
            val journalPath = directory.resolve("recovery-journal.json")
            val id = UUID.randomUUID().toString()
            val device = Device().apply { sessionScratch = persistent }
            val original = IOException("output flush failed")
            val sink = RecordingSink().apply { flushFailure = original }
            var marker: RecordingResourceFailure? = null
            val journal = if (persistent) HarmonyOSRecoveryJournal.create(journalPath, id, UUID.randomUUID().toString()) else null
            journal.use {
                val resources = journal?.enableRecordingResources()
                val recording = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                    config().copy(recordingStopTimeout = Duration.ofSeconds(3)), journal).start(sink)

                assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
                assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
                assertThat(device.stops).isEqualTo(1)
                assertThat(device.capturePids).isEmpty()
                assertThat(device.receives).isEqualTo(1)
                assertThat(device.deletes).isEmpty()
                assertThat(device.wasDeleted).isFalse()
                assertThat(sink.bytes.snapshot().toByteArray()).isEqualTo(device.video)
                assertThat(sink.flushCalls).isEqualTo(1)
                assertThat(sink.flushed).isFalse()
                assertThat(sink.closed).isFalse()
                assertThat(Files.exists(checkNotNull(device.localPath))).isFalse()
                resources?.let {
                    val entry = jacksonObjectMapper().readTree(it.path.toFile())["resources"][0]
                    assertThat(entry["captureEnded"].booleanValue()).isTrue()
                    assertThat(entry["uri"].asText()).isEqualTo(device.uri)
                    assertThat(Files.readAllBytes(directory.resolve("recording-originals").resolve(entry["archiveName"].asText())))
                        .isEqualTo(device.video)
                    assertThat(it.verifyArchives()).isTrue()
                    assertThat(it.isComplete()).isFalse()
                    marker = original.suppressed.filterIsInstance<RecordingResourceFailure>().single()
                }
            }
            if (persistent) {
                val remote = MediaRemote(device.uri).apply { video = device.video }
                HarmonyOSRecoveryJournal.open(journalPath).use { reopened ->
                    val resources = checkNotNull(reopened.recordings)
                    assertThat(resources.recover(HdcClient("fake", remote), reopened, Duration.ofSeconds(3))).isEmpty()
                    assertThat(resources.isComplete()).isTrue()
                    assertThat(resources.verifyArchives()).isTrue()
                    val failure = checkNotNull(marker)
                    assertThat(resources.resolvesFailure(failure.recordingId, failure.fingerprint)).isTrue()
                }
                assertThat(remote.deleted).containsExactly(device.uri)
                assertThat(remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
                assertThat(sink.bytes.snapshot().toByteArray()).isEqualTo(device.video)
                assertThat(sink.flushed).isFalse()
            }
        }
    }

    @Test
    fun `delete must explicitly confirm success after a valid export`() {
        val device = Device()
        val recording = recorder(device).start(Buffer())
        device.response = { args -> if (args.take(2) == listOf("mediatool", "delete")) "[ERROR] failed" else null }
        assertThat(assertThrows(HdcException::class.java) { recording.close() }.message).contains("did not confirm deletion")
        assertThat(device.receives).isEqualTo(1)
        assertThat(device.stops).isEqualTo(1)
    }

    @Test
    fun `same device cannot be recorded by two Maestro sessions`() {
        val device = Device()
        val id = UUID.randomUUID().toString()
        val first = recorder(device, id = id).start(Buffer())
        try {
            assertThrows(HdcException::class.java) { recorder(device, id = id).start(Buffer()) }
            assertThat(device.starts).isEqualTo(1)
        } finally { first.close() }
    }

    @Test
    fun `closing an interrupted flow still stops its recording and restores interruption`() {
        val device = Device()
        val recording = recorder(device).start(Buffer())
        Thread.currentThread().interrupt()
        try {
            recording.close()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(device.stops).isEqualTo(1)
        } finally { Thread.interrupted() }
    }

    @Test
    fun `duration limit stops recording and remains a failure when the owner closes`() {
        val device = Device()
        val recording = recorder(device, config().copy(recordingMaxDuration = Duration.ofSeconds(1), recordingStopTimeout = Duration.ofSeconds(2))).start(Buffer())
        assertThat(device.deleted.await(4, TimeUnit.SECONDS)).isTrue()
        val error = assertThrows(HdcException::class.java) { recording.close() }
        assertThat(error.message).contains("1 second limit")
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.deletes).hasSize(1)
    }

    private fun recorder(device: Device, config: HarmonyOSDriverConfig = config(), id: String = UUID.randomUUID().toString()) =
        HarmonyOSSystemScreenRecorder(id, device, device::receive, config)

    @Test fun `readable active media waits for confirmed capture end with or without a journal`() = TempFileHandler().use { files ->
        for (persistent in listOf(false, true)) {
            val directory = files.createTempDirectory("recording-active-container").toPath()
            val id = UUID.randomUUID().toString()
            val journal = if (persistent) HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), id, UUID.randomUUID().toString()) else null
            journal.use {
                val resources = journal?.enableRecordingResources()
                val device = Device().apply { sessionScratch = persistent }
                val sink = RecordingSink()
                val recording = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                    config().copy(recordingStopTimeout = Duration.ofSeconds(3)), journal).start(sink)
                device.finished = true // The URI and a structurally valid file exist while capture remains active.
                var endChecks = 0
                device.response = { arguments ->
                    when {
                        arguments.first() == "aa" -> {
                            device.stops++
                            assertThat(sink.bytes.size).isEqualTo(0)
                            "start ability successfully.\n" // Acknowledgement alone does not end capture.
                        }
                        arguments.first() == "hidumper" && device.stops > 0 -> {
                            if (++endChecks == 3) device.finishExternally()
                            null
                        }
                        arguments.take(2) == listOf("mediatool", "recv") -> {
                            assertThat(endChecks).isEqualTo(3)
                            assertThat(device.capturePids).isEmpty()
                            resources?.let {
                                assertThat(jacksonObjectMapper().readTree(it.path.toFile())["resources"][0]["captureEnded"].booleanValue()).isTrue()
                            }
                            null
                        }
                        else -> null
                    }
                }

                recording.close()
                recording.close()

                assertThat(device.stops).isEqualTo(1)
                assertThat(endChecks).isEqualTo(3)
                assertThat(device.receives).isEqualTo(1)
                assertThat(device.deletes).containsExactly(device.uri)
                assertThat(sink.bytes.readByteArray()).isEqualTo(device.video)
                assertThat(sink.flushed).isTrue()
                resources?.let { assertThat(it.isComplete()).isTrue(); assertThat(it.verifyArchives()).isTrue() }
            }
        }
    }

    @Test fun `readable media is retained when capture outlives the stop budget with or without a journal`() = TempFileHandler().use { files ->
        for (persistent in listOf(false, true)) {
            val directory = files.createTempDirectory("recording-capture-timeout").toPath()
            val id = UUID.randomUUID().toString()
            val journal = if (persistent) HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), id, UUID.randomUUID().toString()) else null
            journal.use {
                val resources = journal?.enableRecordingResources()
                val device = Device().apply { sessionScratch = persistent }
                val sink = RecordingSink()
                val recording = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                    config().copy(recordingStopTimeout = Duration.ofMillis(500)), journal).start(sink)
                device.finished = true
                device.response = { arguments ->
                    if (arguments.first() == "aa") {
                        device.stops++
                        "start ability successfully.\n"
                    } else null
                }

                val failure = assertThrows(HdcTimeoutException::class.java) { recording.close() }
                assertThat(assertThrows(HdcTimeoutException::class.java) { recording.close() }).isSameInstanceAs(failure)
                assertThat(device.stops).isEqualTo(1)
                assertThat(device.capturePids).containsExactly(51)
                assertThat(device.commands.none { it.take(2) == listOf("mediatool", "recv") }).isTrue()
                assertThat(device.receives).isEqualTo(0)
                assertThat(device.deletes).isEmpty()
                assertThat(sink.bytes.size).isEqualTo(0)
                assertThat(sink.flushed).isFalse()
                resources?.let {
                    val entry = jacksonObjectMapper().readTree(it.path.toFile())["resources"][0]
                    assertThat(entry["stopRequested"].booleanValue()).isTrue()
                    assertThat(entry["captureEnded"].booleanValue()).isFalse()
                    assertThat(it.isComplete()).isFalse()
                }
            }
        }
    }

    @Test fun `a stop that executes before losing its reply is not retried or accepted with or without a journal`() = TempFileHandler().use { files ->
        for (persistent in listOf(false, true)) {
            val directory = files.createTempDirectory("recording-lost-stop-reply").toPath()
            val id = UUID.randomUUID().toString()
            val journal = if (persistent) HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), id, UUID.randomUUID().toString()) else null
            journal.use {
                val resources = journal?.enableRecordingResources()
                val device = Device().apply { sessionScratch = persistent }
                val sink = RecordingSink()
                val recording = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                    config().copy(recordingStopTimeout = Duration.ofSeconds(3)), journal).start(sink)
                val original = HdcException("stop executed but reply lost")
                device.response = { arguments ->
                    if (arguments.first() == "aa") {
                        device.stops++
                        device.finishExternally()
                        throw original
                    }
                    null
                }

                assertThat(assertThrows(HdcException::class.java) { recording.close() }).isSameInstanceAs(original)
                assertThat(assertThrows(HdcException::class.java) { recording.close() }).isSameInstanceAs(original)
                assertThat(device.stops).isEqualTo(1)
                assertThat(device.commands.count { it.first() == "aa" }).isEqualTo(2)
                assertThat(device.capturePids).isEmpty()
                assertThat(device.receives).isEqualTo(0)
                assertThat(device.deletes).isEmpty()
                assertThat(sink.bytes.size).isEqualTo(0)
                resources?.let {
                    val entry = jacksonObjectMapper().readTree(it.path.toFile())["resources"][0]
                    assertThat(entry["stopRequested"].booleanValue()).isTrue()
                    assertThat(entry["captureEnded"].booleanValue()).isFalse()
                    assertThat(it.isComplete()).isFalse()
                }
            }
        }
    }

    @Test fun `retention records dispatch before commands and preserves an original after stopping`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("recording-retention-test").toPath()
        val id = "fixture-device"
        HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), id, UUID.randomUUID().toString()).use { journal ->
            val resources = journal.enableRecordingResources()
            val mapper = jacksonObjectMapper()
            val device = Device().apply { sessionScratch = true }
            device.response = { arguments ->
                if (arguments.first() == "aa") {
                    val entry = mapper.readTree(resources.path.toFile())["resources"][0]
                    assertThat(entry[if (arguments.contains("--ps")) "startRequested" else "stopRequested"].booleanValue()).isTrue()
                }
                null
            }
            val sink = RecordingSink()
            val recorder = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                config().copy(recordingRetainMedia = true, recordingStopTimeout = Duration.ofSeconds(3)), journal)
            recorder.start(sink).close()
            assertThat(device.stops).isEqualTo(1)
            assertThat(device.deletes).isEmpty()
            assertThat(sink.flushed).isTrue()
            assertThat(resources.isComplete()).isFalse()
            assertThat(resources.verifyArchives()).isTrue()
            val entry = mapper.readTree(resources.path.toFile())["resources"][0]
            assertThat(entry["captureEnded"].booleanValue()).isTrue()
            assertThat(entry["uri"].asText()).isEqualTo(device.uri)
            val archive = directory.resolve("recording-originals").resolve(entry["archiveName"].asText())
            assertThat(Files.readAllBytes(archive)).isEqualTo(device.video)
        }
    }

    @Test fun `retention cannot start outside a durable recording session`() {
        val device = Device()
        assertThrows(IllegalStateException::class.java) { recorder(device, config().copy(recordingRetainMedia = true)).start(Buffer()) }
        assertThat(device.commands).isEmpty()
    }

    @Test fun `invalid stopped recording is archived and recoverable without accepting its video`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("invalid-recording-recovery").toPath()
        val journalPath = directory.resolve("recovery-journal.json")
        val device = Device().apply { sessionScratch = true; video = "invalid container bytes".toByteArray() }
        val sink = RecordingSink()
        lateinit var marker: RecordingResourceFailure
        HarmonyOSRecoveryJournal.create(journalPath, "invalid-fixture", UUID.randomUUID().toString()).use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = HarmonyOSSystemScreenRecorder("invalid-fixture", device, device::receive,
                config().copy(recordingStopTimeout = Duration.ofSeconds(2), pollInterval = Duration.ofMillis(100)), journal).start(sink)
            val failure = assertThrows(HdcTimeoutException::class.java) { recording.close() }
            assertThat(failure.suppressed.any { it is InvalidMp4RecordingException }).isTrue()
            assertThat(assertThrows(HdcTimeoutException::class.java) { recording.close() }).isSameInstanceAs(failure)
            marker = failure.suppressed.filterIsInstance<RecordingResourceFailure>().last()
            assertThat(resources.isComplete()).isFalse()
            assertThat(resources.verifyArchives()).isTrue()
            val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
            assertThat(entry["captureEnded"].booleanValue()).isTrue()
            assertThat(entry["uri"].asText()).isEqualTo(device.uri)
            assertThat(entry["archives"].size()).isAtLeast(1)
            entry["archives"].forEach { archive ->
                assertThat(Files.readAllBytes(directory.resolve("recording-originals").resolve(archive["name"].asText())))
                    .isEqualTo(device.video)
            }
        }
        assertThat(sink.bytes.size).isEqualTo(0)
        assertThat(sink.flushed).isFalse()
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.deletes).isEmpty()
        assertThat(Files.exists(device.localPath)).isFalse()
        val remote = MediaRemote(device.uri).apply { video = device.video }
        HarmonyOSRecoveryJournal.open(journalPath).use { reopened ->
            val resources = checkNotNull(reopened.recordings)
            assertThat(resources.recover(HdcClient("fake", remote), reopened, Duration.ofSeconds(3))).isEmpty()
            assertThat(resources.isComplete()).isTrue()
            assertThat(resources.resolvesFailure(marker.recordingId, marker.fingerprint)).isTrue()
            assertThat(resources.verifyArchives()).isTrue()
        }
        assertThat(remote.deleted).containsExactly(device.uri)
        assertThat(remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
        assertThat(sink.bytes.size).isEqualTo(0)
    }

    @Test fun `later valid export preserves the preceding invalid original`(): Unit = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("recording-finalization-history").toPath()
        val invalid = "unfinished container".toByteArray()
        val valid = Mp4RecordingValidatorTest.minimalVideoRecording()
        val device = Device().apply {
            sessionScratch = true; video = invalid
            response = { arguments ->
                if (arguments.take(2) == listOf("mediatool", "recv") && receives == 1) video = valid
                null
            }
        }
        HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), "finalization-fixture", UUID.randomUUID().toString()).use { journal ->
            val resources = journal.enableRecordingResources()
            val sink = RecordingSink()
            val diagnostics = mutableListOf<String>()
            HarmonyOSSystemScreenRecorder("finalization-fixture", device, device::receive,
                config().copy(recordingStopTimeout = Duration.ofSeconds(3)), journal,
                { name, resourceId, contextId -> HarmonyOSRecordingDiagnostics(name, resourceId, contextId, emitLine = diagnostics::add) }).start(sink).close()
            assertThat(sink.bytes.readByteArray()).isEqualTo(valid)
            assertThat(resources.isComplete()).isTrue()
            assertThat(resources.verifyArchives()).isTrue()
            val archives = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]["archives"]
            assertThat(archives.size()).isEqualTo(2)
            assertThat(Files.readAllBytes(directory.resolve("recording-originals").resolve(archives[0]["name"].asText()))).isEqualTo(invalid)
            assertThat(Files.readAllBytes(directory.resolve("recording-originals").resolve(archives[1]["name"].asText()))).isEqualTo(valid)
            assertThat(device.deletes).containsExactly(device.uri)
            val events = diagnostics.map { jacksonObjectMapper().readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
            assertThat(events.filter { it["stage"].asText() == "export" && it["fields"]["result"].asText() == "begin" }
                .map { it["fields"]["attempt"].asInt() }).containsExactly(1, 2).inOrder()
            assertThat(events.any { it["stage"].asText() == "validate" && it["fields"]["result"].asText() == "invalid" }).isTrue()
            assertThat(events.count { it["stage"].asText() == "archive" && it["fields"]["result"].asText() == "returned" }).isEqualTo(2)
            val archiveEvents = events.filter { it["stage"].asText() == "archive" && it["fields"]["result"].asText() == "observed" }
            assertThat(archiveEvents.map { it["fields"]["attempt"].asInt() }).containsExactly(1, 2).inOrder()
            for ((index, event) in archiveEvents.withIndex()) {
                assertThat(event["fields"]["archiveMetadataUnavailable"].asBoolean()).isFalse()
                assertThat(event["fields"]["archiveBytes"].asLong()).isEqualTo(archives[index]["size"].asLong())
                assertThat(event["fields"]["archiveSha256"].asText()).isEqualTo(archives[index]["sha256"].asText())
            }
            assertThat(events.map { it["resourceId"].asText() }.toSet()).containsExactly(
                jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]["id"].asText())
        }
    }

    @Test fun `failed invalid video archival keeps media and the validation error`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("recording-archive-failure").toPath()
        val device = Device().apply { sessionScratch = true; video = "invalid container".toByteArray() }
        HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), "archive-failure-fixture", UUID.randomUUID().toString()).use { journal ->
            val resources = journal.enableRecordingResources()
            val sink = RecordingSink()
            val diagnostics = mutableListOf<String>()
            val recording = HarmonyOSSystemScreenRecorder("archive-failure-fixture", device, device::receive,
                config().copy(recordingStopTimeout = Duration.ofSeconds(3)), journal,
                { name, resourceId, contextId -> HarmonyOSRecordingDiagnostics(name, resourceId, contextId, emitLine = diagnostics::add) }).start(sink)
            Files.writeString(directory.resolve("recording-originals"), "prevent archive directory creation")
            val failure = assertThrows(IOException::class.java) { recording.close() }
            assertThat(failure.suppressed.any { it is InvalidMp4RecordingException }).isTrue()
            assertThat(resources.isComplete()).isFalse()
            assertThat(resources.verifyArchives()).isFalse()
            val entry = jacksonObjectMapper().readTree(resources.path.toFile())["resources"][0]
            assertThat(entry["captureEnded"].booleanValue()).isTrue()
            assertThat(entry["archiveName"].isNull).isTrue()
            assertThat(device.deletes).isEmpty()
            assertThat(sink.bytes.size).isEqualTo(0)
            val events = diagnostics.map { jacksonObjectMapper().readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
            assertThat(events.any { it["stage"].asText() == "archive" && it["fields"]["result"].asText() == "failed" }).isTrue()
            assertThat(events.any { it["fields"]["archiveBytes"] != null || it["fields"]["archiveSha256"] != null }).isFalse()
        }
    }

    @Test fun `duration error is attributable after the recorder has stopped and archived`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("recording-expiry-test").toPath()
        val id = "expiry-device"
        HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), id, UUID.randomUUID().toString()).use { journal ->
            val resources = journal.enableRecordingResources()
            val device = Device().apply { sessionScratch = true }
            val diagnostics = mutableListOf<String>()
            val recording = HarmonyOSSystemScreenRecorder(id, device, device::receive,
                config().copy(recordingMaxDuration = Duration.ofSeconds(1), recordingStopTimeout = Duration.ofSeconds(3)), journal,
                { name, resourceId, contextId -> HarmonyOSRecordingDiagnostics(name, resourceId, contextId, emitLine = diagnostics::add) }).start(Buffer())
            assertThat(device.deleted.await(5, TimeUnit.SECONDS)).isTrue()
            val failure = assertThrows(HdcException::class.java) { recording.close() }
            assertThat(failure.message).contains("1 second limit")
            val marker = failure.suppressed.filterIsInstance<RecordingResourceFailure>().last()
            assertThat(marker.fingerprint).isEqualTo(HarmonyOSRecordingResourceJournal.failureFingerprint(failure))
            assertThat(resources.ownsFailure(marker.recordingId, marker.fingerprint)).isTrue()
            assertThat(resources.isComplete()).isTrue()
            assertThat(resources.verifyArchives()).isTrue()
            assertThat(device.stops).isEqualTo(1)
            val events = diagnostics.map { jacksonObjectMapper().readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
            val terminal = events.single { it["fields"]["terminal"]?.asBoolean() == true }
            assertThat(terminal).isEqualTo(events.last())
            assertThat(terminal["stage"].asText()).isEqualTo("close")
            assertThat(terminal["fields"]["result"].asText()).isEqualTo("failed")
            assertThat(events.any { it["stage"].asText() == "close" && it["fields"]["result"].asText() == "returned" }).isFalse()
            assertThat(events.indexOfFirst { it["stage"].asText() == "limit_reached" }).isLessThan(
                events.indexOfLast { it["stage"].asText() == "resource_failure" && it["fields"]["result"].asText() == "returned" })
            assertThat(events.indexOfLast { it["stage"].asText() == "resource_failure" }).isLessThan(events.lastIndex)
        }
    }

    @Test
    fun `timer diagnostic terminal preserves a close failure with the limit suppressed`() {
        val device = Device()
        val original = IOException("flush failed during timer close")
        val sink = RecordingSink().apply { flushFailure = original }
        val diagnostics = mutableListOf<String>()
        val terminalWritten = CountDownLatch(1)
        val recording = HarmonyOSSystemScreenRecorder(UUID.randomUUID().toString(), device, device::receive,
            config().copy(recordingMaxDuration = Duration.ofSeconds(1), recordingStopTimeout = Duration.ofSeconds(3)), null,
            { name, resourceId, contextId -> HarmonyOSRecordingDiagnostics(name, resourceId, contextId, emitLine = { line ->
                diagnostics += line
                if (jacksonObjectMapper().readTree(line.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX))["fields"]["terminal"]?.asBoolean() == true) {
                    terminalWritten.countDown()
                }
            }) }).start(sink)
        assertThat(terminalWritten.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(assertThrows(IOException::class.java) { recording.close() }).isSameInstanceAs(original)
        assertThat(original.suppressed.any { it is HdcException && it.message?.contains("1 second limit") == true }).isTrue()
        val events = diagnostics.map { jacksonObjectMapper().readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
        val terminal = events.single { it["fields"]["terminal"]?.asBoolean() == true }
        assertThat(terminal).isEqualTo(events.last())
        assertThat(terminal["fields"]["result"].asText()).isEqualTo("failed")
        assertThat(terminal["fields"]["errorKind"].asText()).isEqualTo("io")
        assertThat(device.stops).isEqualTo(1)
        assertThat(device.deletes).isEmpty()
    }

    @Test fun `post transfer observation failures retain media before validation or output`(): Unit = TempFileHandler().use { files ->
        for (persistent in listOf(false, true)) {
            for (scenario in listOf("grow", "shrink", "empty", "query-failed", "short-query-failed", "receive-failed", "budget")) {
                val directory = files.createTempDirectory("recording-after-$scenario-$persistent").toPath()
                val id = UUID.randomUUID().toString()
                val journal = if (persistent) HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), id, UUID.randomUUID().toString()) else null
                journal.use {
                    val resources = journal?.enableRecordingResources()
                    val original = IOException("post-transfer fixture failure")
                    val device = Device().apply {
                        sessionScratch = persistent
                        if (scenario == "short-query-failed") receivedVideo = video.copyOf(video.size / 2)
                        if (scenario == "receive-failed") receiveFailure = original
                        response = { arguments ->
                            if (arguments.first() == "stat" && receives > 0) when (scenario) {
                                "grow" -> (video.size + 1).toString()
                                "shrink" -> (video.size - 1).toString()
                                "empty" -> "0"
                                "query-failed", "short-query-failed" -> throw original
                                else -> null
                            } else null
                        }
                    }
                    val sink = RecordingSink()
                    val diagnostics = mutableListOf<String>()
                    val recorder = HarmonyOSSystemScreenRecorder(id, device, { remote, local, timeout ->
                        device.receive(remote, local, timeout)
                        if (scenario == "budget") Thread.sleep(260)
                    }, config().copy(recordingStopTimeout = Duration.ofMillis(if (scenario == "budget") 200 else 3000)), journal,
                        { name, resourceId, contextId -> HarmonyOSRecordingDiagnostics(name, resourceId, contextId, emitLine = diagnostics::add) })
                    val recording = recorder.start(sink)
                    val failure = assertThrows(Exception::class.java) { recording.close() }
                    assertThat(assertThrows(Exception::class.java) { recording.close() }).isSameInstanceAs(failure)
                    when (scenario) {
                        "query-failed", "receive-failed" -> assertThat(failure).isSameInstanceAs(original)
                        "short-query-failed" -> {
                            assertThat(failure.message).contains("expected file size")
                            assertThat(failure.suppressed.toList()).contains(original)
                        }
                        "budget" -> assertThat(failure).isInstanceOf(HdcTimeoutException::class.java)
                        else -> assertThat(failure.message).contains("changed size during transfer")
                    }
                    assertThat(device.stops).isEqualTo(1)
                    assertThat(device.receives).isEqualTo(1)
                    assertThat(device.deletes).isEmpty()
                    assertThat(sink.bytes.size).isEqualTo(0)
                    assertThat(sink.flushCalls).isEqualTo(0)
                    val stats = device.commands.filter { it.first() == "stat" }
                    assertThat(stats.size).isEqualTo(if (scenario in setOf("receive-failed", "budget")) 1 else 2)
                    assertThat(stats.map { it.last() }.toSet()).hasSize(1)
                    val events = diagnostics.map { jacksonObjectMapper().readTree(it.removePrefix(HarmonyOSRecordingDiagnostics.PREFIX)) }
                    assertThat(events.none { it["stage"].asText() in setOf("validate", "archive", "copy", "flush", "delete") }).isTrue()
                    val observed = events.filter { it["stage"].asText() == "stat_after_receive" && it["fields"]["result"].asText() == "observed" }
                    if (scenario in setOf("grow", "shrink", "empty")) {
                        val expected = when (scenario) { "grow" -> device.video.size + 1; "shrink" -> device.video.size - 1; else -> 0 }
                        assertThat(observed).hasSize(1)
                        assertThat(observed.single()["fields"]["remoteAfterBytes"].asInt()).isEqualTo(expected)
                    } else assertThat(observed).isEmpty()
                    if (scenario == "receive-failed") assertThat(events.none { it["stage"].asText() == "stat_after_receive" }).isTrue()
                    resources?.let {
                        val resource = jacksonObjectMapper().readTree(it.path.toFile())["resources"][0]
                        assertThat(resource["captureEnded"].asBoolean()).isTrue()
                        assertThat(resource["archiveName"].isNull).isTrue()
                        assertThat(resource["mediaRemoved"].asBoolean()).isFalse()
                        assertThat(resource["clean"].asBoolean()).isFalse()
                    }
                }
            }
        }
    }

    private fun config() = HarmonyOSDriverConfig(
        enableScreenRecording = true,
        pollInterval = Duration.ofMillis(1),
        recordingStopTimeout = Duration.ofMillis(200),
    )

    private class RecordingSink : Sink {
        val bytes = Buffer()
        var flushed = false
        var closed = false
        var writeFailure: Throwable? = null
        var flushFailure: Throwable? = null
        var flushCalls = 0
        override fun write(source: Buffer, byteCount: Long) { writeFailure?.let { throw it }; bytes.write(source, byteCount) }
        override fun flush() { flushCalls++; flushFailure?.let { throw it }; flushed = true }
        override fun timeout() = Timeout.NONE
        override fun close() { closed = true }
    }

    private class Device : HarmonyOSDevice {
        var recorderPids: Set<Int> = emptySet()
        var capturePids: Set<Int> = emptySet()
        var fileName: String? = null
        var finished = false
        var wasDeleted = false
        var starts = 0
        var stops = 0
        var receives = 0
        var video = Mp4RecordingValidatorTest.minimalVideoRecording()
        var receivedVideo: ByteArray? = null
        var localPath: Path? = null
        var receiveFailure: Throwable? = null
        var sessionScratch = false
        var response: (List<String>) -> String? = { null }
        val commands = mutableListOf<List<String>>()
        val deletes = mutableListOf<String>()
        val deleted = CountDownLatch(1)
        val uri get() = "file://media/Photo/123/VID_123_456/$fileName"

        override fun connect() = Unit
        override fun isAvailable() = true
        override fun hierarchy(timeout: Duration): TreeNode = error("unused")
        override fun screenshot(timeout: Duration): ByteArray = error("unused")

        @Synchronized
        override fun execute(arguments: List<String>, timeout: Duration): String {
            assertThat(timeout.isNegative || timeout.isZero).isFalse()
            commands += arguments
            response(arguments)?.let { return it }
            return when (arguments.first()) {
                "bm" -> "com.huawei.hmos.screenrecorder.ServiceExtAbility"
                "ps" -> "UID PID PPID C STIME TTY TIME CMD\n" + recorderPids.joinToString("\n") {
                    "20020154 $it 575 0 02:41:36 ? 00:00:00 com.huawei.hmos.screenrecorder"
                }
                "hidumper" -> "PlayerDistributedService\n--RecorderServer--:--ScreenCaptureServer--:" +
                    capturePids.mapIndexed { index, pid -> "-----#$index: pid = $pid-----" }.joinToString("\n") +
                    "\n------------------XCollieDfx------------------\n"
                "aa" -> {
                    if (arguments.contains("--ps")) {
                        starts++
                        fileName = arguments.last()
                        recorderPids = setOf(51)
                        capturePids = setOf(51)
                    } else {
                        stops++
                        finishExternally()
                    }
                    "start ability successfully.\n"
                }
                "mediatool" -> when (arguments[1]) {
                    "help" -> "query <display-name> [-p] [-u]\nrecv <src-media> <dest-path>\ndelete <media-uri>\n"
                    "query" -> if (finished && !wasDeleted) "find 1 result\nuri\n\"$uri\"\n" else {
                        throw HarmonyOSCommandException("Device command failed (exit 255)", "find 0 result\n", "", 255)
                    }
                    "recv" -> {
                        assertThat(arguments[2]).isEqualTo(uri)
                        checkRemote(arguments[3])
                        "Table Name: Photos\n${arguments[3]}\n"
                    }
                    "delete" -> {
                        deletes += arguments[2]
                        wasDeleted = true
                        deleted.countDown()
                        "[SUCCESS] delete success.\n"
                    }
                    else -> error("Unexpected mediatool command: $arguments")
                }
                "rm" -> ""
                "stat" -> "${video.size}\n"
                else -> error("Unexpected command: $arguments")
            }
        }

        fun receive(remote: String, path: Path, timeout: Duration) {
            checkRemote(remote)
            assertThat(timeout.isNegative || timeout.isZero).isFalse()
            receives++
            localPath = path
            receiveFailure?.let { throw it }
            Files.write(path, receivedVideo ?: video)
        }

        private fun checkRemote(remote: String) {
            if (sessionScratch) assertThat(remote).matches("/data/local/tmp/maestro-session-[0-9a-f-]{36}/files/recording-[0-9a-f-]{36}\\.mp4")
            else assertThat(remote).isEqualTo("/data/local/tmp/$fileName")
        }

        fun finishExternally() { finished = true; capturePids = emptySet() }
    }
}
