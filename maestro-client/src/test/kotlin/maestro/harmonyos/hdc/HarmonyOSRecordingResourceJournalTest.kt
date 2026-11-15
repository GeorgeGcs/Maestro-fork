package maestro.harmonyos.hdc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class HarmonyOSRecordingResourceJournalTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private fun create() = HarmonyOSRecoveryJournal.create(directory.resolve("recovery-journal.json"), "device", UUID.randomUUID().toString())

    @Test fun `archive and hash deadline boundaries retain timeout semantics and registered originals`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting(); recording.started(51); recording.stopping(); recording.ended()
            recording.media("file://media/Photo/123/VID_123/${recording.fileName}")
            val original = directory.resolve("original.mp4")
            Files.writeString(original, "registered bytes")
            recording.archive(original)
            val retained = checkNotNull(recording.latestArchive())
            val stateBefore = Files.readString(resources.path)
            Files.writeString(original, "replacement bytes")
            val timeout = Duration.ofSeconds(2)
            // Read 1 creates the deadline. Reads 2, 4 and 6 precede copying, destination hashing and source hashing.
            for (expiredRead in listOf(2, 4, 6)) {
                var reads = 0
                val failure = assertThrows<HdcTimeoutException> {
                    recording.archive(original, timeout) { if (++reads >= expiredRead) timeout.toNanos() else 0L }
                }
                assertThat(reads).isEqualTo(expiredRead)
                assertThat(failure.timeout).isEqualTo(timeout)
                assertThat(failure.message).isEqualTo("Recording archive or hash exceeded the shared operation deadline.")
                assertThat(Files.readString(resources.path)).isEqualTo(stateBefore)
                assertThat(recording.latestArchive()).isEqualTo(retained)
                assertThat(Files.readString(directory.resolve("recording-originals").resolve(retained.name))).isEqualTo("registered bytes")
                assertThat(Files.readString(original)).isEqualTo("replacement bytes")
                assertThat(resources.isComplete()).isFalse()
            }
        }
    }

    @Test fun `interruption remains cancellation even when an archive deadline has expired`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting(); recording.started(51); recording.stopping(); recording.ended()
            recording.media("file://media/Photo/123/VID_123/${recording.fileName}")
            val original = directory.resolve("original.mp4")
            Files.writeString(original, "original bytes")
            val stateBefore = Files.readString(resources.path)
            var reads = 0
            Thread.currentThread().interrupt()
            try {
                assertThrows<InterruptedException> {
                    recording.archive(original, Duration.ofSeconds(2)) { if (++reads == 1) 0L else Long.MAX_VALUE }
                }
                assertThat(Thread.currentThread().isInterrupted).isTrue()
                assertThat(reads).isEqualTo(1)
            } finally {
                Thread.interrupted()
            }
            assertThat(Files.readString(resources.path)).isEqualTo(stateBefore)
            assertThat(recording.latestArchive()).isNull()
            assertThat(Files.readString(original)).isEqualTo("original bytes")
        }
    }

    @Test fun `latest archive metadata is a copy of registered values and does not revalidate files`(): Unit {
        create().use { journal ->
            journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            assertThat(recording.latestArchive()).isNull()
            recording.starting(); recording.started(51); recording.stopping(); recording.ended()
            recording.media("file://media/Photo/123/VID_123/${recording.fileName}")
            val original = directory.resolve("original.mp4")
            Files.writeString(original, "first original")
            recording.archive(original)
            val first = checkNotNull(recording.latestArchive())
            assertThat(recording.latestArchive()).isEqualTo(first)
            assertThat(recording.latestArchive()).isNotSameInstanceAs(first)
            Files.writeString(original, "second original with different bytes")
            recording.archive(original)
            val second = checkNotNull(recording.latestArchive())
            assertThat(second).isNotEqualTo(first)
            assertThat(first.size).isEqualTo("first original".length.toLong())
            assertThat(second.size).isEqualTo("second original with different bytes".length.toLong())
            Files.delete(original)
            Files.writeString(directory.resolve("recording-originals").resolve(second.name), "changed after archive")
            assertThat(recording.latestArchive()).isEqualTo(second)
            assertThrows<IllegalArgumentException> { recording.archive(original) }
            assertThat(recording.latestArchive()).isEqualTo(second)
        }
    }

    @Test fun `completed retained media is removed after restart with its original unchanged`(): Unit {
        val journal = create()
        val resources = journal.enableRecordingResources()
        val recording = journal.allocateRecording()
        recording.starting()
        recording.started(51)
        recording.stopping()
        recording.ended()
        val uri = "file://media/Photo/123/VID_123/${recording.fileName}"
        recording.media(uri)
        val original = directory.resolve("original.mp4")
        val bytes = "even an invalid video must keep its raw bytes".toByteArray()
        Files.write(original, bytes)
        recording.archive(original)
        journal.close()
        val remote = MediaRemote(uri).apply { video = bytes; scratchPresent = true }
        HarmonyOSRecoveryJournal.open(journal.path).use { reopened ->
            val loaded = checkNotNull(reopened.recordings)
            assertThat(loaded.recover(HdcClient("fake", remote), reopened, Duration.ofSeconds(1))).isEmpty()
            assertThat(loaded.isComplete()).isTrue()
            assertThat(loaded.verifyArchives()).isTrue()
            assertThat(remote.deleted).containsExactly(uri)
            assertThat(remote.scratchRemoved).containsExactly(recording.remotePath)
            assertThat(remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
            assertThat(loaded.recover(HdcClient("fake", remote), reopened, Duration.ofSeconds(1))).isEmpty()
            assertThat(remote.deleted).hasSize(1)
        }
        val archive = Files.list(directory.resolve("recording-originals")).use { it.findFirst().orElseThrow() }
        assertThat(Files.readAllBytes(archive)).isEqualTo(bytes)
    }

    @Test fun `unacknowledged start cannot be recovered from an idle device`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting()
            journal.seal()
            val noQuery = HdcClient("fake", HdcCommandRunner { _, _ -> error("No device mutation or query is needed") })
            assertThat(resources.recover(noQuery, journal, Duration.ofSeconds(1)).map { it.code }).contains("RECORDING_CAPTURE_UNCONFIRMED")
            assertThat(resources.isComplete()).isFalse()
        }
    }

    @Test fun `explicitly rejected locked startup is released only after two absent observations`(): Unit {
        val journal = create()
        val resources = journal.enableRecordingResources()
        val recording = journal.allocateRecording()
        recording.starting()
        val error = HdcException("Device command reported a failure\nerror: failed to start ability.\nError Code:10106102  Error Message:The device screen is locked during the application launch")
        recording.failed(error)
        journal.seal()
        journal.close()
        val remote = MediaRemote("file://media/Photo/123/VID_123/${recording.fileName}").apply {
            present = false; recorderProcesses = emptySet()
        }
        HarmonyOSRecoveryJournal.open(journal.path).use { reopened ->
            val loaded = checkNotNull(reopened.recordings)
            val permission = HarmonyOSRecordingResourceJournal.RejectedStartPermission(recording.id,
                HarmonyOSRecordingResourceJournal.failureFingerprint(error), 10106102)
            assertThat(loaded.recover(HdcClient("fake", remote), reopened, Duration.ofSeconds(10), permission)).isEmpty()
            assertThat(loaded.isComplete()).isTrue()
            assertThat(loaded.verifyArchives()).isTrue()
            val state = mapper.readTree(loaded.path.toFile())
            assertThat(state["schemaVersion"].intValue()).isEqualTo(2)
            val rejected = state["resources"][0]["rejectedStart"]
            assertThat(rejected["errorCode"].intValue()).isEqualTo(10106102)
            assertThat(state["resources"][0]["mediaRemoved"].booleanValue()).isFalse()
            assertThat(remote.captureReads).isEqualTo(2)
            assertThat(remote.queries).isEqualTo(2)
            assertThat(remote.commands.none { it.contains("'aa' 'start'") || it.contains("mediatool") && it.contains("delete") }).isTrue()
        }
        HarmonyOSRecoveryJournal.open(journal.path).use { reopened ->
            assertThat(reopened.recordings!!.isComplete()).isTrue()
            assertThat(reopened.recordings!!.verifyArchives()).isTrue()
        }
    }

    @Test fun `rejected startup permission cannot hide a late active capture`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting()
            val error = HdcException("rejected start")
            recording.failed(error)
            journal.seal()
            val remote = MediaRemote("file://media/Photo/123/VID_123/${recording.fileName}").apply {
                present = false; recorderProcesses = emptySet(); activeAtCaptureRead = 2
            }
            val permission = HarmonyOSRecordingResourceJournal.RejectedStartPermission(recording.id,
                HarmonyOSRecordingResourceJournal.failureFingerprint(error), 10106102)
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(10), permission).map { it.code })
                .contains("RECORDING_CAPTURE_UNCONFIRMED")
            assertThat(resources.isComplete()).isFalse()
            assertThat(mapper.readTree(resources.path.toFile())["schemaVersion"].intValue()).isEqualTo(1)
        }
    }

    @Test fun `active capture corrupted archive or changed URI cannot delete media`(): Unit {
        for (scenario in listOf("active", "archive", "missing", "uri")) {
            val root = Files.createDirectory(directory.resolve(scenario))
            HarmonyOSRecoveryJournal.create(root.resolve("recovery-journal.json"), "device", UUID.randomUUID().toString()).use { journal ->
                val resources = journal.enableRecordingResources()
                val recording = journal.allocateRecording()
                recording.starting(); recording.started(51); recording.stopping(); recording.ended()
                val uri = "file://media/Photo/123/VID_123/${recording.fileName}"
                recording.media(uri)
                val original = root.resolve("raw.mp4")
                Files.writeString(original, "original bytes")
                recording.archive(original)
                if (scenario == "archive") {
                    Files.list(root.resolve("recording-originals")).use { Files.writeString(it.findFirst().orElseThrow(), "changed") }
                }
                if (scenario == "missing") {
                    Files.list(root.resolve("recording-originals")).use { Files.delete(it.findFirst().orElseThrow()) }
                }
                val remote = MediaRemote(if (scenario == "uri") uri.replace("/123/", "/999/") else uri)
                remote.active = scenario == "active"
                journal.seal()
                assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(1))).isNotEmpty()
                assertThat(resources.isComplete()).isFalse()
                assertThat(remote.deleted).isEmpty()
            }
        }
    }

    @Test fun `failed delete acknowledgement can be reconciled without another delete`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting(); recording.started(51); recording.stopping(); recording.ended()
            val uri = "file://media/Photo/123/VID_123/${recording.fileName}"
            recording.media(uri)
            val raw = directory.resolve("raw.mp4")
            Files.writeString(raw, "raw"); recording.archive(raw)
            val remote = MediaRemote(uri).apply { loseDeleteReply = true }
            journal.seal()
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(1))).isNotEmpty()
            assertThat(resources.isComplete()).isFalse()
            assertThat(journal.recover(HdcClient("fake", remote), cleanupResources = false).pendingOperations).isEqualTo(0)
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(1))).isEmpty()
            assertThat(remote.deleted).containsExactly(uri)
        }
    }

    @Test fun `changed source keeps both originals and needs a later stable comparison before deletion`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting(); recording.started(51); recording.stopping(); recording.ended()
            val uri = "file://media/Photo/123/VID_123/${recording.fileName}"
            recording.media(uri)
            val raw = directory.resolve("raw.mp4")
            Files.writeString(raw, "first bytes"); recording.archive(raw)
            val remote = MediaRemote(uri).apply { video = "later finalized bytes".toByteArray() }
            journal.seal()
            val result = resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(2))
            assertThat(result.map { it.code }).contains("RECORDING_MEDIA_CHANGED")
            assertThat(remote.deleted).isEmpty()
            assertThat(resources.isComplete()).isFalse()
            val archives = mapper.readTree(resources.path.toFile())["resources"][0]["archives"]
            assertThat(archives.size()).isEqualTo(2)
            val bytes = archives.map { Files.readString(directory.resolve("recording-originals").resolve(it["name"].asText())) }
            assertThat(bytes).containsExactly("first bytes", "later finalized bytes")
            assertThat(journal.recover(HdcClient("fake", remote), cleanupResources = false).pendingOperations).isEqualTo(0)
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(2))).isEmpty()
            assertThat(remote.deleted).containsExactly(uri)
            assertThat(remote.scratchRemoved).containsExactly(recording.remotePath)
        }
    }

    @Test fun `lost scratch removal reply remains unknown until its command proof is reconciled`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting(); recording.started(51); recording.stopping(); recording.ended()
            val uri = "file://media/Photo/123/VID_123/${recording.fileName}"
            recording.media(uri)
            val raw = directory.resolve("raw.mp4")
            Files.writeString(raw, "raw"); recording.archive(raw)
            val remote = MediaRemote(uri).apply { scratchPresent = true; loseScratchReply = true }
            journal.seal()
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(1))).isNotEmpty()
            assertThat(resources.isComplete()).isFalse()
            assertThat(remote.deleted).isEmpty()
            assertThat(remote.exports).isEqualTo(0)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
            assertThat(journal.recover(HdcClient("fake", remote), cleanupResources = false).pendingOperations).isEqualTo(0)
            assertThat(resources.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(2))).isEmpty()
            assertThat(resources.isComplete()).isTrue()
            assertThat(resources.verifyArchives()).isTrue()
            assertThat(remote.deleted).containsExactly(uri)
            assertThat(remote.exports).isEqualTo(1)
        }
    }

    @Test fun `stop intent survives reopening and never permits a second dispatch`(): Unit {
        create().use { journal ->
            val resources = journal.enableRecordingResources()
            val recording = journal.allocateRecording()
            recording.starting(); recording.started(51); recording.stopping()
            assertThat(mapper.readTree(resources.path.toFile())["resources"][0]["stopRequested"].booleanValue()).isTrue()
            assertThrows<IllegalStateException> { recording.stopping() }
        }
        HarmonyOSRecoveryJournal.open(directory.resolve("recovery-journal.json")).use { journal ->
            val resource = mapper.readTree(journal.recordings!!.path.toFile())["resources"][0]
            val remote = MediaRemote("file://media/Photo/123/VID_123/${resource["fileName"].asText()}").apply { present = false }
            assertThat(journal.recordings!!.recover(HdcClient("fake", remote), journal, Duration.ofSeconds(1))).isNotEmpty()
            assertThat(remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
        }
    }

    @Test fun `confirmed end is archived first and only a reopened recovery may delete`(): Unit {
        for (stopRequested in listOf(false, true)) {
            val root = Files.createDirectory(directory.resolve("stop-$stopRequested"))
            val fixture = pending(root)
            val bytes = "first independently preserved recording".toByteArray()
            fixture.remote.video = bytes
            if (stopRequested) fixture.recording.stopping()
            fixture.journal.seal()
            fixture.journal.use { journal ->
                val result = fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))
                assertThat(result.map { it.code }).containsExactly("RECORDING_ARCHIVED_RECHECK_REQUIRED")
                assertThat(entry(fixture.resources)["captureEnded"].booleanValue()).isTrue()
                assertThat(entry(fixture.resources)["stopRequested"].booleanValue()).isEqualTo(stopRequested)
                assertThat(fixture.resources.verifyArchives()).isTrue()
                assertThat(archiveBytes(fixture.resources)).containsExactly(bytes.toList())
                assertThat(fixture.remote.deleted).isEmpty()
                assertThat(fixture.remote.scratchRemoved).isEmpty()
                assertThat(fixture.remote.exports).isEqualTo(1)
            }
            HarmonyOSRecoveryJournal.open(fixture.journal.path).use { journal ->
                assertThat(journal.recordings!!.recover(fixture.client, journal, Duration.ofSeconds(2))).isEmpty()
                assertThat(journal.recordings!!.isComplete()).isTrue()
                assertThat(archiveBytes(journal.recordings!!)).containsExactly(bytes.toList())
            }
            assertThat(fixture.remote.exports).isEqualTo(2)
            assertThat(fixture.remote.deleted).containsExactly(fixture.uri)
            assertThat(fixture.remote.commands.none { it.contains("'aa'") || it.contains("kill") }).isTrue()
        }
    }

    @Test fun `existing scratch is preserved before a changed export and both originals survive`(): Unit {
        val fixture = pending(directory)
        val first = "old failed export A".toByteArray()
        val second = "later media B with other bytes".toByteArray()
        fixture.remote.apply { scratchPresent = true; scratchBytes = first; video = second }
        fixture.journal.seal()
        fixture.journal.use { journal ->
            assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code })
                .containsExactly("RECORDING_ARCHIVED_RECHECK_REQUIRED")
            assertThat(archiveBytes(fixture.resources)).containsExactly(first.toList())
            assertThat(fixture.remote.exports).isEqualTo(0)
            assertThat(fixture.remote.scratchRemoved).isEmpty()
        }
        HarmonyOSRecoveryJournal.open(fixture.journal.path).use { journal ->
            val resources = journal.recordings!!
            assertThat(resources.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code }).containsExactly("RECORDING_MEDIA_CHANGED")
            assertThat(archiveBytes(resources)).containsExactly(first.toList(), second.toList()).inOrder()
            assertThat(fixture.remote.deleted).isEmpty()
            assertThat(resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isEmpty()
            assertThat(archiveBytes(resources)).containsExactly(first.toList(), second.toList()).inOrder()
            assertThat(fixture.remote.deleted).containsExactly(fixture.uri)
        }
    }

    @Test fun `export interrupted before archive is received after reopening without replacement`(): Unit {
        val fixture = pending(directory)
        val first = "export A before the interrupted receive".toByteArray()
        fixture.remote.apply { video = first; shortReceive = true }
        fixture.journal.seal()
        fixture.journal.use { journal ->
            assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isNotEmpty()
            assertThat(entry(fixture.resources)["archives"].size()).isEqualTo(0)
            assertThat(fixture.remote.scratchBytes!!.toList()).isEqualTo(first.toList())
        }
        fixture.remote.apply { video = "B must not replace A".toByteArray(); shortReceive = false }
        HarmonyOSRecoveryJournal.open(fixture.journal.path).use { journal ->
            assertThat(journal.recordings!!.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code })
                .containsExactly("RECORDING_ARCHIVED_RECHECK_REQUIRED")
            assertThat(archiveBytes(journal.recordings!!)).containsExactly(first.toList())
            assertThat(fixture.remote.exports).isEqualTo(1)
            assertThat(fixture.remote.scratchRemoved).isEmpty()
            assertThat(fixture.remote.deleted).isEmpty()
        }
    }

    @Test fun `idle without media foreign processes active captures and changed observations cannot confirm end`(): Unit {
        for (scenario in listOf("missing", "foreign", "multiple", "active", "late", "malformed", "uri")) {
            val fixture = pending(Files.createDirectory(directory.resolve(scenario)))
            fixture.remote.apply {
                when (scenario) {
                    "missing" -> present = false
                    "foreign" -> recorderProcesses = setOf(99)
                    "multiple" -> recorderProcesses = setOf(51, 99)
                    "active" -> active = true
                    "late" -> activeAtCaptureRead = 2
                    "malformed" -> malformedProcesses = true
                    "uri" -> changedUriAtQuery = 2
                }
            }
            fixture.journal.seal()
            fixture.journal.use { journal ->
                assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isNotEmpty()
                assertThat(entry(fixture.resources)["captureEnded"].booleanValue()).isFalse()
                assertThat(entry(fixture.resources)["uri"].isNull).isTrue()
                assertThat(entry(fixture.resources)["archives"].size()).isEqualTo(0)
                assertThat(fixture.remote.exports).isEqualTo(0)
                assertThat(fixture.remote.deleted).isEmpty()
            }
        }
    }

    @Test fun `capture returning during transfer cannot archive or delete`(): Unit {
        val fixture = pending(directory)
        fixture.remote.activeAtCaptureRead = 3
        fixture.journal.seal()
        fixture.journal.use { journal ->
            assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code })
                .containsExactly("RECORDING_CAPTURE_ACTIVE")
            assertThat(entry(fixture.resources)["archives"].size()).isEqualTo(0)
            assertThat(fixture.remote.deleted).isEmpty()
            assertThat(fixture.remote.scratchRemoved).isEmpty()
        }
    }

    @Test fun `uncertain scratch types sizes and test replies never permit initial archival`(): Unit {
        for (scenario in listOf("link", "directory", "empty", "short", "stat", "stderr", "stdout", "exit", "lost")) {
            val fixture = pending(Files.createDirectory(directory.resolve(scenario)))
            fixture.remote.apply {
                scratchPresent = true
                when (scenario) {
                    "link" -> scratchLink = true
                    "directory" -> scratchRegular = false
                    "empty" -> video = byteArrayOf()
                    "short" -> shortReceive = true
                    "stat" -> unstableStat = true
                    "stderr" -> testStderr = "unexpected diagnostic"
                    "stdout" -> testStdout = " "
                    "exit" -> testExit = 2
                    "lost" -> loseTestReply = true
                }
            }
            fixture.journal.seal()
            fixture.journal.use { journal ->
                assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isNotEmpty()
                assertThat(entry(fixture.resources)["archives"].size()).isEqualTo(0)
                assertThat(fixture.remote.exports).isEqualTo(0)
                assertThat(fixture.remote.deleted).isEmpty()
                assertThat(fixture.remote.scratchRemoved).isEmpty()
            }
        }
    }

    @Test fun `lost stop response must be reconciled before resource queries`(): Unit {
        val fixture = pending(directory)
        fixture.recording.stopping()
        fixture.remote.loseStopReply = true
        val device = HdcHarmonyOSDevice("device", fixture.client.withRecoveryJournal(fixture.journal))
        assertThrows<HdcException> { device.execute(listOf("aa", "start", "-b", "recorder"), Duration.ofSeconds(1)) }
        fixture.journal.seal()
        fixture.journal.use { journal ->
            val before = fixture.remote.commands.size
            assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code })
                .contains("REMOTE_COMPLETION_UNCONFIRMED")
            assertThat(fixture.remote.commands).hasSize(before)
            assertThat(journal.recover(fixture.client, cleanupResources = false).pendingOperations).isEqualTo(0)
            assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code })
                .containsExactly("RECORDING_ARCHIVED_RECHECK_REQUIRED")
            assertThat(fixture.remote.commands.count { it.contains("'aa'") }).isEqualTo(1)
        }
    }

    @Test fun `foreign active and unregistered journals cannot advance resource recovery`(): Unit {
        for (scenario in listOf("foreign", "active", "host", "unregistered")) {
            val root = Files.createDirectory(directory.resolve(scenario))
            val fixture = pending(root)
            fixture.journal.use { journal ->
                if (scenario == "host") journal.hostCall { observe ->
                    val process = ProcessHandle.current()
                    observe(HarmonyOSRecoveryJournal.HostProcess(process.pid(), process.info().startInstant().orElseThrow().toString()))
                }
                if (scenario != "active") journal.seal()
                if (scenario == "unregistered") journal.forgetTemporaryFile(fixture.recording.remotePath)
                if (scenario == "foreign") {
                    val otherRoot = Files.createDirectory(root.resolve("other"))
                    HarmonyOSRecoveryJournal.create(otherRoot.resolve("recovery-journal.json"), "device", UUID.randomUUID().toString()).use { other ->
                        other.seal()
                        assertThat(fixture.resources.recover(fixture.client, other, Duration.ofSeconds(1)).map { it.code })
                            .containsExactly("RECORDING_OWNER_MISMATCH")
                    }
                } else assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(1))).isNotEmpty()
                assertThat(fixture.remote.commands).isEmpty()
            }
        }
    }

    @Test fun `finite scratch tests accept only the three exact recording shapes`(): Unit {
        val remote = "/data/local/tmp/maestro-session-${UUID.randomUUID()}/files/recording-${UUID.randomUUID()}.mp4"
        for (flag in listOf("-L", "-e", "-f")) assertThat(HdcClient.finiteCommand(listOf("test", flag, remote))).isTrue()
        for (arguments in listOf(listOf("test", "-d", remote), listOf("test", "-e", "/data/local/tmp/other"),
            listOf("test", "-e", remote, "-o", "true"), listOf("test", "-e", remote + "/../outside"))) {
            assertThat(HdcClient.finiteCommand(arguments)).isFalse()
        }
    }

    @Test fun `a new pending operation prevents all later recording resources from advancing`(): Unit {
        val fixture = pending(directory)
        val second = fixture.journal.allocateRecording()
        second.starting(); second.started(51); second.ended()
        val secondUri = "file://media/Photo/456/VID_456/${second.fileName}"
        second.media(secondUri)
        val original = directory.resolve("second-original.mp4")
        Files.write(original, fixture.remote.video)
        second.archive(original)
        fixture.remote.extraUris[second.fileName] = secondUri
        fixture.remote.loseTestReply = true
        fixture.journal.seal()
        fixture.journal.use { journal ->
            val result = fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))
            assertThat(result.map { it.code }).contains("REMOTE_COMPLETION_UNCONFIRMED")
            assertThat(fixture.remote.commands.none { it.contains(second.fileName) || it.contains(second.remotePath) }).isTrue()
            assertThat(fixture.remote.deleted).isEmpty()
            assertThat(mapper.readTree(fixture.resources.path.toFile())["resources"][1]["clean"].booleanValue()).isFalse()
            val before = fixture.remote.commands.size
            assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isNotEmpty()
            assertThat(fixture.remote.commands).hasSize(before)
            assertThat(journal.recover(fixture.client, cleanupResources = false).pendingOperations).isEqualTo(0)
            fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))
            assertThat(fixture.remote.commands.any { it.contains(second.fileName) }).isTrue()
            assertThat(fixture.remote.deleted).containsExactly(secondUri)
        }
    }

    @Test fun `failed comparison retains complete scratch before later media changes or disappears`(): Unit {
        for (missingMedia in listOf(false, true)) {
            val root = Files.createDirectory(directory.resolve("missing-$missingMedia"))
            val fixture = pending(root)
            val first = "verified original A".toByteArray()
            val second = "complete B despite a short receive".toByteArray()
            val third = "later source C must not overwrite B".toByteArray()
            fixture.recording.ended(); fixture.recording.media(fixture.uri)
            val original = root.resolve("A.mp4")
            Files.write(original, first); fixture.recording.archive(original)
            fixture.remote.apply { video = second; shortReceive = true }
            fixture.journal.seal()
            fixture.journal.use { journal ->
                assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isNotEmpty()
                assertThat(archiveBytes(fixture.resources)).containsExactly(first.toList())
                assertThat(fixture.remote.scratchBytes!!.toList()).isEqualTo(second.toList())
            }
            fixture.remote.apply { video = third; shortReceive = false; present = !missingMedia }
            HarmonyOSRecoveryJournal.open(fixture.journal.path).use { journal ->
                val resources = journal.recordings!!
                assertThat(resources.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code })
                    .containsExactly("RECORDING_SCRATCH_ARCHIVED_RECHECK_REQUIRED")
                assertThat(archiveBytes(resources)).containsExactly(first.toList(), second.toList()).inOrder()
                assertThat(fixture.remote.exports).isEqualTo(1)
                assertThat(fixture.remote.scratchRemoved).isEmpty()
                assertThat(fixture.remote.deleted).isEmpty()
                if (!missingMedia) {
                    assertThat(resources.recover(fixture.client, journal, Duration.ofSeconds(2)).map { it.code })
                        .containsExactly("RECORDING_MEDIA_CHANGED")
                    assertThat(archiveBytes(resources)).containsExactly(first.toList(), second.toList(), third.toList()).inOrder()
                }
                assertThat(resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isEmpty()
                assertThat(resources.isComplete()).isTrue()
                assertThat(fixture.remote.deleted).hasSize(if (missingMedia) 0 else 1)
            }
        }
    }

    @Test fun `only an already verified empty archive permits empty scratch recovery`(): Unit {
        for (emptyArchive in listOf(false, true)) {
            val root = Files.createDirectory(directory.resolve("empty-archive-$emptyArchive"))
            val fixture = pending(root)
            fixture.recording.ended(); fixture.recording.media(fixture.uri)
            val original = root.resolve("original.mp4")
            Files.write(original, if (emptyArchive) byteArrayOf() else "nonempty original".toByteArray())
            fixture.recording.archive(original)
            fixture.remote.apply { scratchPresent = true; video = byteArrayOf() }
            fixture.journal.seal()
            fixture.journal.use { journal ->
                val issues = fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))
                if (emptyArchive) {
                    assertThat(issues).isEmpty()
                    assertThat(fixture.resources.isComplete()).isTrue()
                    assertThat(fixture.remote.deleted).containsExactly(fixture.uri)
                } else {
                    assertThat(issues).isNotEmpty()
                    assertThat(fixture.remote.deleted).isEmpty()
                    assertThat(fixture.remote.scratchRemoved).isEmpty()
                }
                assertThat(entry(fixture.resources)["archives"].size()).isEqualTo(1)
            }
        }
    }

    @Test fun `a surviving native invocation blocks success and acknowledged absence before resource progress`(): Unit {
        for (phase in listOf("status", "receive", "absent-test", "absent-media")) {
            val root = Files.createDirectory(directory.resolve(phase))
            val fixture = pending(root)
            if (phase == "absent-media") {
                fixture.recording.ended(); fixture.recording.media(fixture.uri)
                val original = root.resolve("original.mp4")
                Files.write(original, fixture.remote.video); fixture.recording.archive(original)
                fixture.remote.present = false
            }
            fixture.journal.seal()
            val child = ProcessBuilder(HdcProcessTestChild.command("sleep", root.resolve("native.pid").toString())).start()
            try {
                var observed = false
                fixture.remote.afterReply = { command ->
                    val script = command.last()
                    val selected = when (phase) {
                        "status" -> script.contains("'ps'")
                        "receive" -> command.contains("file") && command.contains("recv")
                        "absent-test" -> script.contains("'test'")
                        else -> script.contains("mediatool") && script.contains("query")
                    }
                    if (selected && !observed) {
                        observed = true
                        fixture.journal.hostCall(allowSealed = true) { report ->
                            report(HarmonyOSRecoveryJournal.HostProcess(child.pid(), child.info().startInstant().orElseThrow().toString()))
                        }
                    }
                }
                fixture.journal.use { journal ->
                    assertThat(fixture.resources.recover(fixture.client, journal, Duration.ofSeconds(2))).isNotEmpty()
                    assertThat(observed).isTrue()
                    assertThat(child.isAlive).isTrue()
                    assertThat(journal.snapshot().issues.map { it.code }).contains("HOST_PROCESS_UNCONFIRMED")
                    val resource = entry(fixture.resources)
                    if (phase == "status") assertThat(resource["captureEnded"].booleanValue()).isFalse()
                    assertThat(resource["archives"].size()).isEqualTo(if (phase == "absent-media") 1 else 0)
                    assertThat(resource["mediaRemoved"].booleanValue()).isFalse()
                    assertThat(resource["clean"].booleanValue()).isFalse()
                    assertThat(fixture.remote.deleted).isEmpty()
                    val commands = fixture.remote.commands.size
                    val receives = fixture.remote.receives
                    val recovery = fixture.client.forResourceRecovery(journal)
                    assertThrows<IllegalStateException> { recovery.shell("device", listOf("ps", "-ef")) }
                    assertThrows<IllegalStateException> { recovery.receiveFile("device", fixture.recording.remotePath, root.resolve("blocked.mp4")) }
                    assertThat(fixture.remote.commands).hasSize(commands)
                    assertThat(fixture.remote.receives).isEqualTo(receives)
                }
            } finally {
                child.destroyForcibly()
                check(child.waitFor(5, TimeUnit.SECONDS)) { "The local test process did not terminate." }
                fixture.journal.close()
            }
        }
    }

    private data class Pending(val journal: HarmonyOSRecoveryJournal, val resources: HarmonyOSRecordingResourceJournal,
        val recording: HarmonyOSRecordingResourceJournal.Recording, val uri: String, val remote: MediaRemote) {
        val client get() = HdcClient("fake", remote)
    }
    private fun pending(root: Path): Pending {
        val journal = HarmonyOSRecoveryJournal.create(root.resolve("recovery-journal.json"), "device", UUID.randomUUID().toString())
        val resources = journal.enableRecordingResources()
        val recording = journal.allocateRecording()
        recording.starting(); recording.started(51)
        val uri = "file://media/Photo/123/VID_123/${recording.fileName}"
        return Pending(journal, resources, recording, uri, MediaRemote(uri))
    }
    private fun entry(resources: HarmonyOSRecordingResourceJournal) = mapper.readTree(resources.path.toFile())["resources"][0]
    private fun archiveBytes(resources: HarmonyOSRecordingResourceJournal): List<List<Byte>> = entry(resources)["archives"].map {
        Files.readAllBytes(resources.path.parent.resolve("recording-originals").resolve(it["name"].asText())).toList()
    }

    @Test fun `missing required recording fields and foreign ownership fail closed`(): Unit {
        create().use { it.enableRecordingResources() }
        val file = directory.resolve("recording-resources.json")
        val valid = Files.readString(file)
        for (invalid in listOf(valid.replace("\"schemaVersion\":1,", ""), valid.replace("\"deviceId\":\"device\"", "\"deviceId\":\"other\""))) {
            Files.writeString(file, invalid)
            assertThrows<Exception> { HarmonyOSRecoveryJournal.open(directory.resolve("recovery-journal.json")) }
        }
    }

    internal class MediaRemote(private val uri: String) : HdcCommandRunner {
        var active = false
        var present = true
        var loseDeleteReply = false
        var loseScratchReply = false
        var scratchPresent = false
        var scratchBytes: ByteArray? = null
        var scratchLink = false
        var scratchRegular = true
        var recorderProcesses = setOf(51)
        var malformedProcesses = false
        var activeAtCaptureRead: Int? = null
        var changedUriAtQuery: Int? = null
        var shortReceive = false
        var unstableStat = false
        var testExit: Int? = null
        var testStdout = ""
        var testStderr = ""
        var loseTestReply = false
        var loseStopReply = false
        val extraUris = mutableMapOf<String, String>()
        var afterReply: (List<String>) -> Unit = {}
        var receives = 0
        var captureReads = 0
        var queries = 0
        private var stats = 0
        var exports = 0
        var video = "raw".toByteArray()
        val deleted = mutableListOf<String>()
        val scratchRemoved = mutableListOf<String>()
        val commands = mutableListOf<String>()
        private val proofs = mutableMapOf<String, String>()
        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            if (command.contains("recv") && command.contains("file")) {
                receives++
                val bytes = scratchBytes ?: video
                Files.write(Path.of(command.last()), if (shortReceive) bytes.copyOf(bytes.size - 1) else bytes)
                afterReply(command)
                return HdcCommandResult(0, "", "")
            }
            val script = command.last()
            commands += script
            var unquoted = script
            while ("'\"'\"'" in unquoted) unquoted = unquoted.replace("'\"'\"'", "'")
            val removesScratch = unquoted.contains("'rm' '-f'")
            val isTest = unquoted.contains("'test'")
            val marker = Regex("MAESTRO_EXIT_[a-f0-9]+=").find(script)?.value
            if (marker == null && !isTest) {
                if (script.contains("cat ")) {
                    val proof = proofs.entries.single { script.contains("/${it.key}/done") }.value
                    return HdcCommandResult(0, proof, "")
                }
                val nonce = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").findAll(script).last().value
                return HdcCommandResult(0, nonce, "")
            }
            var status = 0
            val output = when {
                isTest -> {
                    val flag = Regex("'test' '(-[Lef])'").find(unquoted)!!.groupValues[1]
                    val exists = when (flag) { "-L" -> scratchLink; "-e" -> scratchPresent; else -> scratchPresent && scratchRegular }
                    status = testExit ?: if (exists) 0 else 1
                    testStdout
                }
                unquoted.contains("'ps' '-ef'") -> if (malformedProcesses) "unknown process table" else
                    "UID PID PPID C STIME TTY TIME CMD\n" + recorderProcesses.joinToString("\n") { "user $it 1 0 00:00 ? 0:00 com.huawei.hmos.screenrecorder" }
                script.contains("hidumper") -> {
                    captureReads++
                    "PlayerDistributedService\n--ScreenCaptureServer--:" +
                        (if (active || activeAtCaptureRead?.let { captureReads >= it } == true) "-----#0: pid = 51-----\n" else "") + "\n--NextSection--\n"
                }
                script.contains("mediatool") && script.contains("query") -> {
                    queries++
                    val fileName = Regex("maestro-recording-[0-9a-f]{32}\\.mp4").find(unquoted)!!.value
                    val baseUri = extraUris[fileName] ?: uri
                    val observedUri = if (changedUriAtQuery?.let { queries >= it } == true) baseUri.replace("/123/", "/999/") else baseUri
                    if (present) "find 1 result\nuri\n\"$observedUri\"" else { status = 255; "find 0 result" }
                }
                script.contains("mediatool") && script.contains("delete") -> {
                    deleted += Regex("file://media/Photo/[0-9]+/[A-Za-z0-9_.-]+/maestro-recording-[0-9a-f]{32}\\.mp4").find(unquoted)!!.value
                    present = false
                    "[SUCCESS] delete success."
                }
                removesScratch -> {
                    scratchRemoved += Regex("/data/local/tmp/maestro-session-[0-9a-f-]{36}/files/recording-[0-9a-f-]{36}\\.mp4").find(script)!!.value
                    scratchPresent = false
                    scratchBytes = null
                    ""
                }
                script.contains("mediatool") && script.contains("recv") -> {
                    val remote = Regex("/data/local/tmp/maestro-session-[0-9a-f-]{36}/files/recording-[0-9a-f-]{36}\\.mp4").find(script)!!.value
                    if (scratchPresent) { status = 255; "[FAIL] file has exist. file:$remote" }
                    else { scratchPresent = true; scratchBytes = video.copyOf(); exports++; "Table Name: Photos\n$remote" }
                }
                unquoted.contains("'stat' '-c'") -> { stats++; ((scratchBytes ?: video).size + if (unstableStat && stats % 2 == 0) 1 else 0).toString() }
                unquoted.contains("'aa' 'start'") -> "start ability successfully."
                else -> error("Unexpected recovery command: $command")
            }
            val proof = Regex("[0-9a-f-]{36}:[0-9a-f-]{36}:[0-9a-f-]{36}:[0-9a-f]{64}:[0-9a-f-]{36}").find(script)!!.value
            proofs[proof.split(':')[2]] = "$proof:$status"
            if (loseTestReply && isTest) { loseTestReply = false; throw HdcException("Scratch test response lost") }
            if (loseStopReply && unquoted.contains("'aa' 'start'")) { loseStopReply = false; throw HdcException("Stop response lost") }
            if (loseDeleteReply && script.contains("mediatool") && script.contains("delete")) {
                loseDeleteReply = false; throw HdcException("Delete response lost")
            }
            if (loseScratchReply && removesScratch) {
                loseScratchReply = false; throw HdcException("Scratch removal response lost")
            }
            val acknowledgement = Regex("MAESTRO_RECOVERY_[a-f0-9]+=").find(script)!!.value
            val innerStatus = if (marker == null) "" else "\n$marker$status\n"
            afterReply(command)
            return HdcCommandResult(0, "$output$innerStatus\n$acknowledgement$status\n", if (isTest) testStderr else "")
        }
    }
}
