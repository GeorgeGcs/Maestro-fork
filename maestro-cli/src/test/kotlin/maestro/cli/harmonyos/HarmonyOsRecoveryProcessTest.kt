package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.cli.CliError
import maestro.cli.harmonyos.HarmonyOsRecoveryProcessFixture.Scenario
import maestro.cli.session.HarmonyOsRecoverySupport
import maestro.cli.session.HarmonyOsSessionLease
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.harmonyos.hdc.HdcException
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Timeout(60)
class HarmonyOsRecoveryProcessTest {
    @Test fun `ended archived recording recovers in another JVM after its real owner process exits`() {
        scenario(Scenario.ENDED)
    }

    @Test fun `missing start acknowledgement remains blocked after process exit`() {
        scenario(Scenario.START_UNCONFIRMED)
    }

    @Test fun `capture still active remains blocked after process exit`() {
        scenario(Scenario.CAPTURE_ACTIVE)
    }

    @Test fun `missing registered archive remains blocked after process exit`() {
        scenario(Scenario.ARCHIVE_MISSING)
    }

    @Test fun `changed registered archive remains blocked after process exit`() {
        scenario(Scenario.ARCHIVE_TAMPERED)
    }

    private fun scenario(mode: Scenario) = TempFileHandler().use { temporary ->
        val root = temporary.createTempDirectory("harmonyos-process-recovery-").toPath().toAbsolutePath().normalize()
        val owner = UUID.randomUUID().toString()
        val held = start(root, owner, mode, "hold")
        val checkpoint: JsonNode
        val journalBefore: ByteArray
        val gateBefore: ByteArray
        try {
            checkpoint = awaitCheckpoint(held, root.resolve("checkpoint.json"))
            assertThat(checkpoint["pid"].longValue()).isEqualTo(held.pid)
            assertThat(checkpoint["startedAt"].asText()).isEqualTo(held.startedAt.toString())
            assertThat(checkpoint["ownerId"].asText()).isEqualTo(owner)
            assertThat(checkpoint["scenario"].asText()).isEqualTo(mode.name)
            val journalPath = Path.of(checkpoint["journalPath"].asText())
            journalBefore = Files.readAllBytes(journalPath)
            gateBefore = Files.readAllBytes(HarmonyOsRecoveryProcessFixture.gatePath(root))
            val journal = HarmonyOsRecoveryProcessFixture.mapper.readTree(journalBefore)
            assertThat(journal["sealed"].asBoolean()).isTrue()
            assertThat(journal["operations"].size()).isEqualTo(1)
            assertThat(journal["operations"].all { it["completed"].asBoolean() && !it["markerRemoved"].asBoolean() }).isTrue()
            assertThat(journal["hostCalls"].size()).isEqualTo(1)
            assertThat(journal["hostCalls"].all { it["returned"].asBoolean() && it["stopped"].asBoolean() }).isTrue()
            assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root)).isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.BUSY)
            assertThat(assertThrows<CliError> { beginAndFinish(root) }.cause).isInstanceOf(AgentProtocolException::class.java)
            // A distinct process also owns the journal OS lock, not only a same-JVM registry entry.
            assertThrows<HdcException> { HarmonyOSRecoveryJournal.open(journalPath).use { error("Owner lock was not held") } }
            assertThrows<CliError> { HarmonyOsRecoverySupport.recover(device, owner, HarmonyOsRecoveryProcessFixture.noHdc(), root) }
            assertThat(Files.readAllBytes(journalPath)).isEqualTo(journalBefore)
            assertThat(Files.readAllBytes(HarmonyOsRecoveryProcessFixture.gatePath(root))).isEqualTo(gateBefore)
            verifyCheckpointResource(checkpoint, mode)
            terminateOwned(held)
        } finally {
            terminateOwned(held)
        }

        assertThat(held.process.isAlive).isFalse()
        awaitDeviceLockRelease(root)
        assertThat(Files.readAllBytes(Path.of(checkpoint["journalPath"].asText()))).isEqualTo(journalBefore)
        assertThat(Files.readAllBytes(HarmonyOsRecoveryProcessFixture.gatePath(root))).isEqualTo(gateBefore)
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root)).isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
        val denied = assertThrows<AgentProtocolException> { beginAndFinish(root) }
        assertThat(denied.error.code).isEqualTo("DEVICE_RECOVERY_REQUIRED")
        assertThat(denied.error.details?.get("recoverySessionId")).isEqualTo(owner)
        val resultBefore = Files.readAllBytes(Path.of(checkpoint["resultPath"].asText()))
        assertThat(HarmonyOsRecoveryProcessFixture.digest(Path.of(checkpoint["resultPath"].asText())))
            .isEqualTo(checkpoint["resultSha256"].asText())

        val recovery = start(root, owner, mode, "recover")
        try {
            assertThat(recovery.process.waitFor(25, TimeUnit.SECONDS)).isTrue()
            assertWithMessage(Files.readString(recovery.log)).that(recovery.process.exitValue()).isEqualTo(0)
        } finally { terminateOwned(recovery) }
        val outcome = json(root.resolve("recovered.json"))
        assertThat(outcome["pid"].longValue()).isEqualTo(recovery.pid)
        assertThat(outcome["startedAt"].asText()).isEqualTo(recovery.startedAt.toString())
        assertThat(recovery.pid != held.pid || recovery.startedAt != held.startedAt).isTrue()
        assertThat(outcome["ownerId"].asText()).isEqualTo(owner)
        val receiptPath = Path.of(outcome["reportPath"].asText())
        assertThat(HarmonyOsRecoveryProcessFixture.digest(receiptPath)).isEqualTo(outcome["sha256"].asText())
        val receipt = json(receiptPath)
        assertThat(receipt["schemaVersion"].asInt()).isEqualTo(2)
        assertThat(receipt["deviceId"].asText()).isEqualTo(device)
        assertThat(receipt["sessionId"].asText()).isEqualTo(owner)
        assertThat(receipt["reason"].asText()).isEqualTo("recovery")
        assertThat(receipt["previousState"].asText()).isEqualTo("active")
        val snapshotPath = Path.of(outcome["snapshotPath"].asText())
        assertThat(HarmonyOsRecoveryProcessFixture.digest(snapshotPath)).isEqualTo(outcome["snapshotSha256"].asText())
        assertThat(receipt["recordingResources"]["sha256"].asText()).isEqualTo(outcome["snapshotSha256"].asText())
        val snapshot = json(snapshotPath)
        assertThat(snapshot["ownerId"].asText()).isEqualTo(owner)
        assertThat(snapshot["deviceId"].asText()).isEqualTo(device)
        assertThat(snapshot["resources"].size()).isEqualTo(1)
        val recoveredGate = json(root.resolve("gate-after-recovery.json"))
        assertThat(recoveredGate["ownerSessionId"].asText()).isEqualTo(owner)
        assertThat(recoveredGate["reportSha256"].asText()).isEqualTo(outcome["sha256"].asText())
        assertThat(recoveredGate["reportPath"].asText()).isEqualTo(receiptPath.toString())

        if (mode == Scenario.ENDED) {
            assertThat(outcome["complete"].asBoolean()).isTrue()
            assertThat(receipt["state"].asText()).isEqualTo("complete")
            assertThat(receipt["pendingOperations"].asInt()).isEqualTo(0)
            assertThat(receipt["temporaryFiles"].asInt()).isEqualTo(0)
            assertThat(receipt["issues"].size()).isEqualTo(0)
            assertThat(receipt["unresolvedRecording"].asBoolean()).isFalse()
            assertThat(snapshot["resources"].all { it["clean"].asBoolean() && it["mediaRemoved"].asBoolean() }).isTrue()
            assertThat(outcome["mediaDeletes"].asInt()).isEqualTo(1)
            assertThat(outcome["receiveCount"].asInt()).isEqualTo(1)
            assertThat(outcome["mediaPresent"].asBoolean()).isFalse()
            assertThat(outcome["scratchPresent"].asBoolean()).isFalse()
            assertThat(outcome["calls"].map(JsonNode::asText)).containsAtLeast("capture-state", "media-export", "receive", "media-delete", "journal-cleanup").inOrder()
            assertThat(outcome["calls"].map(JsonNode::asText))
                .containsAtLeast("scratch-test-L", "scratch-test-e", "media-export", "receive", "scratch-test-L", "scratch-test-f").inOrder()
            assertThat(recoveredGate["state"].asText()).isEqualTo("clean")
            val nextGate = json(HarmonyOsRecoveryProcessFixture.gatePath(root))
            assertThat(nextGate["ownerSessionId"].asText()).isEqualTo(outcome["nextOwner"].asText())
            assertThat(nextGate["ownerSessionId"].asText()).isNotEqualTo(owner)
            assertThat(nextGate["state"].asText()).isEqualTo("clean")
            val nextReceipt = Path.of(nextGate["reportPath"].asText())
            assertThat(HarmonyOsRecoveryProcessFixture.digest(nextReceipt)).isEqualTo(nextGate["reportSha256"].asText())
            assertThat(json(nextReceipt)["reason"].asText()).isEqualTo("session_close")
            assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root)).isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE)
        } else {
            assertThat(outcome["complete"].asBoolean()).isFalse()
            assertThat(receipt["state"].asText()).isEqualTo("unknown")
            assertThat(outcome["newOwnerRejectedCode"].asText()).isEqualTo("DEVICE_RECOVERY_REQUIRED")
            assertThat(outcome["nextOwner"].isNull).isTrue()
            assertThat(outcome["mediaDeletes"].asInt()).isEqualTo(0)
            assertThat(outcome["receiveCount"].asInt()).isEqualTo(0)
            assertThat(outcome["mediaPresent"].asBoolean()).isTrue()
            assertThat(outcome["scratchPresent"].asBoolean()).isFalse()
            assertThat(snapshot["resources"][0]["clean"].asBoolean()).isFalse()
            assertThat(recoveredGate["state"].asText()).isEqualTo("quarantined")
            val codes = receipt["issues"].map { it["code"].asText() }
            when (mode) {
                Scenario.START_UNCONFIRMED -> assertThat(codes).contains("RECORDING_CAPTURE_UNCONFIRMED")
                Scenario.CAPTURE_ACTIVE -> {
                    assertThat(codes).contains("RECORDING_CAPTURE_ACTIVE")
                    assertThat(codes).doesNotContain("RECORDING_CAPTURE_UNCONFIRMED")
                    // These are the recovery JVM's calls, not the original owner's checkpoint query.
                    assertThat(outcome["calls"].map(JsonNode::asText))
                        .containsExactly("recorder-processes", "capture-state").inOrder()
                    val retained = snapshot["resources"][0]
                    assertThat(retained["startAcknowledged"].asBoolean()).isTrue()
                    assertThat(retained["captureEnded"].asBoolean()).isFalse()
                    assertThat(retained["stopRequested"].asBoolean()).isFalse()
                    assertThat(retained["archives"].size()).isEqualTo(0)
                    assertThat(retained["mediaRemoved"].asBoolean()).isFalse()
                }
                else -> assertThat(codes).contains("RECORDING_ARCHIVE_UNCONFIRMED")
            }
            assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root)).isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
        }
        assertThat(Files.readAllBytes(Path.of(checkpoint["resultPath"].asText()))).isEqualTo(resultBefore)
        assertThat(HarmonyOsRecoveryProcessFixture.digest(Path.of(checkpoint["sourcePath"].asText())))
            .isEqualTo(checkpoint["sourceSha256"].asText())
        if (!checkpoint["archivePath"].isNull) {
            val archive = Path.of(checkpoint["archivePath"].asText())
            assertThat(Files.exists(archive)).isEqualTo(checkpoint["archiveExists"].asBoolean())
            if (Files.exists(archive)) assertThat(HarmonyOsRecoveryProcessFixture.digest(archive))
                .isEqualTo(checkpoint["archiveObservedSha256"].asText())
        }
        val result = json(Path.of(checkpoint["resultPath"].asText()))["data"]
        assertThat(result["status"].asText()).isEqualTo("failed")
        assertThat(result["error"]["code"].asText()).isEqualTo("ASSERTION_FAILED")
        assertThat(result["cleanup"]["state"].asText()).isEqualTo("unknown")
    }

    private fun verifyCheckpointResource(checkpoint: JsonNode, mode: Scenario) {
        val resource = json(Path.of(checkpoint["journalPath"].asText()).resolveSibling("recording-resources.json"))["resources"].single()
        assertThat(resource["id"].asText()).isEqualTo(checkpoint["resourceId"].asText())
        assertThat(resource["startRequested"].asBoolean()).isTrue()
        assertThat(resource["clean"].asBoolean()).isFalse()
        assertThat(resource["startAcknowledged"].asBoolean()).isEqualTo(mode != Scenario.START_UNCONFIRMED)
        assertThat(resource["captureEnded"].asBoolean()).isEqualTo(mode !in setOf(Scenario.START_UNCONFIRMED, Scenario.CAPTURE_ACTIVE))
        if (mode == Scenario.ENDED) {
            assertThat(resource["uri"].asText()).isEqualTo(checkpoint["uri"].asText())
            assertThat(resource["archives"].size()).isEqualTo(1)
            val archive = Path.of(checkpoint["archivePath"].asText())
            assertThat(Files.size(archive)).isEqualTo(resource["archiveSize"].longValue())
            assertThat(HarmonyOsRecoveryProcessFixture.digest(archive)).isEqualTo(resource["archiveSha256"].asText())
        }
    }

    private fun beginAndFinish(root: Path) = HarmonyOsSessionSupport.withDeviceLock(device, root) {
        HarmonyOsSessionLease.begin(device, UUID.randomUUID().toString(), root).finish(null, HarmonyOsRecoveryProcessFixture.noHdc())
    }

    private fun awaitCheckpoint(child: Child, checkpoint: Path): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!Files.exists(checkpoint) && child.process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertWithMessage(Files.readString(child.log)).that(Files.exists(checkpoint)).isTrue()
        assertThat(child.process.isAlive).isTrue()
        return json(checkpoint)
    }

    private fun awaitDeviceLockRelease(root: Path) {
        // Windows may report process termination before its file locks become observable as free.
        // Recovery is never started while the real OS lock is still busy.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (HarmonyOsSessionSupport.probeDeviceLock(device, root) == HarmonyOsSessionSupport.DeviceLockStatus.BUSY &&
            System.nanoTime() < deadline) Thread.sleep(20)
    }

    private data class Child(val process: Process, val pid: Long, val startedAt: Instant, val log: Path)

    private fun start(root: Path, owner: String, mode: Scenario, action: String): Child {
        val paths = linkedSetOf<String>()
        var loader: ClassLoader? = HarmonyOsRecoveryProcessFixture::class.java.classLoader
        while (loader != null) {
            (loader as? URLClassLoader)?.urLs?.filter { it.protocol == "file" }?.forEach { paths += Path.of(it.toURI()).toString() }
            loader = loader.parent
        }
        paths += System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotBlank)
        paths += Path.of(HarmonyOsRecoveryProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString()
        fun argument(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val args = root.resolve("$action-java.args")
        Files.writeString(args, listOf("-Dfile.encoding=UTF-8", "-cp", paths.joinToString(File.pathSeparator),
            HarmonyOsRecoveryProcessFixture::class.java.name, action, root.toString(), owner, mode.name)
            .joinToString("\n", transform = ::argument))
        val windows = System.getProperty("os.name").startsWith("Windows")
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java").toString()
        val log = root.resolve("$action-process.log")
        val process = ProcessBuilder(java, "@$args").redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val identity = process.info().startInstant()
        if (identity.isEmpty) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Own child could not be stopped after missing process identity" }
            error("Child process did not expose a start time")
        }
        return Child(process, process.pid(), identity.get(), log)
    }

    private fun terminateOwned(child: Child) {
        if (!child.process.isAlive) return
        check(child.process.pid() == child.pid && child.process.info().startInstant().orElse(null) == child.startedAt) {
            "Refusing to terminate a process whose identity changed"
        }
        child.process.destroyForcibly()
        check(child.process.waitFor(5, TimeUnit.SECONDS)) { "Own child did not exit within five seconds" }
    }

    private fun json(path: Path) = HarmonyOsRecoveryProcessFixture.json(path)
    private val device = HarmonyOsRecoveryProcessFixture.DEVICE
}
