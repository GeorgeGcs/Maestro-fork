package maestro.cli.session

import com.fasterxml.jackson.databind.JsonNode
import maestro.harmonyos.hdc.CleanupFenceObservation
import maestro.harmonyos.hdc.HarmonyOSCleanupFenceTransport
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcTimeoutException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Each remote mutation is bound to an immutable intent before dispatch. The original journal is not edited. */
internal class HarmonyOsCleanupFenceRecovery(
    private val lockDirectory: Path,
    private val origin: CleanupFenceOrigin,
    private val tools: Map<String, MaintenanceTool>,
    private val hdc: HdcClient,
    private val publishGate: (Path, LeaseGate) -> Unit = ::writeGate,
) {
    fun recover(device: String, owner: String): Map<String, Any?> = HarmonyOsSessionSupport.withDeviceLock(device, lockDirectory) {
        E.uuid(owner)
        val directory = lockDirectory.toAbsolutePath().normalize()
        var gate = requireNotNull(readGate(directory, device)) { "No durable device owner." }
        require(gate.ownerId == owner && gate.maintenance == null && gate.closeFailure == null && !gate.unresolvedRecording)
        verifyTools()
        if (gate.cleanupFence == null) {
            require(gate.state == "quarantined") { "Cleanup fencing requires an isolated original owner." }
            verifyRetained(directory, gate, hdc)
            gate = prepare(directory, gate)
        }
        val management = requireNotNull(gate.cleanupFence)
        val evidence = HarmonyOsMaintenanceEvidence(F.root(directory, device, owner, management.recoveryId))
        val cp = checkpoint(evidence, management.checkpoint, device, owner, management.recoveryId)
        require(cp.origin == origin && cp.tools == tools) { "Recovery tools changed since the intent was saved." }
        verifyOriginal(directory, cp, evidence)
        var step = readStep(evidence, management.head, management.checkpoint)
        validateChain(evidence, management.head, management.checkpoint)
        if (gate.state == "clean") {
            completedOutcome(directory, gate)
            val retained = gate.retainedFences.single { it.ownerId == owner && it.recoveryId == management.recoveryId }
            val receipt = validateRetained(directory, device, retained)
            verifyRetained(directory, gate, hdc)
            return@withDeviceLock response(gate, retained, receipt, evidence)
        }
        require(gate.state == "quarantined" && step.phase != "complete")
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        fun remaining(): Duration {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Cleanup fence recovery interrupted")
            val left = deadline - System.nanoTime()
            if (left <= 0) throw HdcTimeoutException(Duration.ofSeconds(30))
            return Duration.ofNanos(left)
        }
        fun publish(next: CleanupFenceStep, clean: RetainedCleanupFence? = null) {
            val nextStep = next.copy(revision = step.revision + 1, previous = gate.cleanupFence!!.head,
                observedAt = Instant.now().toString())
            val head = evidence.save("step", nextStep)
            val nextGate = gate.copy(cleanupFence = management.copy(head = head),
                state = if (clean == null) "quarantined" else "clean",
                requiresRecovery = clean == null, uncertainReason = if (clean == null) gate.uncertainReason else null,
                reportPath = clean?.let { evidence.root.resolve(it.receipt.path).toString() } ?: gate.reportPath,
                reportSha256 = clean?.receipt?.sha256 ?: gate.reportSha256,
                retainedFences = if (clean == null) gate.retainedFences else gate.retainedFences + clean,
                updatedAt = Instant.now().toString())
            publishGate(directory, nextGate)
            gate = nextGate; step = nextStep
        }
        val callsPath = evidence.root.resolve("calls.json")
        HarmonyOSRecoveryJournal.open(callsPath).use { calls ->
            // An empty, separate journal tracks native recovery invocations without rewriting the original.
            validateCallsJournal(E.mapper.readTree(E.read(callsPath)), cp, step, requireStopped = false)
            calls.recover(hdc, remaining(), cleanupResources = false)
            validateCallsJournal(E.mapper.readTree(E.read(callsPath)), cp, step, requireStopped = true)
            val transport = HarmonyOSCleanupFenceTransport(hdc, calls)
            fun query(action: String): CleanupFenceObservation {
                require(step.calls.size < 64) { "Recovery invocation budget exhausted." }
                verifyOriginal(directory, cp, evidence)
                verifyTools()
                try {
                    val observed = transport.execute(cp.scope, HarmonyOSCleanupFenceTransport.proof(management.checkpoint.sha256),
                        action, remaining()) { call ->
                        val journal = evidence.saveBytes("calls", E.read(callsPath))
                        publish(step.copy(journal = journal, calls = step.calls + CleanupFenceCallEvidence(call, null)))
                    }
                    val outcome = evidence.save("observation", observed)
                    val updated = step.calls.toMutableList()
                    require(updated.last().call.hostCallId == observed.hostCallId)
                    updated[updated.lastIndex] = updated.last().copy(outcome = outcome)
                    val snapshot = evidence.saveBytes("calls", E.read(callsPath))
                    publish(step.copy(journal = snapshot, calls = updated))
                    validateCallsJournal(evidence.json(step.journal), cp, step, requireStopped = true)
                    return observed
                } catch (error: Throwable) {
                    // A lost response remains explicit; a later fixed verification may prove its effects are contained.
                    try { publish(step.copy(journal = evidence.saveBytes("calls", E.read(callsPath)))) }
                    catch (persistError: Throwable) { if (persistError !== error) error.addSuppressed(persistError) }
                    throw error
                }
            }
            fun restoreOccupied(): Nothing {
                require(step.phase == "aborted" && step.calls.size == 1)
                val item = step.calls.single()
                require(item.call.action == "claim")
                val observed = evidence.document(requireNotNull(item.outcome), CleanupFenceObservation::class.java,
                    setOf("hostCallId", "action", "nonce", "phase", "stdout", "stderr"))
                require(observed.hostCallId == item.call.hostCallId && observed.action == "claim" && observed.nonce == item.call.nonce &&
                    HarmonyOSCleanupFenceTransport.confirmedPhase(observed.stdout, observed.stderr, observed.nonce) == "occupied")
                require(item.call.argumentsSha256 == E.digest(E.mapper.writeValueAsBytes(HarmonyOSCleanupFenceTransport.arguments(
                    cp.scope, HarmonyOSCleanupFenceTransport.proof(management.checkpoint.sha256), "claim", item.call.nonce))))
                val original = evidence.json(cp.originalGate)
                publishGate(directory, gate.copy(cleanupFence = null,
                    requiresRecovery = original["requiresRecovery"].booleanValue(),
                    uncertainReason = original["uncertainReason"].takeUnless(JsonNode::isNull)?.asText(),
                    updatedAt = Instant.now().toString()))
                throw IllegalStateException("The operation directory was already occupied. Original quarantine restored; use ordinary recovery to inspect its completion proof.")
            }
            if (step.phase == "aborted") restoreOccupied()
            if (step.phase == "prepared") {
                val observed = if (step.calls.any { it.call.action == "claim" }) query("verify") else query("claim")
                if (observed.phase == "occupied") {
                    // The atomic claim explicitly failed and its native invocation has stopped. No barrier was ours.
                    publish(step.copy(phase = "aborted"))
                    restoreOccupied()
                }
                publish(step.copy(phase = if (observed.phase == "cleaned") "cleaned" else "claimed"))
            }
            if (step.phase == "claimed") {
                if (step.calls.any { it.call.action == "clean" }) {
                    require(query("verify").phase == "cleaned") { "The previous cleanup has no confirming proof; do not replay it." }
                } else query("clean")
                publish(step.copy(phase = "cleaned"))
            }
            require(step.phase == "cleaned")
            if (HarmonyOSCleanupFenceTransport.splitInventory(cp.scope)) require(query("verify-root").phase == "cleaned")
            require(query("verify").phase == "cleaned")
            verifyOriginal(directory, cp, evidence)
            verifyTools()
            val receipt = CleanupFenceReceipt(4, cp.recoveryId, device, owner, Instant.now().toString(), "isolated", true,
                "prevented_before_dispatch", "unknown", 1, 0, management.checkpoint, gate.cleanupFence!!.head,
                listOf(cp.scope.remoteRoot))
            val reference = evidence.save("receipt", receipt)
            // Save the receipt reference while still isolated; interruption here is safe to resume.
            publish(step.copy(receipt = reference))
        }
        // Closing the call journal precedes the handoff commit.
        verifyOriginal(directory, cp, evidence)
        val retained = RetainedCleanupFence(owner, management.recoveryId, management.checkpoint, requireNotNull(step.receipt))
        val receipt = validateRetained(directory, device, retained)
        publish(step.copy(phase = "complete"), retained)
        response(gate, retained, receipt, evidence)
    }

    private fun verifyTools() {
        F.validateOrigin(origin)
        require(tools.isNotEmpty() && tools.values.all { E.fingerprint(Path.of(it.path)) == it }) { "Recovery tool fingerprint changed." }
        require(E.fingerprint(Path.of(origin.clientJar.path)) == origin.clientJar) { "Original CLI changed." }
    }

    private fun prepare(directory: Path, gate: LeaseGate): LeaseGate {
        require(gate.retainedFences.size < 8)
        val originalPath = checkedJournalPath(directory, gate)
        val originalBytes = E.read(originalPath)
        val scope = HarmonyOSRecoveryJournal.open(originalPath).use(HarmonyOSCleanupFenceTransport::inspect)
        require(E.read(originalPath).contentEquals(originalBytes)) { "Original journal changed while opening." }
        require(scope.deviceId == gate.deviceId && scope.ownerId == gate.ownerId)
        val actions = if (HarmonyOSCleanupFenceTransport.splitInventory(scope)) listOf("claim", "clean", "verify-root", "verify")
            else listOf("claim", "clean", "verify")
        for (action in actions) HarmonyOSCleanupFenceTransport.arguments(scope,
            HarmonyOSCleanupFenceTransport.proof("0".repeat(64)), action, "00000000-0000-0000-0000-000000000000")
        val recoveryId = UUID.randomUUID().toString()
        val root = F.root(directory, gate.deviceId, gate.ownerId, recoveryId)
        Files.createDirectories(root.parent); E.regularAncestors(root.parent); Files.createDirectory(root)
        val evidence = HarmonyOsMaintenanceEvidence(root)
        val receiptPath = Path.of(requireNotNull(gate.reportPath)).toAbsolutePath().normalize()
        require(receiptPath.parent == ownerDirectory(directory, gate.deviceId, gate.ownerId).resolve("recoveries"))
        val originalReceipt = E.read(receiptPath)
        require(E.digest(originalReceipt) == gate.reportSha256)
        val cp = CleanupFenceCheckpoint(1, recoveryId, gate.deviceId, gate.ownerId, Instant.now().toString(), scope,
            evidence.saveBytes("original-gate", E.read(gatePath(directory, gate.deviceId))),
            evidence.saveBytes("original-journal", originalBytes), evidence.saveBytes("original-receipt", originalReceipt), origin, tools)
        val cpRef = evidence.save("checkpoint", cp)
        val callsPath = root.resolve("calls.json")
        HarmonyOSRecoveryJournal.create(callsPath, gate.deviceId, recoveryId).use { it.seal() }
        val step = CleanupFenceStep(1, cpRef.sha256, 0, null, "prepared", Instant.now().toString(),
            evidence.saveBytes("calls", E.read(callsPath)), emptyList(), null)
        val prepared = gate.copy(cleanupFence = CleanupFenceGate(recoveryId, cpRef, evidence.save("step", step)),
            requiresRecovery = true, updatedAt = Instant.now().toString())
        publishGate(directory, prepared)
        return prepared
    }

    companion object {
        private val E = HarmonyOsMaintenanceEvidence
        private val F = HarmonyOsCleanupFenceEvidence

        private fun checkpoint(evidence: HarmonyOsMaintenanceEvidence, ref: MaintenanceReference,
                               device: String, owner: String, recovery: String): CleanupFenceCheckpoint {
            val cp = evidence.document(ref, CleanupFenceCheckpoint::class.java, F.checkpointFields)
            require(cp.schemaVersion == 1 && cp.deviceId == device && cp.ownerId == owner && cp.recoveryId == recovery)
            E.uuid(owner); E.uuid(recovery); Instant.parse(cp.createdAt); F.validateOrigin(cp.origin)
            require(cp.scope == HarmonyOSCleanupFenceTransport.scope(evidence.json(cp.originalJournal)))
            require(cp.scope.deviceId == device && cp.scope.ownerId == owner)
            val originalGate = evidence.json(cp.originalGate)
            require(originalGate["deviceId"].asText() == device && originalGate["ownerSessionId"].asText() == owner &&
                originalGate["state"].asText() == "quarantined" && originalGate["closeFailure"].isNull &&
                !originalGate["unresolvedRecording"].asBoolean() && originalGate["maintenance"] == null &&
                originalGate["cleanupFence"].let { it == null || it.isNull })
            val originalReceipt = evidence.json(cp.originalReceipt)
            require(originalGate["reportSha256"].asText() == cp.originalReceipt.sha256 &&
                originalReceipt["deviceId"].asText() == device && originalReceipt["sessionId"].asText() == owner &&
                originalReceipt["state"].asText() == "unknown")
            return cp
        }

        private fun verifyOriginal(directory: Path, cp: CleanupFenceCheckpoint, evidence: HarmonyOsMaintenanceEvidence) {
            val owner = ownerDirectory(directory, cp.deviceId, cp.ownerId)
            require(E.read(owner.resolve("recovery-journal.json")).contentEquals(evidence.bytes(cp.originalJournal))) {
                "The original journal changed; keep the device isolated."
            }
            require(Files.notExists(owner.resolve("recording-resources.json"), LinkOption.NOFOLLOW_LINKS))
            val originalGate = evidence.json(cp.originalGate)
            val receipt = Path.of(originalGate["reportPath"].asText()).toAbsolutePath().normalize()
            require(receipt.parent == owner.resolve("recoveries"))
            require(E.read(receipt).contentEquals(evidence.bytes(cp.originalReceipt)))
        }

        private fun readStep(evidence: HarmonyOsMaintenanceEvidence, ref: MaintenanceReference, cp: MaintenanceReference): CleanupFenceStep =
            evidence.document(ref, CleanupFenceStep::class.java, F.stepFields).also {
                require(it.schemaVersion == 1 && it.checkpointSha256 == cp.sha256 && it.revision in 0..256 &&
                    it.phase in F.phases && it.calls.size <= 64)
                Instant.parse(it.observedAt)
            }

        private fun validateChain(evidence: HarmonyOsMaintenanceEvidence, ref: MaintenanceReference, cp: MaintenanceReference) {
            var step = readStep(evidence, ref, cp)
            var count = 0
            while (step.previous != null) {
                require(++count <= 256)
                val previous = readStep(evidence, step.previous!!, cp)
                require(previous.revision + 1 == step.revision && F.phases.indexOf(previous.phase) <= F.phases.indexOf(step.phase))
                require(previous.calls.size <= step.calls.size && previous.calls.indices.all { index ->
                    previous.calls[index].call == step.calls[index].call &&
                        (previous.calls[index].outcome == null || previous.calls[index].outcome == step.calls[index].outcome)
                }) { "Recovery call history was rewritten." }
                step = previous
            }
            require(step.revision == 0 && step.phase == "prepared" && step.calls.isEmpty() && step.receipt == null)
        }

        private fun validateCallsJournal(node: JsonNode, cp: CleanupFenceCheckpoint, step: CleanupFenceStep, requireStopped: Boolean) {
            require(node["version"].asInt() == 1 && node["deviceId"].asText() == cp.deviceId && node["ownerId"].asText() == cp.recoveryId &&
                node["sealed"].asBoolean() && node["rootRemoved"].asBoolean() && node["operations"].isEmpty && node["temporaryFiles"].isEmpty)
            val calls = node["hostCalls"].toList()
            require(calls.map { it["id"].asText() } == step.calls.map { it.call.hostCallId }) { "Unbound recovery invocation." }
            require(calls.map { it["id"].asText() }.distinct().size == calls.size)
            if (requireStopped) require(calls.all { it["stopped"].asBoolean() }) { "Recovery host invocation is not confirmed stopped." }
        }

        internal fun validateRetained(directory: Path, device: String, retained: RetainedCleanupFence): CleanupFenceReceipt {
            val evidence = HarmonyOsMaintenanceEvidence(F.root(directory, device, retained.ownerId, retained.recoveryId))
            val cp = checkpoint(evidence, retained.checkpoint, device, retained.ownerId, retained.recoveryId)
            verifyOriginal(directory, cp, evidence)
            val receipt = evidence.document(retained.receipt, CleanupFenceReceipt::class.java, F.receiptFields)
            require(receipt.schemaVersion == 4 && receipt.recoveryId == cp.recoveryId && receipt.deviceId == device &&
                receipt.sessionId == cp.ownerId && receipt.checkpoint == retained.checkpoint && receipt.state == "isolated" &&
                receipt.handoffReady && receipt.disposition == "prevented_before_dispatch" && receipt.originalResult == "unknown" &&
                receipt.pendingOperations == 1 && receipt.temporaryFiles == 0 && receipt.retainedDirectories == listOf(cp.scope.remoteRoot))
            require(Instant.parse(receipt.observedAt) >= Instant.parse(cp.createdAt))
            val step = readStep(evidence, receipt.verifiedHead, retained.checkpoint)
            require(step.phase == "cleaned")
            validateChain(evidence, receipt.verifiedHead, retained.checkpoint)
            val callsNode = evidence.json(step.journal)
            validateCallsJournal(callsNode, cp, step, requireStopped = true)
            require(E.read(evidence.root.resolve("calls.json")).contentEquals(evidence.bytes(step.journal))) { "Recovery journal changed after release." }
            require(step.calls.count { it.call.action == "claim" } == 1 && step.calls.count { it.call.action == "clean" } == 1)
            for (item in step.calls) {
                E.uuid(item.call.hostCallId); E.uuid(item.call.nonce)
                val args = HarmonyOSCleanupFenceTransport.arguments(cp.scope,
                    HarmonyOSCleanupFenceTransport.proof(retained.checkpoint.sha256), item.call.action, item.call.nonce)
                require(item.call.argumentsSha256 == E.digest(E.mapper.writeValueAsBytes(args)))
                item.outcome?.let { ref ->
                    val result = evidence.document(ref, CleanupFenceObservation::class.java,
                        setOf("hostCallId", "action", "nonce", "phase", "stdout", "stderr"))
                    require(result.hostCallId == item.call.hostCallId && result.action == item.call.action && result.nonce == item.call.nonce)
                    require(result.phase == HarmonyOSCleanupFenceTransport.confirmedPhase(result.stdout, result.stderr, result.nonce))
                    require(result.action != "claim" || result.phase == "claimed")
                    require(result.action != "clean" || result.phase == "cleaned")
                }
            }
            val last = step.calls.last()
            require(last.call.action == "verify" && last.outcome != null && evidence.json(last.outcome)["phase"].asText() == "cleaned")
            if (HarmonyOSCleanupFenceTransport.splitInventory(cp.scope)) {
                val rootCheck = step.calls.getOrNull(step.calls.lastIndex - 1)
                require(rootCheck?.call?.action == "verify-root" && rootCheck.outcome != null &&
                    evidence.json(rootCheck.outcome)["phase"].asText() == "cleaned") { "Split inventory lacks its final root verification." }
            }
            return receipt
        }

        internal fun verifyRetained(directory: Path, gate: LeaseGate, hdc: HdcClient? = null) {
            require(gate.retainedFences.size <= 8 && gate.retainedFences.map { it.ownerId to it.recoveryId }.distinct().size == gate.retainedFences.size)
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            for (retained in gate.retainedFences) {
                validateRetained(directory, gate.deviceId, retained)
                val saved = HarmonyOsMaintenanceEvidence(F.root(directory, gate.deviceId, retained.ownerId, retained.recoveryId))
                val checkpoint = checkpoint(saved, retained.checkpoint, gate.deviceId, retained.ownerId, retained.recoveryId)
                val old = saved.json(checkpoint.originalGate)["retainedFences"]
                if (old != null) require(old.all { value ->
                    val prior = E.mapper.treeToValue(value, RetainedCleanupFence::class.java)
                    prior in gate.retainedFences
                }) { "A prior retained fence was dropped." }
                if (hdc != null) {
                    val evidence = HarmonyOsMaintenanceEvidence(F.root(directory, gate.deviceId, retained.ownerId, retained.recoveryId))
                    val cp = checkpoint(evidence, retained.checkpoint, gate.deviceId, retained.ownerId, retained.recoveryId)
                    val actions = if (HarmonyOSCleanupFenceTransport.splitInventory(cp.scope)) listOf("verify-root", "verify") else listOf("verify")
                    for (action in actions) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) throw HdcTimeoutException(Duration.ofSeconds(30))
                        require(HarmonyOSCleanupFenceTransport(hdc).execute(cp.scope,
                            HarmonyOSCleanupFenceTransport.proof(retained.checkpoint.sha256), action, Duration.ofNanos(remaining)).phase == "cleaned")
                    }
                }
            }
        }

        internal fun completedOutcome(directory: Path, gate: LeaseGate): RecoveryOutcome {
            val management = requireNotNull(gate.cleanupFence)
            require(gate.state == "clean" && !gate.requiresRecovery)
            val retained = gate.retainedFences.single { it.ownerId == gate.ownerId && it.recoveryId == management.recoveryId }
            require(retained.checkpoint == management.checkpoint)
            val receipt = validateRetained(directory, gate.deviceId, retained)
            val evidence = HarmonyOsMaintenanceEvidence(F.root(directory, gate.deviceId, gate.ownerId, management.recoveryId))
            val step = readStep(evidence, management.head, management.checkpoint)
            validateChain(evidence, management.head, management.checkpoint)
            require(step.phase == "complete" && step.receipt == retained.receipt)
            val path = evidence.root.resolve(retained.receipt.path)
            require(gate.reportPath == path.toString() && gate.reportSha256 == retained.receipt.sha256)
            @Suppress("UNCHECKED_CAST") val report = E.mapper.convertValue(receipt, Map::class.java) as Map<String, Any?>
            return RecoveryOutcome(gate.deviceId, gate.ownerId, true, path, retained.receipt.sha256, report)
        }

        private fun response(gate: LeaseGate, retained: RetainedCleanupFence, receipt: CleanupFenceReceipt,
                             evidence: HarmonyOsMaintenanceEvidence): Map<String, Any?> = mapOf(
            "protocol" to "maestro.harmonyos.cleanup-fence-recovery", "schemaVersion" to 1,
            "deviceId" to gate.deviceId, "sessionId" to gate.ownerId, "recoveryId" to retained.recoveryId,
            "state" to receipt.state, "handoffReady" to true, "originalResult" to "unknown",
            "disposition" to receipt.disposition, "retainedDirectories" to receipt.retainedDirectories,
            "receipt" to mapOf("path" to evidence.root.resolve(retained.receipt.path).toString(),
                "sha256" to retained.receipt.sha256, "schemaVersion" to 4))
    }
}
