package maestro.cli.session

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.cli.harmonyos.AgentProtocolException
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Synthetic host/device observations; uses the real journal, lease and maintenance transport, never HDC. */
@EnabledOnOs(OS.WINDOWS)
class HarmonyOsMaintenanceRecoveryTest {
    private val mapper = jacksonObjectMapper()
    private val device = "maintenance-fixture-device"
    private val oldBoot = "2026-10-20T00:00:00.000Z"
    private val newBoot = "2026-10-30T00:00:00.000Z"
    private val beforePhone = " 23:02:14 up 4 days, 35 min,  0 users,  load average: 12.18, 12.26, 12.44\n"
    private val afterPhone = " 23:10:14 up 1 min,  0 users,  load average: 0.18, 0.26, 0.44\n"

    private inner class Fixture(val root: Path, recording: Boolean = false, retainedImages: Boolean = false) {
        val owner = UUID.randomUUID().toString()
        var tools: Map<String, MaintenanceTool>
        var powershellVersion = "7.5.0"
        val originalReceipt: Path
        val originalReceiptBytes: ByteArray
        val originalJournalBytes: ByteArray
        var hostObservations = 0
        var deviceCalls = 0
        var cleanupCalls = 0
        var unchangedHost = false
        var secondHostRestart = false
        var unchangedPhone = false
        var failPhone = false
        var wrongCleanup = false
        var rejectReceiptGateOnce = false
        var rejectCleanGateOnce = false
        val journalPath: Path get() = ownerDirectory(root, device, owner).resolve("recovery-journal.json")
        init {
            val toolsRoot = Files.createDirectory(root.resolve("fixture-tools"))
            val lib = Files.createDirectory(toolsRoot.resolve("lib"))
            tools = listOf("powershell" to toolsRoot.resolve("pwsh.exe"), "script" to toolsRoot.resolve("observe.ps1"),
                "hdc" to toolsRoot.resolve("fake-hdc.exe"), "java" to toolsRoot.resolve("java.exe"),
                "classpath:0:fixture.jar" to lib.resolve("fixture.jar")).associate { (key, path) ->
                Files.writeString(path, "host-fixture-$key", StandardOpenOption.CREATE_NEW)
                key to HarmonyOsMaintenanceEvidence.fingerprint(path)
            }
            HarmonyOsSessionSupport.withDeviceLock(device, root) {
                val lease = HarmonyOsSessionLease.begin(device, owner, root, requiresRecordingCleanup = recording)
                val lost = HdcClient("fake-original", HdcCommandRunner { _, _ -> HdcCommandResult(0, "missing original proof", "") })
                    .withRecoveryJournal(lease.journal)
                assertThrows<HdcException> { lost.shell(device, listOf("uitest", "dumpLayout")) }
                if (retainedImages) {
                    val adapter = HdcHarmonyOSDevice(device, lost)
                    assertThrows<Exception> { adapter.hierarchy(java.time.Duration.ofSeconds(2)) }
                    assertThrows<Exception> { adapter.screenshot(java.time.Duration.ofSeconds(2)) }
                }
                HarmonyOsRecoverySupport.noteUncertainCleanup(owner, "Synthetic original proof loss")
                assertThrows<AgentProtocolException> { lease.finish(null, noHdc()) }
            }
            originalReceipt = Path.of(gate()["reportPath"].asText())
            originalReceiptBytes = Files.readAllBytes(originalReceipt)
            originalJournalBytes = Files.readAllBytes(journalPath)
        }
        fun gate(): JsonNode = mapper.readTree(gatePath(root, device).toFile())
        fun head(): JsonNode {
            val management = gate()["maintenance"]
            val evidence = ownerDirectory(root, device, owner).resolve("maintenance").resolve(management["maintenanceId"].asText())
            return mapper.readTree(evidence.resolve(management["head"]["path"].asText()).toFile())
        }
        fun observe(path: Path): JsonNode {
            val index = hostObservations++
            val boot = if (index == 0 || unchangedHost) oldBoot else if (index >= 2 && secondHostRestart) "2026-10-30T01:00:00.000Z" else newBoot
            val ticks = if (index == 0) "900000000" else if (unchangedHost) "900000001" else (1000 + index * 100).toString()
            val node: JsonNode = mapper.valueToTree(mapOf("protocol" to "maestro.harmonyos.host-boot-observation", "schemaVersion" to 1,
                "provider" to mapOf("name" to "windows-gettickcount64-cim", "version" to 1),
                "observedAt" to "2026-10-30T0${index + 2}:00:00.000Z", "uptimeMilliseconds" to ticks,
                "lastBootUpTime" to boot, "machineIdSha256" to "a".repeat(64),
                "tools" to mapOf("powershell" to (mapper.convertValue(tools.getValue("powershell"), Map::class.java) + mapOf("version" to powershellVersion)),
                    "script" to tools.getValue("script"))))
            Files.write(path, mapper.writeValueAsBytes(node), StandardOpenOption.CREATE_NEW)
            // The production observer returns the persisted JSON, including its parsed number types.
            return mapper.readTree(Files.readAllBytes(path))
        }
        fun hdc() = HdcClient("fake-maintenance", HdcCommandRunner { command, _ ->
            deviceCalls++
            assertThat(command.take(4)).containsExactly("fake-maintenance", "-t", device, "shell").inOrder()
            // Binding must already be the committed gate head when transport is reached.
            val bound = head()["calls"].last()
            assertThat(bound["outcome"].isNull).isTrue()
            assertThat(bound["endedJournal"].isNull).isTrue()
            val registered = mapper.readTree(journalPath.toFile())["hostCalls"].last()
            assertThat(registered["id"].asText()).isEqualTo(bound["hostCallId"].asText())
            assertThat(registered["returned"].asBoolean()).isFalse()
            if (command.last() == "'uptime'") {
                assertThat(bound["purpose"].asText()).isEqualTo("observe_uptime")
                if (failPhone) throw HdcException("Synthetic failed phone query")
                HdcCommandResult(0, if (deviceCalls == 1 || unchangedPhone) beforePhone else afterPhone, "")
            } else {
                cleanupCalls++
                assertThat(bound["purpose"].asText()).isEqualTo("cleanup_owned_paths")
                assertThat(head()["termination"].isNull).isFalse()
                val shell = command.last().removePrefix("'sh' '-c' '").removeSuffix("'").replace("'\"'\"'", "'")
                assertThat(shell).doesNotContain("rm -r")
                assertThat(shell).doesNotContain("*")
                val nonce = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").findAll(shell).last().value
                HdcCommandResult(0, if (wrongCleanup) "wrong-confirmation" else nonce, "")
            }
        })
        fun recovery() = HarmonyOsMaintenanceRecovery(root, tools, ::observe, ::hdc) { directory, gate ->
            val reference = gate.maintenance?.head
            val phase = reference?.let { mapper.readTree(ownerDirectory(root, device, owner).resolve("maintenance")
                .resolve(gate.maintenance!!.maintenanceId).resolve(it.path).toFile())["phase"].asText() }
            if (rejectReceiptGateOnce && phase == "receipt_written") {
                rejectReceiptGateOnce = false; throw IOException("Synthetic receipt gate publication failure")
            }
            if (rejectCleanGateOnce && gate.state == "clean") {
                rejectCleanGateOnce = false; throw IOException("Synthetic clean gate publication failure")
            }
            writeGate(directory, gate)
        }
        fun assertOriginalPreserved() {
            assertThat(Files.readAllBytes(originalReceipt)).isEqualTo(originalReceiptBytes)
            val before = mapper.readTree(originalJournalBytes).deepCopy<ObjectNode>().apply { remove("hostCalls") }
            val after = mapper.readTree(journalPath.toFile()).deepCopy<ObjectNode>().apply { remove("hostCalls") }
            assertThat(after).isEqualTo(before)
            assertThat(after["operations"].all { !it["completed"].asBoolean() && !it["markerRemoved"].asBoolean() }).isTrue()
        }
    }

    private fun Fixture.updatedPlan(checkpoint: MaintenanceReference): Path {
        val executable = Path.of(tools.getValue("powershell").path)
        Files.writeString(executable, "updated Windows PowerShell")
        tools = tools + ("powershell" to HarmonyOsMaintenanceEvidence.fingerprint(executable))
        powershellVersion = "7.5.1"
        val plan = root.resolve("tool-approval.json")
        Files.write(plan, mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "reason" to "windows_powershell_update",
            "checkpointSha256" to checkpoint.sha256, "tools" to tools)))
        return plan
    }

    @Test fun `explicit system update migration preserves old evidence and completes with new PowerShell version`() = fixture { f ->
        val prepared = f.recovery().prepare(device, f.owner)
        val oldCheckpoint = Files.readAllBytes(Path.of(prepared.evidenceDirectory).resolve(prepared.checkpoint.path))
        val approval = f.updatedPlan(prepared.checkpoint)
        assertThrows<AgentProtocolException> { f.recovery().resume(device, f.owner) }
        val migrated = f.recovery().migrateHost(device, f.owner, approval)
        assertThat(migrated.phase).isEqualTo("prepared")
        assertThat(migrated.complete).isFalse()
        assertThat(f.deviceCalls).isEqualTo(0)
        assertThat(Files.readAllBytes(Path.of(prepared.evidenceDirectory).resolve(prepared.checkpoint.path))).isEqualTo(oldCheckpoint)
        assertThat(f.recovery().migrateHost(device, f.owner, approval).checkpoint).isEqualTo(migrated.checkpoint)
        assertThat(f.recovery().resume(device, f.owner).phase).isEqualTo("phone_prepared")
        assertThat(f.recovery().complete(device, f.owner).complete).isTrue()
        f.assertOriginalPreserved()
    }

    @Test fun `migration rejects altered approval stale checkpoint extra tool and dependency changes without gate mutation`() {
        for (mode in listOf("approval", "checkpoint", "script", "dependency", "extra", "no-restart", "missing-original")) fixture { f ->
            val prepared = f.recovery().prepare(device, f.owner)
            val approval = f.updatedPlan(prepared.checkpoint)
            val before = Files.readAllBytes(gatePath(f.root, device))
            if (mode == "approval") Files.writeString(approval, "{}")
            if (mode == "checkpoint") {
                val value = mapper.readTree(approval.toFile()).deepCopy<ObjectNode>(); value.put("checkpointSha256", "a".repeat(64))
                Files.write(approval, mapper.writeValueAsBytes(value))
            }
            if (mode in listOf("script", "dependency", "extra")) {
                val key = if (mode == "dependency") "classpath:0:fixture.jar" else "script"
                val file = Path.of(f.tools.getValue(key).path); Files.writeString(file, "unexpected content")
                f.tools = f.tools + ((if (mode == "extra") "extra" else key) to HarmonyOsMaintenanceEvidence.fingerprint(file))
                Files.write(approval, mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "reason" to "windows_powershell_update",
                    "checkpointSha256" to prepared.checkpoint.sha256, "tools" to f.tools)))
            }
            if (mode == "no-restart") f.unchangedHost = true
            if (mode == "missing-original") Files.delete(Path.of(f.tools.getValue("hdc").path))
            assertThrows<AgentProtocolException> { f.recovery().migrateHost(device, f.owner, approval) }
            assertThat(Files.readAllBytes(gatePath(f.root, device))).isEqualTo(before)
            assertThat(f.deviceCalls).isEqualTo(0)
        }
    }

    @Test fun `migration cannot reset an already dispatched phone baseline`() = fixture { f ->
        val prepared = f.recovery().prepare(device, f.owner); f.recovery().resume(device, f.owner)
        val approval = f.updatedPlan(prepared.checkpoint); val gate = f.gate()
        assertThrows<AgentProtocolException> { f.recovery().migrateHost(device, f.owner, approval) }
        assertThat(f.gate()).isEqualTo(gate); assertThat(f.deviceCalls).isEqualTo(1)
    }

    @Test fun `another host restart after migration rejects resume before device dispatch`() = fixture { f ->
        val prepared = f.recovery().prepare(device, f.owner); val approval = f.updatedPlan(prepared.checkpoint)
        f.recovery().migrateHost(device, f.owner, approval); f.secondHostRestart = true
        assertThrows<AgentProtocolException> { f.recovery().resume(device, f.owner) }
        assertThat(f.deviceCalls).isEqualTo(0)
    }

    @Test fun `ordered restart chain releases only through verified receipt three without rewriting original failure`() = fixture { f ->
        val recovery = f.recovery()
        assertThat(recovery.prepare(device, f.owner).phase).isEqualTo("prepared")
        assertThat(f.gate()["schemaVersion"].asInt()).isEqualTo(2)
        assertThat(f.deviceCalls).isEqualTo(0)
        assertThrows<AgentProtocolException> { HarmonyOsRecoverySupport.recover(device, f.owner, noHdc(), f.root) }
        assertThat(HarmonyOsSessionLease.requiresRecovery(device, f.root)).isTrue()
        assertThat(recovery.resume(device, f.owner).phase).isEqualTo("phone_prepared")
        val savedHead = f.gate()["maintenance"]["head"]
        assertThat(recovery.resume(device, f.owner).phase).isEqualTo("phone_prepared")
        assertThat(f.gate()["maintenance"]["head"]).isEqualTo(savedHead)
        assertThat(f.deviceCalls).isEqualTo(1)
        val completed = recovery.complete(device, f.owner)
        assertThat(completed.complete).isTrue()
        assertThat(completed.receipt!!.report["schemaVersion"]).isEqualTo(3)
        assertThat(completed.receipt.report["originalExecutionResult"]).isEqualTo("unknown")
        assertThat(completed.receipt.report["originalPendingOperations"]).isEqualTo(1)
        assertThat(f.cleanupCalls).isEqualTo(1)
        assertThat(f.deviceCalls).isEqualTo(3)
        f.assertOriginalPreserved()
        assertThat(recovery.complete(device, f.owner).receipt!!.sha256).isEqualTo(completed.receipt.sha256)
        assertThat(recovery.resume(device, f.owner).complete).isTrue()
        assertThat(f.deviceCalls).isEqualTo(3)
        assertThat(HarmonyOsSessionLease.requiresRecovery(device, f.root)).isFalse()
        HarmonyOsSessionSupport.withDeviceLock(device, f.root) {
            HarmonyOsSessionLease.begin(device, UUID.randomUUID().toString(), f.root).finish(null, noHdc())
        }
    }

    @Test fun `unconfirmed host restart cannot dispatch phone observation`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner); f.unchangedHost = true
        assertThrows<AgentProtocolException> { recovery.resume(device, f.owner) }
        assertThat(f.deviceCalls).isEqualTo(0)
        assertThat(f.head()["phase"].asText()).isEqualTo("prepared")
        f.assertOriginalPreserved()
    }

    @Test fun `a second host restart before complete cannot dispatch or clean`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner); recovery.resume(device, f.owner)
        f.secondHostRestart = true
        assertThrows<AgentProtocolException> { recovery.complete(device, f.owner) }
        assertThat(f.deviceCalls).isEqualTo(1)
        assertThat(f.cleanupCalls).isEqualTo(0)
        assertThat(HarmonyOsSessionLease.requiresRecovery(device, f.root)).isTrue()
    }

    @Test fun `early complete preserves confirmed observation and later device restart can complete`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner); recovery.resume(device, f.owner)
        f.unchangedPhone = true
        assertThrows<AgentProtocolException> { recovery.complete(device, f.owner) }
        assertThat(f.deviceCalls).isEqualTo(2)
        assertThat(f.cleanupCalls).isEqualTo(0)
        f.unchangedPhone = false
        assertThat(recovery.complete(device, f.owner).complete).isTrue()
        assertThat(f.deviceCalls).isEqualTo(4)
        assertThat(f.head()["calls"].count { it["phase"].asText() == "phone_prepared" }).isEqualTo(2)
        assertThat(f.cleanupCalls).isEqualTo(1)
        f.assertOriginalPreserved()
    }

    @Test fun `unconfirmed maintenance call prevents all retry dispatch and ordinary recovery`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner); f.failPhone = true
        assertThrows<AgentProtocolException> { recovery.resume(device, f.owner) }
        assertThat(f.head()["calls"].single()["outcome"].isNull).isTrue()
        assertThrows<AgentProtocolException> { recovery.resume(device, f.owner) }
        assertThrows<AgentProtocolException> { recovery.complete(device, f.owner) }
        assertThrows<AgentProtocolException> { HarmonyOsRecoverySupport.recover(device, f.owner, noHdc(), f.root) }
        assertThat(f.deviceCalls).isEqualTo(1)
        f.assertOriginalPreserved()
    }

    @Test fun `wrong cleanup acknowledgement cannot produce a release receipt`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner); recovery.resume(device, f.owner); f.wrongCleanup = true
        assertThrows<AgentProtocolException> { recovery.complete(device, f.owner) }
        assertThat(f.head()["phase"].asText()).isEqualTo("cleaning")
        assertThat(f.head()["cleanupBatches"].size()).isEqualTo(0)
        assertThat(f.gate()["state"].asText()).isEqualTo("quarantined")
        assertThrows<AgentProtocolException> { recovery.complete(device, f.owner) }
        assertThat(f.cleanupCalls).isEqualTo(1)
        f.assertOriginalPreserved()
    }

    @Test fun `written receipt survives failed gate publication and retry never repeats device cleanup`() = fixture { f ->
        val recovery = f.recovery(); val prepared = recovery.prepare(device, f.owner); recovery.resume(device, f.owner)
        f.rejectReceiptGateOnce = true
        assertThrows<AgentProtocolException> { recovery.complete(device, f.owner) }
        assertThat(f.head()["phase"].asText()).isEqualTo("cleaned")
        val receipt = ownerDirectory(f.root, device, f.owner).resolve("recoveries").resolve("${prepared.maintenanceId}.json")
        val bytes = Files.readAllBytes(receipt)
        assertThat(f.gate()["state"].asText()).isEqualTo("quarantined")
        val completed = recovery.complete(device, f.owner)
        assertThat(completed.complete).isTrue()
        assertThat(Files.readAllBytes(receipt)).isEqualTo(bytes)
        assertThat(f.cleanupCalls).isEqualTo(1)
        assertThat(f.deviceCalls).isEqualTo(3)
    }

    @Test fun `verified receipt written stage retries only the final clean gate`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner); recovery.resume(device, f.owner)
        f.rejectCleanGateOnce = true
        assertThrows<AgentProtocolException> { recovery.complete(device, f.owner) }
        assertThat(f.head()["phase"].asText()).isEqualTo("receipt_written")
        assertThat(recovery.complete(device, f.owner).complete).isTrue()
        assertThat(f.deviceCalls).isEqualTo(3)
        f.assertOriginalPreserved()
    }

    @Test fun `changed tools or extra classpath member stop before dispatch`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner)
        Files.writeString(Path.of(f.tools.getValue("classpath:0:fixture.jar").path).parent.resolve("extra.JAR"), "unexpected")
        assertThrows<AgentProtocolException> { recovery.resume(device, f.owner) }
        assertThat(f.deviceCalls).isEqualTo(0)
        assertThat(f.hostObservations).isEqualTo(1)
    }

    @Test fun `recording resource owner is ineligible for first maintenance implementation`() = fixture(recording = true) { f ->
        val before = Files.readAllBytes(gatePath(f.root, device))
        assertThrows<AgentProtocolException> { f.recovery().prepare(device, f.owner) }
        assertThat(Files.readAllBytes(gatePath(f.root, device))).isEqualTo(before)
        assertThat(f.deviceCalls).isEqualTo(0)
        assertThat(f.hostObservations).isEqualTo(0)
    }

    @Test fun `tampered referenced evidence cannot make a clean gate available`() = fixture { f ->
        val recovery = f.recovery(); recovery.prepare(device, f.owner); recovery.resume(device, f.owner)
        val done = recovery.complete(device, f.owner)
        val checkpoint = mapper.readTree(Path.of(done.evidenceDirectory).resolve(done.checkpoint.path).toFile())
        val original = Path.of(done.evidenceDirectory).resolve(checkpoint["originalJournal"]["path"].asText())
        Files.writeString(original, "{}")
        assertThrows<Exception> { HarmonyOsSessionLease.requiresRecovery(device, f.root) }
        assertThrows<AgentProtocolException> {
            HarmonyOsSessionSupport.withDeviceLock(device, f.root) { HarmonyOsSessionLease.begin(device, UUID.randomUUID().toString(), f.root) }
        }
        assertThat(f.deviceCalls).isEqualTo(3)
    }

    @Test fun `ordered maintenance accepts retained layout and screenshot ownership without changing original journal`() = fixture(retainedImages = true) { f ->
        val original = mapper.readTree(f.originalJournalBytes)
        assertThat(original["temporaryFiles"].size()).isEqualTo(2)
        assertThat(f.recovery().prepare(device, f.owner).phase).isEqualTo("prepared")
        assertThat(f.deviceCalls).isEqualTo(0)
        assertThat(f.recovery().resume(device, f.owner).phase).isEqualTo("phone_prepared")
        assertThat(f.recovery().complete(device, f.owner).complete).isTrue()
        f.assertOriginalPreserved()
        val finished = mapper.readTree(Path.of(f.gate()["reportPath"].asText()).toFile())
        assertThat(finished["originalPendingOperations"].asInt()).isEqualTo(original["operations"].size())
        assertThat(finished["temporaryFiles"].asInt()).isEqualTo(0)
        assertThat(f.gate()["state"].asText()).isEqualTo("clean")
    }

    private fun noHdc() = HdcClient("forbidden", HdcCommandRunner { _, _ -> error("No HDC call is permitted") })
    private fun fixture(recording: Boolean = false, retainedImages: Boolean = false, block: (Fixture) -> Unit) = TempFileHandler().use { files ->
        block(Fixture(files.createTempDirectory("maintenance-recovery-test").toPath(), recording, retainedImages))
    }
}
