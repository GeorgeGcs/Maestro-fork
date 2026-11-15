package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.cli.session.HarmonyOsRecoverySupport
import maestro.cli.session.HarmonyOsSessionLease
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.time.Duration

class HarmonyOsSessionLeaseTest {
    private val mapper = jacksonObjectMapper()
    private val device = "fixture-device"
    private val noHdc = HdcClient("test-hdc", HdcCommandRunner { _, _ ->
        error("An empty journal must not contact HDC")
    })

    @Test fun `large completed session has a shared close budget and a lost cleanup acknowledgement still blocks reuse`() {
        for (loseAcknowledgement in listOf(false, true)) directory { root ->
            val owner = uuid()
            val budgets = mutableListOf<Duration>()
            val hdc = HdcClient("test-hdc", HdcCommandRunner { command, timeout ->
                val script = command.last()
                val ack = Regex("MAESTRO_RECOVERY_[a-f0-9]+=").find(script)?.value
                if (ack != null) HdcCommandResult(0, "\n${ack}0\n", "")
                else {
                    budgets += timeout
                    // Model a cleanup transport that needs more than the old ten-second budget.
                    if (timeout < Duration.ofSeconds(12)) throw maestro.harmonyos.hdc.HdcTimeoutException(timeout)
                    val nonce = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").findAll(script).last().value
                    HdcCommandResult(0, if (loseAcknowledgement) "" else nonce, "")
                }
            })
            HarmonyOsSessionSupport.withDeviceLock(device, root) {
                val lease = HarmonyOsSessionLease.begin(device, owner, root)
                val tracked = hdc.withRecoveryJournal(lease.journal)
                repeat(148) { tracked.shell(device, listOf("uitest", "dumpLayout")) }
                if (loseAcknowledgement) assertThrows<AgentProtocolException> { lease.finish(null, hdc) }
                else lease.finish(null, hdc)
            }
            assertThat(budgets).isNotEmpty()
            assertThat(budgets.all { it > Duration.ZERO && it <= Duration.ofSeconds(30) }).isTrue()
            assertThat(budgets.zipWithNext().all { (before, after) -> after <= before }).isTrue()
            assertThat(gate(root)["state"].asText()).isEqualTo(if (loseAcknowledgement) "quarantined" else "clean")
            val journal = mapper.readTree(Path.of(gate(root)["journalPath"].asText()).toFile())
            assertThat(journal["operations"].all { it["completed"].asBoolean() }).isTrue()
            if (loseAcknowledgement) {
                assertThat(journal["operations"].any { !it["markerRemoved"].asBoolean() }).isTrue()
                assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
                    .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
            } else {
                assertThat(budgets.size).isGreaterThan(1)
                assertThat(journal["operations"].all { it["markerRemoved"].asBoolean() }).isTrue()
                normalClose(root, uuid())
            }
        }
    }

    @Test fun `normal empty session creates a verified receipt and allows a new owner`() = directory { root ->
        val owner = uuid()
        normalClose(root, owner)
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE)
        val receipt = HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        assertThat(receipt.complete).isTrue()
        assertThat(receipt.report["operations"]).isEqualTo(0)
        assertThat(receipt.report["reason"]).isEqualTo("session_close")
        assertThat(receipt.reportPath.isAbsolute).isTrue()
        assertThat(receipt.sha256).matches("[0-9a-f]{64}")
        normalClose(root, uuid())
        assertThrows<AgentProtocolException> {
            HarmonyOsSessionSupport.withDeviceLock(device, root) { HarmonyOsSessionLease.begin(device, owner, root) }
        }.also { assertThat(it.error.code).isEqualTo("SESSION_ALREADY_EXISTS") }
    }

    @Test fun `reported unknown cleanup stays blocked until explicit recovery and preserves its receipt`() = directory { root ->
        val owner = uuid()
        quarantine(root, owner)
        val unknownPath = Path.of(gate(root)["reportPath"].asText())
        val unknownBytes = Files.readAllBytes(unknownPath)
        assertThat(mapper.readTree(unknownBytes)["state"].asText()).isEqualTo("unknown")
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
        val denied = assertThrows<AgentProtocolException> {
            HarmonyOsSessionSupport.withSession(device, noHdc, lockDirectory = root) { error("Must not connect") }
        }
        assertThat(denied.error.code).isEqualTo("DEVICE_RECOVERY_REQUIRED")
        assertThat(denied.error.details?.get("recoverySessionId")).isEqualTo(owner)
        val recovered = HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        assertThat(recovered.complete).isTrue()
        assertThat(recovered.report["reason"]).isEqualTo("recovery")
        assertThat(recovered.report["previousReportPath"]).isEqualTo(unknownPath.toString())
        assertThat(Files.readAllBytes(unknownPath)).isEqualTo(unknownBytes)
        val repeated = HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        assertThat(repeated.reportPath).isEqualTo(recovered.reportPath)
        assertThat(repeated.sha256).isEqualTo(recovered.sha256)
        assertThat(gate(root)["requiresRecovery"].asBoolean()).isFalse()
        normalClose(root, uuid())
    }

    @Test fun `a deadline exhausted between recovery phases retains a receipt and blocks handoff`() = directory { root ->
        val owner = uuid()
        quarantine(root, owner)
        val originalPath = Path.of(gate(root)["reportPath"].asText())
        val original = Files.readAllBytes(originalPath)
        val timedOut = HarmonyOsRecoverySupport.recover(device, owner, noHdc, root, Duration.ofNanos(1))
        assertThat(timedOut.complete).isFalse()
        assertThat(timedOut.report["state"]).isEqualTo("unknown")
        assertThat(timedOut.report["issues"].toString()).contains("RECOVERY_QUERY_FAILED")
        assertThat(Files.readAllBytes(originalPath)).isEqualTo(original)
        assertThat(gate(root)["reportPath"].asText()).isEqualTo(timedOut.reportPath.toString())
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
        val unknown = Files.readAllBytes(timedOut.reportPath)
        assertThat(HarmonyOsRecoverySupport.recover(device, owner, noHdc, root).complete).isTrue()
        assertThat(Files.readAllBytes(timedOut.reportPath)).isEqualTo(unknown)
    }

    @Test fun `uncertainty flag is durable before the session block returns`() = directory { root ->
        val owner = uuid()
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val lease = HarmonyOsSessionLease.begin(device, owner, root)
            HarmonyOsRecoverySupport.noteUncertainCleanup(owner, "Cancellation acknowledgement was not received")
            assertThat(gate(root)["requiresRecovery"].asBoolean()).isTrue()
            assertThat(gate(root)["uncertainReason"].asText()).contains("Cancellation acknowledgement")
            assertThat(assertThrows<AgentProtocolException> { lease.finish(null, noHdc) }.error.code)
                .isEqualTo("DEVICE_RECOVERY_REQUIRED")
        }
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsRecoverySupport.noteUncertainCleanup(owner)
        }.error.code).isEqualTo("SESSION_LEASE_UNAVAILABLE")
    }

    @Test fun `driver close failure cannot be cleared by an empty command journal`() = directory { root ->
        val owner = uuid()
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val lease = HarmonyOsSessionLease.begin(device, owner, root)
            assertThrows<AgentProtocolException> { lease.finish(IllegalStateException("recording still active"), noHdc) }
        }
        val recovered = HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        assertThat(recovered.complete).isFalse()
        @Suppress("UNCHECKED_CAST") val issues = recovered.report["issues"] as List<Map<String, String>>
        assertThat(issues.map { it["code"] }).contains("DRIVER_CLEANUP_FAILED")
        assertThat(gate(root)["state"].asText()).isEqualTo("quarantined")
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
    }

    @Test fun `an active durable gate outlives its operating system lock`() = directory { root ->
        val owner = uuid()
        quarantine(root, owner)
        val abandoned = gate(root).put("state", "active")
        Files.writeString(gatePath(root), mapper.writeValueAsString(abandoned))
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsSessionSupport.withSession(device, noHdc, lockDirectory = root) { error("Must not connect") }
        }.error.code).isEqualTo("DEVICE_RECOVERY_REQUIRED")
        assertThat(HarmonyOsRecoverySupport.recover(device, owner, noHdc, root).complete).isTrue()
    }

    @Test fun `recording capability is gated before opening and only a successful close clears it`() = directory { root ->
        val owner = uuid()
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val lease = HarmonyOsSessionLease.begin(device, owner, root, requiresRecordingCleanup = true)
            assertThat(gate(root)["unresolvedRecording"].asBoolean()).isTrue()
            lease.finish(null, noHdc)
        }
        assertThat(gate(root)["unresolvedRecording"].asBoolean()).isFalse()
        assertThat(HarmonyOsRecoverySupport.recover(device, owner, noHdc, root).complete).isTrue()

        val abandonedOwner = uuid()
        quarantine(root, abandonedOwner)
        // Durable state left by a host exit before the recording owner's close could acknowledge completion.
        val abandoned = gate(root).put("state", "active").put("unresolvedRecording", true)
        Files.writeString(gatePath(root), mapper.writeValueAsString(abandoned))
        val recovered = HarmonyOsRecoverySupport.recover(device, abandonedOwner, noHdc, root)
        assertThat(recovered.complete).isFalse()
        @Suppress("UNCHECKED_CAST") val issues = recovered.report["issues"] as List<Map<String, String>>
        assertThat(issues.map { it["code"] }).contains("RECORDING_CLEANUP_UNCONFIRMED")
        assertThat(gate(root)["unresolvedRecording"].asBoolean()).isTrue()
    }

    @Test fun `a gate without explicit recording ownership metadata is rejected`() = directory { root ->
        val owner = uuid()
        quarantine(root, owner)
        val incomplete = gate(root).also { it.remove("unresolvedRecording") }
        Files.writeString(gatePath(root), mapper.writeValueAsString(incomplete))
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
    }

    @Test fun `retained ended recording recovers through the existing gate and keeps immutable evidence`() = directory { root ->
        val owner = uuid()
        var uri = ""
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val lease = HarmonyOsSessionLease.begin(device, owner, root, requiresRecordingCleanup = true)
            val recording = lease.journal.allocateRecording()
            recording.starting(); recording.started(51); recording.stopping(); recording.ended()
            uri = "file://media/Photo/123/VID_123/${recording.fileName}"
            recording.media(uri)
            val raw = lease.journal.path.parent.resolve("test-original.mp4")
            Files.writeString(raw, "raw original may fail decoding but is still preserved")
            recording.archive(raw)
            assertThat(assertThrows<AgentProtocolException> { lease.finish(null, noHdc) }.error.code)
                .isEqualTo("DEVICE_RECOVERY_REQUIRED")
        }
        val beforePath = Path.of(gate(root)["reportPath"].asText())
        val before = Files.readAllBytes(beforePath)
        val remote = RecordingRemote(uri, "raw original may fail decoding but is still preserved".toByteArray())
        val outcome = HarmonyOsRecoverySupport.recover(device, owner, HdcClient("fake", remote), root)
        assertThat(outcome.complete).isTrue()
        assertThat(outcome.report["unresolvedRecording"]).isEqualTo(false)
        assertThat(outcome.report["recordingResources"]).isNotNull()
        assertThat(Files.readAllBytes(beforePath)).isEqualTo(before)
        assertThat(remote.deletes).isEqualTo(1)
        assertThat(gate(root)["unresolvedRecording"].booleanValue()).isFalse()
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root)).isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE)
        assertThat(HarmonyOsRecoverySupport.recover(device, owner, noHdc, root).sha256).isEqualTo(outcome.sha256)
        normalClose(root, uuid())
    }

    @Test fun `only a fingerprinted recording close failure can be resolved by its resource evidence`() = directory { root ->
        for (unrelated in listOf(false, true)) {
            val folder = Files.createDirectory(root.resolve(if (unrelated) "mixed" else "recording"))
            val owner = uuid()
            var uri = ""
            HarmonyOsSessionSupport.withDeviceLock(device, folder) {
                val lease = HarmonyOsSessionLease.begin(device, owner, folder, requiresRecordingCleanup = true)
                val resource = lease.journal.allocateRecording()
                resource.starting(); resource.started(51); resource.stopping(); resource.ended()
                uri = "file://media/Photo/123/VID_123/${resource.fileName}"
                resource.media(uri)
                val raw = lease.journal.path.parent.resolve("raw.mp4")
                Files.writeString(raw, "raw"); resource.archive(raw)
                val error = IllegalStateException("owned media delete failed")
                resource.failed(error)
                // A duration-limit error is attached by expire after close has already recorded its first failure.
                error.addSuppressed(IllegalStateException("recording duration limit"))
                resource.failed(error)
                if (unrelated) error.addSuppressed(IllegalStateException("unrelated close error"))
                assertThrows<AgentProtocolException> { lease.finish(error, noHdc) }
            }
            val recovered = HarmonyOsRecoverySupport.recover(device, owner, HdcClient("fake", RecordingRemote(uri)), folder)
            assertThat(recovered.complete).isEqualTo(!unrelated)
            if (unrelated) {
                @Suppress("UNCHECKED_CAST") val issues = recovered.report["issues"] as List<Map<String, String>>
                assertThat(issues.map { it["code"] }).contains("DRIVER_CLEANUP_FAILED")
            } else {
                assertThat(gate(folder)["closeFailure"].isNull).isTrue()
                assertThat(HarmonyOsRecoverySupport.recover(device, owner, noHdc, folder).complete).isTrue()
            }
        }
    }

    @Test fun `recording recovery after the owner exits uses durable evidence instead of process memory`() = directory { root ->
        val owner = uuid()
        var uri = ""
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val lease = HarmonyOsSessionLease.begin(device, owner, root, requiresRecordingCleanup = true)
            val resource = lease.journal.allocateRecording()
            resource.starting(); resource.started(51); resource.stopping(); resource.ended()
            uri = "file://media/Photo/123/VID_123/${resource.fileName}"
            resource.media(uri)
            val raw = lease.journal.path.parent.resolve("raw.mp4")
            Files.writeString(raw, "original"); resource.archive(raw)
            // Model process disappearance by releasing the journal lock without finishing the lease.
            lease.journal.close()
        }
        assertThat(gate(root)["state"].asText()).isEqualTo("active")
        val recovered = HarmonyOsRecoverySupport.recover(device, owner, HdcClient("fake", RecordingRemote(uri, "original".toByteArray())), root)
        assertThat(recovered.complete).isTrue()
        assertThat(HarmonyOsRecoverySupport.recover(device, owner, noHdc, root).complete).isTrue()
    }

    @Test fun `fresh end observation preserves a first original before a later recovery can hand off`() = directory { root ->
        val owner = uuid()
        var uri = ""
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val lease = HarmonyOsSessionLease.begin(device, owner, root, requiresRecordingCleanup = true)
            val resource = lease.journal.allocateRecording()
            resource.starting(); resource.started(51); resource.stopping()
            uri = "file://media/Photo/123/VID_123/${resource.fileName}"
            val error = IllegalStateException("Stop reply was lost; original close remains failed")
            resource.failed(error)
            assertThrows<AgentProtocolException> { lease.finish(error, noHdc) }
        }
        val originalReceipt = Path.of(gate(root)["reportPath"].asText())
        val originalBytes = Files.readAllBytes(originalReceipt)
        val remote = RecordingRemote(uri, "first preserved original".toByteArray())
        val hdc = HdcClient("fake", remote)
        val first = HarmonyOsRecoverySupport.recover(device, owner, hdc, root)
        assertThat(first.complete).isFalse()
        assertThat(first.report["issues"].toString()).contains("RECORDING_ARCHIVED_RECHECK_REQUIRED")
        assertThat(remote.exports).isEqualTo(1)
        assertThat(remote.deletes).isEqualTo(0)
        assertThat(gate(root)["closeFailure"]["code"].asText()).isEqualTo("RECORDING_RESOURCE_CLEANUP_FAILED")
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
        val firstBytes = Files.readAllBytes(first.reportPath)
        val snapshot = mapper.readTree(first.reportPath.toFile())["recordingResources"]
        val recorded = mapper.readTree(Path.of(snapshot["path"].asText()).toFile())["resources"][0]
        assertThat(recorded["captureEnded"].booleanValue()).isTrue()
        assertThat(recorded["archives"].size()).isEqualTo(1)
        assertThat(recorded["clean"].booleanValue()).isFalse()

        val second = HarmonyOsRecoverySupport.recover(device, owner, hdc, root)
        assertThat(second.complete).isTrue()
        assertThat(remote.exports).isEqualTo(2)
        assertThat(remote.deletes).isEqualTo(1)
        assertThat(gate(root)["closeFailure"].isNull).isTrue()
        assertThat(Files.readAllBytes(originalReceipt)).isEqualTo(originalBytes)
        assertThat(Files.readAllBytes(first.reportPath)).isEqualTo(firstBytes)
        normalClose(root, uuid())
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE)
    }

    @Test fun `fresh media recovery cannot resolve an unrelated close error`() = directory { root ->
        val owner = uuid()
        var uri = ""
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val lease = HarmonyOsSessionLease.begin(device, owner, root, requiresRecordingCleanup = true)
            val resource = lease.journal.allocateRecording()
            resource.starting(); resource.started(51)
            uri = "file://media/Photo/123/VID_123/${resource.fileName}"
            val error = IllegalStateException("Recording close failed")
            resource.failed(error)
            error.addSuppressed(IllegalStateException("Unrelated driver close failed"))
            assertThrows<AgentProtocolException> { lease.finish(error, noHdc) }
        }
        val remote = RecordingRemote(uri)
        val hdc = HdcClient("fake", remote)
        assertThat(HarmonyOsRecoverySupport.recover(device, owner, hdc, root).complete).isFalse()
        val second = HarmonyOsRecoverySupport.recover(device, owner, hdc, root)
        assertThat(second.complete).isFalse()
        assertThat(second.report["issues"].toString()).contains("DRIVER_CLEANUP_FAILED")
        assertThat(remote.deletes).isEqualTo(1)
        assertThat(gate(root)["closeFailure"].isNull).isFalse()
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED)
    }

    @Test fun `recovery cannot overlap a live owner or a CLI holding the same device lock`() = directory { root ->
        val owner = uuid()
        quarantine(root, owner)
        val original = Files.readAllBytes(gatePath(root))
        HarmonyOsSessionSupport.withDeviceLock(device, root) {
            val denied = assertThrows<CliError> { HarmonyOsRecoverySupport.recover(device, owner, noHdc, root) }
            assertThat(HarmonyOsAgentProtocol.error(denied).code).isEqualTo("DEVICE_BUSY")
            // Recovery of one device must not serialize an unrelated device behind it.
            HarmonyOsSessionSupport.withDeviceLock("other-device", root) {}
        }
        assertThat(Files.readAllBytes(gatePath(root))).isEqualTo(original)
    }

    @Test fun `wrong owner cannot recover the current gate`() = directory { root ->
        val owner = uuid()
        quarantine(root, owner)
        val original = Files.readAllBytes(gatePath(root))
        val denied = assertThrows<AgentProtocolException> { HarmonyOsRecoverySupport.recover(device, uuid(), noHdc, root) }
        assertThat(denied.error.code).isEqualTo("RECOVERY_SESSION_MISMATCH")
        assertThat(denied.error.details?.get("recoverySessionId")).isEqualTo(owner)
        assertThat(Files.readAllBytes(gatePath(root))).isEqualTo(original)
    }

    @Test fun `missing or corrupt journal leaves the gate blocked without creating a recovery receipt`() = directory { root ->
        for (missing in listOf(true, false)) {
            val fixture = Files.createDirectory(root.resolve(if (missing) "missing" else "corrupt"))
            val owner = uuid()
            quarantine(fixture, owner)
            val journal = Path.of(gate(fixture)["journalPath"].asText())
            val original = Files.readAllBytes(gatePath(fixture))
            val receipts = journal.parent.resolve("recoveries")
            val count = Files.list(receipts).use { it.count() }
            if (missing) Files.delete(journal) else Files.writeString(journal, "{}")
            assertThat(assertThrows<AgentProtocolException> {
                HarmonyOsRecoverySupport.recover(device, owner, noHdc, fixture)
            }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
            assertThat(Files.readAllBytes(gatePath(fixture))).isEqualTo(original)
            assertThat(Files.list(receipts).use { it.count() }).isEqualTo(count)
        }
    }

    @Test fun `missing gate with prior session records cannot be treated as a new device`() = directory { root ->
        val owner = uuid()
        normalClose(root, owner)
        Files.delete(gatePath(root))
        assertThat(HarmonyOsSessionSupport.probeDeviceLock(device, root))
            .isEqualTo(HarmonyOsSessionSupport.DeviceLockStatus.UNKNOWN)
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsSessionSupport.withSession(device, noHdc, lockDirectory = root) { error("Must not connect") }
        }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
    }

    @Test fun `tampered complete receipt prevents normal entry and idempotent recovery`() = directory { root ->
        val owner = uuid()
        normalClose(root, owner)
        Files.writeString(Path.of(gate(root)["reportPath"].asText()), "{}")
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsSessionSupport.withSession(device, noHdc, lockDirectory = root) { error("Must not connect") }
        }.error.code).isEqualTo("DEVICE_RECOVERY_REQUIRED")
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
    }

    @Test fun `corrupt gate never causes recovery or a fresh connection`() = directory { root ->
        val owner = uuid()
        quarantine(root, owner)
        Files.writeString(gatePath(root), "{\"schemaVersion\":1,\"schemaVersion\":1}")
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsSessionSupport.withSession(device, noHdc, lockDirectory = root) { error("Must not connect") }
        }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
        assertThat(assertThrows<AgentProtocolException> {
            HarmonyOsRecoverySupport.recover(device, owner, noHdc, root)
        }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
    }

    @Test fun `a complete receipt must have trustworthy zero remaining counters even with a matching digest`() = directory { root ->
        val invalidCounters: List<(ObjectNode) -> Unit> = listOf(
            { it.put("pendingOperations", 1) },
            { it.put("temporaryFiles", 1) },
            { it.put("operations", -1) },
            { it.put("pendingOperations", "0") },
            { it.remove("temporaryFiles") },
        )
        invalidCounters.forEachIndexed { index, mutate ->
            val fixture = Files.createDirectory(root.resolve("case-$index"))
            val owner = uuid()
            normalClose(fixture, owner)
            val metadata = gate(fixture)
            val receiptPath = Path.of(metadata["reportPath"].asText())
            val receipt = mapper.readTree(receiptPath.toFile()) as ObjectNode
            mutate(receipt)
            val altered = mapper.writeValueAsBytes(receipt)
            Files.write(receiptPath, altered)
            metadata.put("reportSha256", MessageDigest.getInstance("SHA-256").digest(altered).joinToString("") { "%02x".format(it) })
            Files.writeString(gatePath(fixture), mapper.writeValueAsString(metadata))
            assertThat(assertThrows<AgentProtocolException> {
                HarmonyOsRecoverySupport.recover(device, owner, noHdc, fixture)
            }.error.code).isEqualTo("RECOVERY_METADATA_INVALID")
        }
    }

    private fun quarantine(root: Path, owner: String) = HarmonyOsSessionSupport.withDeviceLock(device, root) {
        val lease = HarmonyOsSessionLease.begin(device, owner, root)
        HarmonyOsRecoverySupport.noteUncertainCleanup(owner, "Unconfirmed cancellation")
        assertThat(assertThrows<AgentProtocolException> { lease.finish(null, noHdc) }.error.code)
            .isEqualTo("DEVICE_RECOVERY_REQUIRED")
    }

    private fun normalClose(root: Path, owner: String) = HarmonyOsSessionSupport.withDeviceLock(device, root) {
        HarmonyOsSessionLease.begin(device, owner, root).finish(null, noHdc)
    }

    private fun gate(root: Path): ObjectNode = mapper.readTree(gatePath(root).toFile()) as ObjectNode
    private fun gatePath(root: Path): Path = root.resolve("${HarmonyOsSessionSupport.lockName(device)}.owner.json")
    private fun uuid(): String = UUID.randomUUID().toString()
    private class RecordingRemote(private val uri: String, private val video: ByteArray = "raw".toByteArray()) : HdcCommandRunner {
        var present = true
        var deletes = 0
        var exports = 0
        private var scratchPresent = false
        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            if (command.contains("recv") && command.contains("file")) {
                Files.write(Path.of(command.last()), video)
                return HdcCommandResult(0, "", "")
            }
            val script = command.last()
            var unquoted = script
            while ("'\"'\"'" in unquoted) unquoted = unquoted.replace("'\"'\"'", "'")
            val marker = Regex("MAESTRO_EXIT_[a-f0-9]+=").find(script)?.value
            val acknowledgement = Regex("MAESTRO_RECOVERY_[a-f0-9]+=").find(script)?.value
            if (marker == null && acknowledgement == null) {
                val nonce = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").findAll(script).last().value
                return HdcCommandResult(0, nonce, "")
            }
            var status = 0
            val output = when {
                unquoted.contains("'ps' '-ef'") -> "UID PID PPID C STIME TTY TIME CMD\n"
                unquoted.contains("'test' '-L'") -> { status = 1; "" }
                unquoted.contains("'test' '-e'") || unquoted.contains("'test' '-f'") -> {
                    status = if (scratchPresent) 0 else 1; ""
                }
                script.contains("hidumper") -> "PlayerDistributedService\n--ScreenCaptureServer--:\n--NextSection--\n"
                script.contains("mediatool") && script.contains("query") -> if (present) "find 1 result\nuri\n\"$uri\"" else {
                    status = 255; "find 0 result"
                }
                script.contains("mediatool") && script.contains("delete") -> { present = false; deletes++; "[SUCCESS] delete success." }
                script.contains("mediatool") && script.contains("recv") -> {
                    check(!scratchPresent) { "The system exporter refuses an existing destination." }
                    scratchPresent = true; exports++
                    "Table Name: Photos\n" + Regex("/data/local/tmp/maestro-session-[0-9a-f-]{36}/files/recording-[0-9a-f-]{36}\\.mp4").find(script)!!.value
                }
                unquoted.contains("'rm' '-f'") -> { scratchPresent = false; "" }
                unquoted.contains("'stat' '-c'") -> video.size.toString()
                else -> error("Unexpected command $command")
            }
            val deviceOutput = if (marker == null) output else "$output\n$marker$status\n"
            return HdcCommandResult(0, "$deviceOutput\n${checkNotNull(acknowledgement)}$status\n", "")
        }
    }
    private fun directory(block: (Path) -> Unit) = TempFileHandler().use { files ->
        block(files.createTempDirectory("harmonyos-lease-test").toPath())
    }
}
