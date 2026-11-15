package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.session.HarmonyOsRecoverySupport
import maestro.cli.session.HarmonyOsSessionLease
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-only JVM entry point. All HDC commands terminate in FakeHdcRunner; no executable is invoked. */
object HarmonyOsRecoveryProcessFixture {
    const val DEVICE = "process-recovery-fixture"
    val mapper = jacksonObjectMapper()
    private val video = "A retained byte-for-byte original; decoding is outside this recovery fixture.".toByteArray()

    enum class Scenario { ENDED, START_UNCONFIRMED, CAPTURE_ACTIVE, ARCHIVE_MISSING, ARCHIVE_TAMPERED }

    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 4)
        val root = Path.of(arguments[1]).toAbsolutePath().normalize()
        val owner = UUID.fromString(arguments[2]).toString()
        val scenario = Scenario.valueOf(arguments[3])
        when (arguments[0]) {
            "hold" -> hold(root, owner, scenario)
            "recover" -> recover(root, owner, scenario)
            else -> error("Unknown fixture mode")
        }
    }

    private fun hold(root: Path, owner: String, scenario: Scenario) {
        HarmonyOsSessionSupport.withDeviceLock(DEVICE, root) {
            val lease = HarmonyOsSessionLease.begin(DEVICE, owner, root, requiresRecordingCleanup = true)
            val resource = lease.journal.allocateRecording()
            val uri = "file://media/Photo/123/VID_123/${resource.fileName}"
            val source = root.resolve("original-bytes.mp4")
            Files.write(source, video)
            resource.starting()
            if (scenario != Scenario.START_UNCONFIRMED) resource.started(51)
            if (scenario != Scenario.START_UNCONFIRMED && scenario != Scenario.CAPTURE_ACTIVE) {
                resource.stopping()
                resource.ended()
                resource.media(uri)
                resource.archive(source)
            }
            val archive = resource.latestArchive()?.let { lease.journal.path.parent.resolve("recording-originals").resolve(it.name) }
            if (scenario == Scenario.ARCHIVE_MISSING) Files.delete(checkNotNull(archive))
            if (scenario == Scenario.ARCHIVE_TAMPERED) Files.write(checkNotNull(archive), "changed retained bytes".toByteArray())

            val remote = FakeHdcRunner(uri, resource.remotePath, source, scenario == Scenario.CAPTURE_ACTIVE)
            // A real tracked finite call persists its acknowledgement and returned/stopped host call.
            HdcHarmonyOSDevice(DEVICE, HdcClient("fixture-hdc", remote).withRecoveryJournal(lease.journal))
                .execute(listOf("hidumper", "-s", "3002", "-a", "-h"), Duration.ofSeconds(2))
            lease.journal.seal()
            val attempt = HarmonyOsAgentAttemptStore(root.resolve("attempts")).register(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, owner, DEVICE, "action",
                mapper.readTree("{\"type\":\"back\"}"),
            )
            attempt.markRunning()
            attempt.setCleanup("unknown", listOf(AgentError("environment", "DEVICE_RECOVERY_REQUIRED",
                "The retained recording needs recovery.", mapOf("recoverySessionId" to owner))))
            attempt.finishWithError(AgentError("assertion", "ASSERTION_FAILED", "Fixture assertion remains failed."), "failed")
            val process = ProcessHandle.current()
            write(root.resolve("checkpoint.json"), mapOf(
                "schemaVersion" to 1, "pid" to process.pid(),
                "startedAt" to process.info().startInstant().orElseThrow().toString(),
                "scenario" to scenario.name, "ownerId" to owner, "deviceId" to DEVICE,
                "journalPath" to lease.journal.path.toString(), "resourceId" to resource.id,
                "uri" to uri, "remotePath" to resource.remotePath,
                "sourcePath" to source.toString(), "sourceSha256" to digest(source),
                "archivePath" to archive?.toString(), "archiveExists" to (archive != null && Files.exists(archive)),
                "archiveObservedSha256" to archive?.takeIf { Files.exists(it) }?.let(::digest),
                "resultPath" to attempt.directory.resolve("result.json").toString(),
                "resultSha256" to digest(attempt.directory.resolve("result.json")),
                "runId" to attempt.runId, "attemptId" to attempt.attemptId,
            ))
            // Deliberately no lease.finish/journal.close: the parent must prove OS process death.
            check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Parent did not terminate its fixture within the deadline" }
        }
    }

    private fun recover(root: Path, owner: String, scenario: Scenario) {
        val checkpoint = json(root.resolve("checkpoint.json"))
        val remote = FakeHdcRunner(checkpoint["uri"].asText(), checkpoint["remotePath"].asText(),
            Path.of(checkpoint["sourcePath"].asText()), scenario == Scenario.CAPTURE_ACTIVE)
        val hdc = HdcClient("fixture-hdc", remote)
        val outcome = HarmonyOsRecoverySupport.recover(DEVICE, owner, hdc, root)
        val receiptBytes = Files.readAllBytes(outcome.reportPath)
        check(digest(outcome.reportPath) == outcome.sha256)
        val receipt = mapper.readTree(receiptBytes)
        check(receipt["schemaVersion"].asInt() == 2 && receipt["sessionId"].asText() == owner)
        val snapshot = Path.of(receipt["recordingResources"]["path"].asText())
        check(digest(snapshot) == receipt["recordingResources"]["sha256"].asText())
        write(root.resolve("gate-after-recovery.json"), json(gatePath(root)))
        var nextOwner: String? = null
        var rejectedCode: String? = null
        if (outcome.complete) {
            check(scenario == Scenario.ENDED)
            val repeated = HarmonyOsRecoverySupport.recover(DEVICE, owner, noHdc(), root)
            check(repeated.reportPath == outcome.reportPath && repeated.sha256 == outcome.sha256)
            val next = UUID.randomUUID().toString()
            nextOwner = next
            HarmonyOsSessionSupport.withDeviceLock(DEVICE, root) {
                HarmonyOsSessionLease.begin(DEVICE, next, root).finish(null, noHdc())
            }
            check(HarmonyOsSessionSupport.probeDeviceLock(DEVICE, root) == HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE)
        } else {
            try {
                HarmonyOsSessionSupport.withDeviceLock(DEVICE, root) {
                    HarmonyOsSessionLease.begin(DEVICE, UUID.randomUUID().toString(), root)
                }
                error("Unknown recovery unexpectedly permitted a new owner")
            } catch (error: AgentProtocolException) {
                rejectedCode = error.error.code
                check(rejectedCode == "DEVICE_RECOVERY_REQUIRED")
            }
        }
        check(Files.readAllBytes(outcome.reportPath).contentEquals(receiptBytes))
        write(root.resolve("recovered.json"), mapOf(
            "schemaVersion" to 1, "pid" to ProcessHandle.current().pid(),
            "startedAt" to ProcessHandle.current().info().startInstant().orElseThrow().toString(), "ownerId" to owner,
            "complete" to outcome.complete, "reportPath" to outcome.reportPath.toString(), "sha256" to outcome.sha256,
            "snapshotPath" to snapshot.toString(), "snapshotSha256" to digest(snapshot),
            "nextOwner" to nextOwner, "newOwnerRejectedCode" to rejectedCode,
            "mediaPresent" to remote.present, "mediaDeletes" to remote.deletes, "scratchPresent" to remote.scratchPresent,
            "receiveCount" to remote.receives, "calls" to remote.calls,
        ))
    }

    fun gatePath(root: Path): Path = root.resolve("${HarmonyOsSessionSupport.lockName(DEVICE)}.owner.json")
    fun json(path: Path): JsonNode = mapper.readTree(path.toFile())
    fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }
    fun noHdc(): HdcClient = HdcClient("fixture-no-hdc", HdcCommandRunner { _, _ -> error("No device command is permitted here") })

    private fun write(path: Path, value: Any) {
        val pending = path.resolveSibling("${path.fileName}.pending")
        check(Files.notExists(path) && Files.notExists(pending))
        FileOutputStream(pending.toFile()).use { output -> output.write(mapper.writeValueAsBytes(value)); output.fd.sync() }
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE)
    }

    private class FakeHdcRunner(
        private val uri: String,
        private val scratch: String,
        private val source: Path,
        private val active: Boolean,
    ) : HdcCommandRunner {
        var present = true
        var scratchPresent = false
        var deletes = 0
        var receives = 0
        val calls = mutableListOf<String>()

        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            check(command.take(3) == listOf("fixture-hdc", "-t", DEVICE))
            check(timeout > Duration.ZERO)
            if (command.drop(3).take(2) == listOf("file", "recv")) {
                check(command.size == 7 && command[5] == scratch && scratchPresent)
                calls += "receive"
                receives++
                Files.write(Path.of(command.last()), Files.readAllBytes(source))
                return HdcCommandResult(0, "", "")
            }
            check(command.size == 5 && command[3] == "shell")
            val script = command.last()
            var unquoted = script
            while ("'\"'\"'" in unquoted) unquoted = unquoted.replace("'\"'\"'", "'")
            if (unquoted.contains("'aa'")) {
                calls += "toggle-attempt"
                error("Recovery must not start or stop a recorder")
            }
            val marker = Regex("MAESTRO_EXIT_[a-f0-9]+=").find(script)?.value
            if (marker == null) {
                val test = Regex("'test' '(-[Lef])' '([^']+)'; __maestro_recovery_status=").find(unquoted)
                if (test != null) {
                    check(test.groupValues[2] == scratch)
                    val flag = test.groupValues[1]
                    calls += "scratch-test${flag}"
                    val exists = when (flag) {
                        "-L" -> false
                        "-e", "-f" -> scratchPresent
                        else -> error("Unexpected scratch test flag")
                    }
                    val acknowledgement = Regex("MAESTRO_RECOVERY_[a-f0-9]+=").find(script)?.value
                        ?: error("Scratch checks require a tracked completion proof")
                    return HdcCommandResult(0, "\n$acknowledgement${if (exists) 0 else 1}\n", "")
                }
                check(unquoted.contains("rmdir") && unquoted.contains(scratch.substringBefore("/files/")))
                calls += "journal-cleanup"
                // Cleanup batches send the owned root and basenames separately. Model
                // the actual guarded removal, rather than acknowledging an unremoved file.
                val batches = Regex("__maestro_cleanup_root='([^']+)'; __maestro_cleanup_names='([^']+)';")
                    .findAll(unquoted)
                for (batch in batches) {
                    check(batch.groupValues[1] == scratch.substringBeforeLast('/'))
                    check(batch.groupValues[2].split(' ') == listOf(scratch.substringAfterLast('/')))
                    check(unquoted.contains("__maestro_cleanup_file=\"\$__maestro_cleanup_root/\$__maestro_cleanup_name\"; " +
                        "[ ! -L \"\$__maestro_cleanup_file\" ] && rm -f \"\$__maestro_cleanup_file\" && " +
                        "[ ! -e \"\$__maestro_cleanup_file\" ] || exit 121; done; "))
                    scratchPresent = false
                }
                if (unquoted.contains("rmdir '${scratch.substringBeforeLast('/')}'")) {
                    check(!scratchPresent) { "The scratch directory cannot be removed while its file remains" }
                }
                val nonce = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    .findAll(script).last().value
                return HdcCommandResult(0, nonce, "")
            }
            var status = 0
            val output = when {
                unquoted.contains("'ps' '-ef'") -> {
                    calls += "recorder-processes"
                    "UID PID PPID C STIME TTY TIME CMD\n" +
                        "1000 51 1 0 00:00 ? 00:00:00 com.huawei.hmos.screenrecorder\n"
                }
                unquoted.contains("'hidumper' '-s' '3002' '-a' '-h'") -> {
                    calls += "capture-state"
                    "PlayerDistributedService\n--ScreenCaptureServer--:\n" +
                        (if (active) "-----#0: pid = 51-----\n" else "") + "--NextSection--\n"
                }
                unquoted.contains("'mediatool' 'query'") -> {
                    check(unquoted.contains(uri.substringAfterLast('/')))
                    calls += "media-query"
                    if (present) "find 1 result\nuri\n\"$uri\"" else { status = 255; "find 0 result" }
                }
                unquoted.contains("'mediatool' 'delete'") -> {
                    check(unquoted.contains(uri) && present && !active)
                    calls += "media-delete"; deletes++; present = false
                    "[SUCCESS] delete success."
                }
                unquoted.contains("'mediatool' 'recv'") -> {
                    check(unquoted.contains("'mediatool' 'recv' '$uri' '$scratch'") && present && !scratchPresent)
                    calls += "media-export"
                    scratchPresent = true
                    "Table Name: Photos\n$scratch"
                }
                unquoted.contains("'rm' '-f'") -> {
                    check(unquoted.contains("'rm' '-f' '$scratch'"))
                    calls += "scratch-remove"; scratchPresent = false; ""
                }
                unquoted.contains("'stat' '-c' '%s'") -> {
                    check(unquoted.contains("'stat' '-c' '%s' '$scratch'") && scratchPresent)
                    calls += "scratch-size"; Files.size(source).toString()
                }
                else -> {
                    calls += "unexpected-command"
                    error("Unexpected fake command; no HDC fallback is available")
                }
            }
            val ack = Regex("MAESTRO_RECOVERY_[a-f0-9]+=").find(script)?.value
                ?: error("Expected a tracked command")
            return HdcCommandResult(0, "$output\n$marker$status\n\n$ack$status\n", "")
        }
    }
}
