package maestro.cli.session

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import maestro.cli.harmonyos.AgentError
import maestro.cli.harmonyos.AgentProtocolException
import maestro.harmonyos.hdc.HarmonyOSMaintenanceTransport
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.harmonyos.hdc.HarmonyOSUptimeObservation
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.MaintenanceCall
import maestro.harmonyos.hdc.MaintenanceCleanupBatch
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class MaintenanceOutcome(
    val maintenanceId: String,
    val deviceId: String,
    val ownerId: String,
    val phase: String,
    val complete: Boolean,
    val checkpoint: MaintenanceReference,
    val head: MaintenanceReference,
    val evidenceDirectory: String,
    val receipt: RecoveryOutcome? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf("protocol" to "harmonyos.reboot-recovery", "schemaVersion" to 1,
        "maintenanceId" to maintenanceId, "deviceId" to deviceId, "ownerId" to ownerId,
        "state" to if (complete) "complete" else "unknown", "cleanup" to mapOf("state" to if (complete) "complete" else "unknown"),
        "phase" to phase, "handoffReady" to complete, "checkpoint" to checkpoint, "head" to head,
        "evidenceDirectory" to evidenceDirectory, "receipt" to receipt?.let {
            mapOf("path" to it.reportPath.toString(), "sha256" to it.sha256, "schemaVersion" to 3)
        })
}

/** Explicit host-restart, then device-restart maintenance. Ordinary command results are never rewritten. */
internal class HarmonyOsMaintenanceRecovery(
    private val lockDirectory: Path,
    private val tools: Map<String, MaintenanceTool>,
    private val observeHost: (Path) -> JsonNode,
    private val hdcFactory: () -> HdcClient,
    private val gateWriter: (Path, LeaseGate) -> Unit = ::writeGate,
) {
    /** Explicit, hash-bound migration, only before any maintenance device dispatch. */
    fun migrateHost(deviceId: String, ownerId: String, approval: Path): MaintenanceOutcome = locked(deviceId, ownerId) { directory, gate ->
        val state = load(directory, gate, gateWriter)
        val plan = mapper.readTree(E.read(approval, 1024 * 1024))
        E.exactFields(plan, setOf("schemaVersion", "reason", "checkpointSha256", "tools"))
        require(plan["schemaVersion"].asInt() == 1 && plan["reason"].asText() == "windows_powershell_update")
        // Read both sides as JSON: file sizes deserialize as IntNode when small, while
        // valueToTree preserves the Kotlin Long and would reject identical manifests.
        require(plan["tools"] == mapper.readTree(mapper.writeValueAsBytes(tools))) { "Approved tools differ from the running distribution." }
        verifyTools(tools)
        if (state.checkpoint.schemaVersion == 2) {
            val transition = state.evidence.json(requireNotNull(state.checkpoint.transition))
            require(plan["checkpointSha256"].asText() == transition["previousCheckpoint"]["sha256"].asText())
            verifyFrozen(state)
            return@locked state.outcome()
        }
        require(plan["checkpointSha256"].asText() == gate.maintenance!!.checkpoint.sha256)
        requirePrepared(state.step, gate.maintenance!!.checkpoint, state.checkpoint.originalJournal)
        validateToolTransition(state.checkpoint.tools, tools)
        // The original distribution remains intact. Only the OS-owned PowerShell file may have changed.
        val originalWithUpdatedPowerShell = state.checkpoint.tools + ("powershell" to tools.getValue("powershell"))
        verifyTools(originalWithUpdatedPowerShell)
        val observed = observe(state.evidence)
        val current = HarmonyOsMaintenanceHostBoot.parse(state.evidence.json(observed), tools.getValue("powershell"), tools.getValue("script"))
        HarmonyOsMaintenanceHostBoot.requireRestartedAfterToolMigration(state.host(state.checkpoint.hostBefore), current)
        val savedPlan = state.evidence.save("tool-approval", plan)
        val transition = state.evidence.save("host-tool-transition", mapOf("schemaVersion" to 1,
            "reason" to "windows_powershell_update", "previousCheckpoint" to gate.maintenance!!.checkpoint,
            "previousHead" to gate.maintenance!!.head,
            "previousGate" to state.evidence.saveBytes("previous-gate", E.read(gatePath(directory, deviceId))),
            "approval" to savedPlan, "hostObserved" to observed))
        val checkpoint = state.checkpoint.copy(schemaVersion = 2, createdAt = now(), tools = tools.toSortedMap(), transition = transition)
        val reference = state.evidence.save("checkpoint", checkpoint)
        val head = state.evidence.save("step", state.step.copy(checkpointSha256 = reference.sha256, observedAt = now()))
        verifyTools(tools); verifyTools(originalWithUpdatedPowerShell); state.verifyCurrentJournal()
        val updated = gate.copy(maintenance = gate.maintenance!!.copy(checkpoint = reference, head = head), updatedAt = now())
        gateWriter(directory, updated)
        load(directory, updated, gateWriter).outcome()
    }

    fun prepare(deviceId: String, ownerId: String): MaintenanceOutcome = locked(deviceId, ownerId) { directory, gate ->
        if (gate.maintenance != null) return@locked load(directory, gate, gateWriter).also { requireSettled(it); verifyFrozen(it) }.outcome()
        require(gate.state == "quarantined" && gate.closeFailure == null && !gate.unresolvedRecording)
        val journalPath = checkedJournalPath(directory, gate)
        val originalJournal = E.read(journalPath)
        val original = mapper.readTree(originalJournal)
        requireScope(original, gate)
        val originalReceipt = originalReceipt(directory, gate)
        verifyTools(tools)
        HarmonyOSRecoveryJournal.open(journalPath).use { journal ->
            require(journal.deviceId == deviceId && journal.ownerId == ownerId)
            require(E.digest(E.read(journalPath)) == E.digest(originalJournal)) { "Opening the journal changed its frozen bytes." }
            val id = UUID.randomUUID().toString()
            val parent = ownerDirectory(directory, deviceId, ownerId).resolve("maintenance")
            Files.createDirectories(parent); E.regularAncestors(parent)
            val root = parent.resolve(id); Files.createDirectory(root)
            val evidence = HarmonyOsMaintenanceEvidence(root)
            val hostBefore = observe(evidence)
            val checkpoint = MaintenanceCheckpoint(1, id, deviceId, ownerId, original["generation"].asText(), now(),
                evidence.saveBytes("original-gate", E.read(gatePath(directory, deviceId))),
                evidence.saveBytes("original-journal", originalJournal), evidence.saveBytes("original-receipt", originalReceipt),
                hostBefore, tools.toSortedMap())
            val reference = evidence.save("checkpoint", checkpoint)
            val step = MaintenanceStep(1, reference.sha256, 0, null, "prepared", now(), checkpoint.originalJournal,
                emptyList(), null, null, null, null, null, emptyList(), null)
            verifyTools(tools)
            val head = evidence.save("step", step)
            gateWriter(directory, gate.copy(maintenance = MaintenanceGate(id, reference, head), requiresRecovery = true,
                updatedAt = now(), uncertainReason = "Explicit maintenance requires host restart followed by device restart."))
            MaintenanceOutcome(id, deviceId, ownerId, step.phase, false, reference, head, root.toString())
        }
    }

    fun resume(deviceId: String, ownerId: String): MaintenanceOutcome = locked(deviceId, ownerId) { directory, gate ->
        val state = load(directory, gate, gateWriter); requireSettled(state); verifyFrozen(state)
        if (state.step.phase in setOf("phone_prepared", "receipt_written", "complete")) return@locked state.outcome()
        require(state.step.phase in setOf("prepared", "host_verified")) { "Partial maintenance cannot be automatically resumed." }
        HarmonyOSRecoveryJournal.open(checkedJournalPath(directory, gate)).use { journal ->
            state.verifyCurrentJournal()
            if (state.step.phase == "prepared") {
                val host = observe(state.evidence)
                requireHostRestart(state, host)
                state.checkpoint.transition?.let {
                    val migrated = mapper.treeToValue(state.evidence.json(it)["hostObserved"], MaintenanceReference::class.java)
                    HarmonyOsMaintenanceHostBoot.requireSameBoot(state.host(migrated), state.host(host))
                }
                state.publish(state.step.copy(phase = "host_verified", hostResume = host))
            } else {
                require(state.step.calls.isEmpty()) { "A previous phone observation is not confirmed." }
                val current = observe(state.evidence)
                HarmonyOsMaintenanceHostBoot.requireSameBoot(state.host(requireNotNull(state.step.hostResume)), state.host(current))
            }
            val transport = HarmonyOSMaintenanceTransport(journal, hdcFactory())
            val phone = transport.observeUptime(Duration.ofSeconds(5), state::beforeDispatch)
            val observation = state.phoneEvidence(phone)
            state.finishCall(observation)
            verifyFrozen(state)
            state.publish(state.step.copy(phase = "phone_prepared", phoneBefore = observation))
        }
        state.outcome()
    }

    fun complete(deviceId: String, ownerId: String, timeout: Duration = Duration.ofSeconds(30)): MaintenanceOutcome =
        locked(deviceId, ownerId) { directory, gate ->
            require(timeout > Duration.ZERO && timeout <= Duration.ofMinutes(1))
            val state = load(directory, gate, gateWriter); requireSettled(state); verifyFrozen(state)
            if (state.step.phase == "complete") return@locked state.outcome(completedOutcome(directory, gate))
            require(state.step.phase in setOf("phone_prepared", "cleaned", "receipt_written")) {
                "The phone baseline is absent, or a partial maintenance call needs diagnosis."
            }
            HarmonyOSRecoveryJournal.open(checkedJournalPath(directory, gate)).use { journal ->
                state.verifyCurrentJournal()
                if (state.step.phase == "phone_prepared") {
                    require(state.step.calls.count { it.phase == "host_verified" } == 1 &&
                        state.step.calls.all { it.phase in setOf("host_verified", "phone_prepared") })
                    val host = observe(state.evidence)
                    HarmonyOsMaintenanceHostBoot.requireSameBoot(state.host(requireNotNull(state.step.hostResume)), state.host(host))
                    val deadline = System.nanoTime() + timeout.toNanos()
                    fun remaining(): Duration {
                        val left = deadline - System.nanoTime()
                        if (left <= 0) throw HdcTimeoutException(timeout)
                        return Duration.ofNanos(left)
                    }
                    state.publish(state.step.copy(hostComplete = host))
                    val transport = HarmonyOSMaintenanceTransport(journal, hdcFactory())
                    val phone = transport.observeUptime(minOf(Duration.ofSeconds(5), remaining()), state::beforeDispatch)
                    val observation = state.phoneEvidence(phone)
                    state.finishCall(observation)
                    HarmonyOSUptimeObservation.requireRestarted(state.phone(requireNotNull(state.step.phoneBefore)), phone)
                    val termination = state.evidence.save("termination", mapOf("schemaVersion" to 1,
                        "deviceId" to deviceId, "ownerId" to ownerId, "checkpointSha256" to state.gate.maintenance!!.checkpoint.sha256,
                        "hostBefore" to state.checkpoint.hostBefore, "hostResume" to state.step.hostResume,
                        "hostComplete" to host, "phoneBefore" to state.step.phoneBefore, "phoneAfter" to observation,
                        "disposition" to "terminated_by_ordered_host_device_restart", "originalResult" to "unknown",
                        "operationIds" to pendingIds(state.original)))
                    state.publish(state.step.copy(phase = "terminated", phoneAfter = observation, termination = termination))
                    state.publish(state.step.copy(phase = "cleaning"))
                    transport.cleanupOwnedPaths(remaining(), state::beforeDispatch) { batch ->
                        val evidence = state.evidence.save("cleanup-batch", batch)
                        state.finishCall(evidence)
                        state.publish(state.step.copy(cleanupBatches = state.step.cleanupBatches + evidence))
                    }
                    validateCleanup(state)
                    verifyFrozen(state)
                    state.publish(state.step.copy(phase = "cleaned"))
                }
                if (state.step.phase == "cleaned") writeFinalReceipt(state)
            }
            // Closing the journal must finish before a gate can advertise a handoff.
            verifyFrozen(state)
            val receipt = validateReceipt(state)
            state.publish(state.step.copy(phase = "complete"), clean = receipt)
            state.outcome(receipt)
        }

    private fun observe(evidence: HarmonyOsMaintenanceEvidence): MaintenanceReference {
        verifyTools(tools)
        val path = evidence.root.resolve("host-observation-${UUID.randomUUID()}.json")
        val returned = observeHost(path)
        val bytes = E.read(path, 64 * 1024)
        require(mapper.readTree(bytes) == returned) { "Host observation differs from its persisted original." }
        HarmonyOsMaintenanceHostBoot.parse(returned, tools.getValue("powershell"), tools.getValue("script"))
        verifyTools(tools)
        return MaintenanceReference(path.fileName.toString(), E.digest(bytes))
    }

    private fun verifyFrozen(state: State) {
        require(tools == state.checkpoint.tools) { "Maintenance tools differ from prepare." }
        verifyTools(tools)
    }

    private fun <T> locked(deviceId: String, ownerId: String, block: (Path, LeaseGate) -> T): T {
        E.uuid(ownerId)
        require(deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")))
        return HarmonyOsSessionSupport.withDeviceLock(deviceId, lockDirectory) {
            val directory = lockDirectory.toAbsolutePath().normalize()
            val gate = requireNotNull(readGate(directory, deviceId)) { "No durable owner is present." }
            require(gate.cleanupFence == null && gate.retainedFences.isEmpty()) {
                "Retained cleanup fences cannot be removed by reboot maintenance."
            }
            require(gate.ownerId == ownerId) { "Maintenance cannot take over another owner." }
            try { block(directory, gate) } catch (error: Exception) {
                throw AgentProtocolException(AgentError("environment", "MAINTENANCE_RECOVERY_BLOCKED",
                    "Maintenance evidence is incomplete or incompatible; the device remains gated.",
                    mapOf("deviceId" to deviceId, "recoverySessionId" to ownerId))).also { it.initCause(error) }
            }
        }
    }

    private class State(val directory: Path, var gate: LeaseGate, val evidence: HarmonyOsMaintenanceEvidence,
                        val checkpoint: MaintenanceCheckpoint, var step: MaintenanceStep,
                        private val gateWriter: (Path, LeaseGate) -> Unit) {
        val original: JsonNode = evidence.json(checkpoint.originalJournal)
        fun journalSnapshot(): MaintenanceReference = evidence.saveBytes("journal", E.read(checkedJournalPath(directory, gate)))
        fun verifyCurrentJournal() {
            val current = E.read(checkedJournalPath(directory, gate))
            require(E.digest(current) == step.journal.sha256) { "Journal changed outside its recorded maintenance call." }
            verifyJournal(original, mapper.readTree(current), step.calls, allowLastPending = false)
        }
        fun host(reference: MaintenanceReference): HarmonyOsMaintenanceHostBoot.Observation {
            val transition = checkpoint.transition
            val expected = if (transition != null && reference == checkpoint.hostBefore) {
                val prior = mapper.treeToValue(evidence.json(transition)["previousCheckpoint"], MaintenanceReference::class.java)
                readCheckpoint(evidence, prior).tools
            } else checkpoint.tools
            return HarmonyOsMaintenanceHostBoot.parse(evidence.json(reference), expected.getValue("powershell"), expected.getValue("script"))
        }
        fun phone(reference: MaintenanceReference): HarmonyOSUptimeObservation = parsePhone(evidence.json(reference), gate)
        fun publish(next: MaintenanceStep, clean: RecoveryOutcome? = null) {
            val linked = next.copy(revision = step.revision + 1, previous = gate.maintenance!!.head, observedAt = now())
            val head = evidence.save("step", linked)
            val updated = gate.copy(maintenance = gate.maintenance!!.copy(head = head), updatedAt = now(),
                state = if (clean == null) "quarantined" else "clean", requiresRecovery = clean == null,
                uncertainReason = if (clean == null) gate.uncertainReason else null,
                reportPath = clean?.reportPath?.toString() ?: gate.reportPath, reportSha256 = clean?.sha256 ?: gate.reportSha256)
            gateWriter(directory, updated)
            gate = updated; step = linked
        }
        fun beforeDispatch(call: MaintenanceCall) {
            require(step.calls.none { it.outcome == null || it.endedJournal == null }) { "An earlier maintenance call is unconfirmed." }
            require((step.phase in setOf("host_verified", "phone_prepared") && call.purpose == "observe_uptime") ||
                (step.phase == "cleaning" && call.purpose == "cleanup_owned_paths" && step.termination != null))
            E.uuid(call.hostCallId); require(call.argumentsSha256.matches(Regex("[0-9a-f]{64}")))
            val registered = journalSnapshot()
            val binding = MaintenanceCallEvidence(call.hostCallId, step.phase, call.purpose, call.argumentsSha256, registered, null, null)
            verifyJournal(original, evidence.json(registered), step.calls + binding, allowLastPending = true)
            publish(step.copy(journal = registered, calls = step.calls + binding))
        }
        fun finishCall(outcome: MaintenanceReference) {
            val call = step.calls.last()
            require(call.outcome == null && call.endedJournal == null)
            val ended = journalSnapshot()
            val calls = step.calls.dropLast(1) + call.copy(outcome = outcome, endedJournal = ended)
            verifyJournal(original, evidence.json(ended), calls, allowLastPending = false)
            publish(step.copy(journal = ended, calls = calls))
        }
        fun phoneEvidence(value: HarmonyOSUptimeObservation): MaintenanceReference = evidence.save("phone-observation",
            mapOf("schemaVersion" to 1, "provider" to PHONE_PROVIDER, "deviceId" to gate.deviceId, "ownerId" to gate.ownerId,
                "hostCallId" to step.calls.last().hostCallId, "raw" to value.raw, "minutes" to value.minutes,
                "lowerBoundMilliseconds" to value.lowerBoundMilliseconds,
                "upperBoundMillisecondsExclusive" to value.upperBoundMillisecondsExclusive))
        fun outcome(receipt: RecoveryOutcome? = if (gate.state == "clean") completedOutcome(directory, gate) else null) = MaintenanceOutcome(checkpoint.maintenanceId, gate.deviceId, gate.ownerId, step.phase,
            gate.state == "clean" && receipt != null, gate.maintenance!!.checkpoint, gate.maintenance!!.head, evidence.root.toString(), receipt)
    }

    companion object {
        private val E = HarmonyOsMaintenanceEvidence
        private val mapper = E.mapper
        private const val PHONE_PROVIDER = "toybox-uptime-english-floor-minute-v1"
        private fun now() = Instant.now().toString()
        private fun pendingIds(journal: JsonNode) = journal["operations"].filter { !it["completed"].booleanValue() }.map { it["id"].asText() }
        private fun readCheckpoint(evidence: HarmonyOsMaintenanceEvidence, reference: MaintenanceReference): MaintenanceCheckpoint {
            val node = evidence.json(reference)
            val version = node["schemaVersion"].asInt()
            require(version in setOf(1, 2))
            E.exactFields(node, if (version == 1) E.checkpointFields else E.checkpointFields + "transition")
            return mapper.treeToValue(node, MaintenanceCheckpoint::class.java).also {
                require((version == 1) == (it.transition == null))
            }
        }
        private fun requirePrepared(step: MaintenanceStep, checkpoint: MaintenanceReference, journal: MaintenanceReference) {
            require(step.schemaVersion == 1 && step.phase == "prepared" && step.revision == 0 && step.previous == null &&
                step.checkpointSha256 == checkpoint.sha256 && step.journal == journal && step.calls.isEmpty() &&
                step.hostResume == null && step.phoneBefore == null && step.hostComplete == null && step.phoneAfter == null &&
                step.termination == null && step.cleanupBatches.isEmpty() && step.receipt == null) { "Migration requires untouched prepare." }
        }
        private fun validateToolTransition(old: Map<String, MaintenanceTool>, next: Map<String, MaintenanceTool>) {
            require(old.getValue("powershell").path == next.getValue("powershell").path && old.getValue("powershell") != next.getValue("powershell"))
            for (key in listOf("java", "hdc", "script")) require(old.getValue(key) == next.getValue(key))
            fun jars(value: Map<String, MaintenanceTool>) = value.filterKeys { it.startsWith("classpath:") }.values.associateBy { Path.of(it.path).fileName.toString() }
            val before = jars(old); val after = jars(next)
            require(before.isNotEmpty() && before.keys == after.keys && old.size == before.size + 4 && next.size == after.size + 4)
            for ((name, value) in before) {
                val target = after.getValue(name)
                if (!name.matches(Regex("maestro-(cli|client|orchestra|orchestra-models)(-[0-9][A-Za-z0-9._+-]*)?\\.jar")))
                    require(value.sha256 == target.sha256 && value.bytes == target.bytes) { "Unrelated dependency changed." }
            }
        }
        private fun validateTransition(evidence: HarmonyOsMaintenanceEvidence, cp: MaintenanceCheckpoint, management: MaintenanceGate) {
            val node = evidence.json(requireNotNull(cp.transition))
            E.exactFields(node, setOf("schemaVersion", "reason", "previousCheckpoint", "previousHead", "previousGate", "approval", "hostObserved"))
            require(node["schemaVersion"].asInt() == 1 && node["reason"].asText() == "windows_powershell_update")
            fun reference(name: String) = mapper.treeToValue(node[name], MaintenanceReference::class.java)
            val oldRef = reference("previousCheckpoint"); require(oldRef != management.checkpoint)
            val old = readCheckpoint(evidence, oldRef); require(old.schemaVersion == 1)
            require(cp.copy(schemaVersion = 1, createdAt = old.createdAt, tools = old.tools, transition = null) == old)
            validateToolTransition(old.tools, cp.tools)
            requirePrepared(evidence.document(reference("previousHead"), MaintenanceStep::class.java, E.stepFields), oldRef, old.originalJournal)
            val previousGate = evidence.json(reference("previousGate"))
            val oldGate = mapper.treeToValue(previousGate["maintenance"], MaintenanceGate::class.java)
            require(oldGate == MaintenanceGate(cp.maintenanceId, oldRef, reference("previousHead")) &&
                previousGate["schemaVersion"].asInt() == 2 && previousGate["state"].asText() == "quarantined" &&
                previousGate["deviceId"].asText() == cp.deviceId && previousGate["ownerSessionId"].asText() == cp.ownerId)
            val approval = evidence.json(reference("approval"))
            require(approval == mapper.readTree(mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "reason" to "windows_powershell_update",
                "checkpointSha256" to oldRef.sha256, "tools" to cp.tools))))
            val before = HarmonyOsMaintenanceHostBoot.parse(evidence.json(old.hostBefore), old.tools.getValue("powershell"), old.tools.getValue("script"))
            val current = HarmonyOsMaintenanceHostBoot.parse(evidence.json(reference("hostObserved")), cp.tools.getValue("powershell"), cp.tools.getValue("script"))
            HarmonyOsMaintenanceHostBoot.requireRestartedAfterToolMigration(before, current)
        }
        private fun verifyTools(tools: Map<String, MaintenanceTool>) {
            require(tools.keys.containsAll(setOf("powershell", "script", "hdc", "java")) && tools.keys.any { it.startsWith("classpath:") })
            require(tools.size <= 1000 && tools.values.map { it.path }.distinct().size == tools.size)
            tools.values.forEach { require(E.fingerprint(Path.of(it.path)) == it) { "A frozen maintenance tool changed." } }
            val jars = tools.filterKeys { it.startsWith("classpath:") }.values
            val parents = jars.map { Path.of(it.path).parent }.distinct()
            require(parents.size == 1) { "Maintenance classpath must be one frozen CLI lib directory." }
            val actual = Files.list(parents.single()).use { files -> files.filter { Files.isRegularFile(it) &&
                it.fileName.toString().endsWith(".jar", ignoreCase = true) }.map { it.toString() }.toList().toSet() }
            require(actual == jars.map { it.path }.toSet()) { "CLI classpath membership changed." }
        }
        private fun maintenanceTemporaryPaths(node: JsonNode): List<String> {
            val generation = node["generation"].asText()
            E.uuid(generation)
            val root = "/data/local/tmp/maestro-session-$generation"
            require(node["remoteRoot"].asText() == root && node["temporaryFiles"].isArray)
            val pattern = Regex(Regex.escape(root) + "/files/(?:layout-[0-9a-f-]{36}\\.json|screen-[0-9a-f-]{36}\\.png)")
            val paths = node["temporaryFiles"].map {
                require(it.isTextual && pattern.matches(it.asText())) { "Unsupported maintenance temporary file." }
                it.asText()
            }
            require(paths.distinct().size == paths.size)
            return paths
        }
        private fun requireScope(node: JsonNode, gate: LeaseGate) {
            require(node["version"].asInt() == 1 && node["deviceId"].asText() == gate.deviceId && node["ownerId"].asText() == gate.ownerId)
            require(node["sealed"].isBoolean && node["sealed"].booleanValue())
            maintenanceTemporaryPaths(node)
            require(node["operations"].isArray && node["operations"].all { it["completed"].booleanValue() || it["recoverable"].booleanValue() })
            require(pendingIds(node).isNotEmpty()) { "Maintenance is only for pending finite commands." }
            require(node["hostCalls"].isArray && node["hostCalls"].all { it["returned"].isBoolean && it["returned"].booleanValue() && it["stopped"].isBoolean && it["stopped"].booleanValue() })
            require(gate.closeFailure == null && !gate.unresolvedRecording &&
                Files.notExists(Path.of(gate.journalPath).resolveSibling("recording-resources.json"), LinkOption.NOFOLLOW_LINKS))
        }
        private fun originalReceipt(directory: Path, gate: LeaseGate): ByteArray {
            val path = Path.of(requireNotNull(gate.reportPath)).toAbsolutePath().normalize()
            require(path.parent == ownerDirectory(directory, gate.deviceId, gate.ownerId).resolve("recoveries"))
            val bytes = E.read(path, 4 * 1024 * 1024); require(E.digest(bytes) == gate.reportSha256)
            val node = mapper.readTree(bytes)
            E.exactFields(node, setOf("schemaVersion", "recoveryId", "deviceId", "sessionId", "observedAt", "reason", "previousState",
                "previousReportPath", "previousReportSha256", "requiresRecovery", "uncertainReason", "unresolvedRecording", "closeFailure",
                "state", "operations", "pendingOperations", "temporaryFiles", "issues"))
            require(node["schemaVersion"].asInt() == 1 && node["deviceId"].asText() == gate.deviceId && node["sessionId"].asText() == gate.ownerId &&
                node["state"].asText() == "unknown" && node["unresolvedRecording"].isBoolean && !node["unresolvedRecording"].booleanValue() && node["closeFailure"].isNull)
            val journal = mapper.readTree(E.read(checkedJournalPath(directory, gate)))
            require(node["operations"].isIntegralNumber && node["operations"].asInt() == journal["operations"].size() &&
                node["pendingOperations"].isIntegralNumber && node["pendingOperations"].asInt() == pendingIds(journal).size &&
                node["temporaryFiles"].isIntegralNumber && node["temporaryFiles"].asInt() == maintenanceTemporaryPaths(journal).size)
            E.uuid(node["recoveryId"].asText()); require(path.fileName.toString() == node["recoveryId"].asText() + ".json")
            return bytes
        }
        private fun verifyJournal(original: JsonNode, current: JsonNode, calls: List<MaintenanceCallEvidence>, allowLastPending: Boolean) {
            val left = original.deepCopy<ObjectNode>().apply { remove("hostCalls") }
            val right = current.deepCopy<ObjectNode>().apply { remove("hostCalls") }
            require(left == right) { "Maintenance changed original command results or resource ownership." }
            val initial = original["hostCalls"].toList()
            val all = current["hostCalls"].toList()
            require(all.take(initial.size) == initial && all.size == initial.size + calls.size)
            require(calls.map { it.hostCallId }.distinct().size == calls.size)
            all.drop(initial.size).zip(calls).forEachIndexed { index, (call, bound) ->
                require(call["id"].asText() == bound.hostCallId)
                if (!(allowLastPending && index == calls.lastIndex)) require(call["returned"].booleanValue() && call["stopped"].booleanValue())
            }
        }
        private fun parsePhone(node: JsonNode, gate: LeaseGate): HarmonyOSUptimeObservation {
            E.exactFields(node, setOf("schemaVersion", "provider", "deviceId", "ownerId", "hostCallId", "raw", "minutes", "lowerBoundMilliseconds", "upperBoundMillisecondsExclusive"))
            require(node["schemaVersion"].asInt() == 1 && node["provider"].asText() == PHONE_PROVIDER && node["deviceId"].asText() == gate.deviceId && node["ownerId"].asText() == gate.ownerId)
            E.uuid(node["hostCallId"].asText())
            val parsed = HarmonyOSUptimeObservation.parse(node["raw"].asText())
            require(node["minutes"].asText() == parsed.minutes && node["lowerBoundMilliseconds"].asText() == parsed.lowerBoundMilliseconds &&
                node["upperBoundMillisecondsExclusive"].asText() == parsed.upperBoundMillisecondsExclusive)
            return parsed
        }
        private fun load(directory: Path, gate: LeaseGate, gateWriter: (Path, LeaseGate) -> Unit = ::writeGate): State {
            val management = requireNotNull(gate.maintenance) { "Prepare is required before maintenance can proceed." }
            val evidence = HarmonyOsMaintenanceEvidence(ownerDirectory(directory, gate.deviceId, gate.ownerId).resolve("maintenance").resolve(management.maintenanceId))
            val checkpoint = readCheckpoint(evidence, management.checkpoint)
            require(checkpoint.maintenanceId == management.maintenanceId && checkpoint.deviceId == gate.deviceId && checkpoint.ownerId == gate.ownerId)
            if (checkpoint.schemaVersion == 2) validateTransition(evidence, checkpoint, management)
            val step = evidence.document(management.head, MaintenanceStep::class.java, E.stepFields)
            val state = State(directory, gate, evidence, checkpoint, step, gateWriter)
            requireScope(state.original, gate)
            require(checkpoint.generation == state.original["generation"].asText())
            require(E.digest(originalReceipt(directory, gate.copy(reportPath = evidence.json(checkpoint.originalGate)["reportPath"].asText(),
                reportSha256 = evidence.json(checkpoint.originalGate)["reportSha256"].asText()))) == checkpoint.originalReceipt.sha256)
            evidence.bytes(checkpoint.originalGate); evidence.bytes(checkpoint.originalReceipt)
            state.host(checkpoint.hostBefore)
            var cursor = step; var seen = 0
            while (true) {
                require(++seen <= 10000 && cursor.schemaVersion == 1 && cursor.checkpointSha256 == management.checkpoint.sha256 && cursor.phase in E.phases)
                evidence.bytes(cursor.journal)
                if (cursor.previous == null) { require(cursor.revision == 0 && cursor.phase == "prepared"); break }
                val previous = evidence.document(requireNotNull(cursor.previous), MaintenanceStep::class.java, E.stepFields)
                require(cursor.revision == previous.revision + 1 && E.phases.indexOf(cursor.phase) >= E.phases.indexOf(previous.phase))
                cursor = previous
            }
            step.calls.forEachIndexed { index, call ->
                E.uuid(call.hostCallId)
                require(call.phase in setOf("host_verified", "phone_prepared", "cleaning") && call.argumentsSha256.matches(Regex("[0-9a-f]{64}")))
                verifyJournal(state.original, evidence.json(call.registeredJournal), step.calls.take(index + 1), true)
                require((call.outcome == null) == (call.endedJournal == null))
                if (call.endedJournal != null) {
                    verifyJournal(state.original, evidence.json(call.endedJournal), step.calls.take(index + 1), false)
                    evidence.bytes(requireNotNull(call.outcome))
                }
            }
            state.verifyCurrentJournal()
            return state
        }
        private fun requireSettled(state: State) {
            require(state.step.calls.all { it.outcome != null && it.endedJournal != null }) { "A dispatched maintenance call has no durable outcome." }
        }
        private fun validateTermination(state: State) {
            val step = state.step
            val hostResume = requireNotNull(step.hostResume)
            requireHostRestart(state, hostResume)
            HarmonyOsMaintenanceHostBoot.requireSameBoot(state.host(hostResume), state.host(requireNotNull(step.hostComplete)))
            state.checkpoint.transition?.let {
                val migrated = mapper.treeToValue(state.evidence.json(it)["hostObserved"], MaintenanceReference::class.java)
                HarmonyOsMaintenanceHostBoot.requireSameBoot(state.host(migrated), state.host(hostResume))
            }
            HarmonyOSUptimeObservation.requireRestarted(state.phone(requireNotNull(step.phoneBefore)), state.phone(requireNotNull(step.phoneAfter)))
            val expected = mapper.valueToTree<JsonNode>(mapOf("schemaVersion" to 1, "deviceId" to state.gate.deviceId,
                "ownerId" to state.gate.ownerId, "checkpointSha256" to state.gate.maintenance!!.checkpoint.sha256,
                "hostBefore" to state.checkpoint.hostBefore, "hostResume" to step.hostResume, "hostComplete" to step.hostComplete,
                "phoneBefore" to step.phoneBefore, "phoneAfter" to step.phoneAfter,
                "disposition" to "terminated_by_ordered_host_device_restart", "originalResult" to "unknown", "operationIds" to pendingIds(state.original)))
            require(state.evidence.json(requireNotNull(step.termination)) == expected)
            for ((phase, reference) in listOf("host_verified" to step.phoneBefore, "phone_prepared" to step.phoneAfter)) {
                val call = state.step.calls.single { it.phase == phase && it.outcome == reference }
                require(call.purpose == "observe_uptime" && call.outcome == reference &&
                    state.evidence.json(requireNotNull(reference))["hostCallId"].asText() == call.hostCallId &&
                    call.argumentsSha256 == E.digest(mapper.writeValueAsBytes(listOf("uptime"))))
            }
        }
        private fun requireHostRestart(state: State, reference: MaintenanceReference) {
            val before = state.host(state.checkpoint.hostBefore); val after = state.host(reference)
            if (state.checkpoint.transition == null) HarmonyOsMaintenanceHostBoot.requireRestarted(before, after)
            else HarmonyOsMaintenanceHostBoot.requireRestartedAfterToolMigration(before, after)
        }
        private fun validateCleanup(state: State) {
            validateTermination(state)
            val root = state.original["remoteRoot"].asText()
            val expected = maintenanceTemporaryPaths(state.original) + state.original["operations"].flatMap { val path = "$root/${it["id"].asText()}"; listOf("$path/done", "$path/done.pending", path) } + listOf("$root/files", root)
            val batches = state.step.cleanupBatches.mapIndexed { index, reference ->
                val node = state.evidence.json(reference); E.exactFields(node, setOf("index", "paths", "hostCallId"))
                val batch = mapper.treeToValue(node, MaintenanceCleanupBatch::class.java)
                require(batch.index == index)
                val call = state.step.calls.single { it.hostCallId == batch.hostCallId }
                require(call.phase == "cleaning" && call.purpose == "cleanup_owned_paths" && call.outcome == reference && call.endedJournal != null)
                batch
            }
            require(batches.flatMap { it.paths } == expected && batches.size == state.step.calls.count { it.phase == "cleaning" })
        }
        private fun receiptPath(state: State): Path = ownerDirectory(state.directory, state.gate.deviceId, state.gate.ownerId)
            .resolve("recoveries").resolve("${state.checkpoint.maintenanceId}.json")
        private fun receiptPayload(state: State, cleanedHead: MaintenanceReference): Map<String, Any?> = linkedMapOf(
            "schemaVersion" to 3, "recoveryId" to state.checkpoint.maintenanceId, "deviceId" to state.gate.deviceId,
            "observedAt" to state.evidence.json(cleanedHead)["observedAt"].asText(),
            "sessionId" to state.gate.ownerId, "reason" to "ordered_restart_maintenance", "state" to "complete",
            "originalExecutionResult" to "unknown", "operations" to state.original["operations"].size(),
            "originalPendingOperations" to pendingIds(state.original).size, "pendingOperations" to 0, "temporaryFiles" to 0,
            "issues" to emptyList<Any>(), "maintenanceId" to state.checkpoint.maintenanceId,
            "checkpoint" to state.gate.maintenance!!.checkpoint, "evidenceHead" to cleanedHead,
            "termination" to state.step.termination, "originalJournal" to state.checkpoint.originalJournal,
            "finalJournal" to state.step.journal, "originalReceipt" to state.checkpoint.originalReceipt,
            "cleanupBatches" to state.step.cleanupBatches)
        private fun writeFinalReceipt(state: State) {
            requireSettled(state); validateCleanup(state)
            val path = receiptPath(state)
            val payload = receiptPayload(state, state.gate.maintenance!!.head)
            val bytes = mapper.writeValueAsBytes(payload)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) require(E.read(path).contentEquals(bytes)) { "A different maintenance receipt already exists." }
            else writeLeaseFile(path, bytes, replace = false)
            // The canonical receipt location permits recovery of a failed following gate write.
            val reference = state.evidence.saveBytes("receipt", bytes)
            state.publish(state.step.copy(phase = "receipt_written", receipt = reference))
        }
        private fun validateReceipt(state: State): RecoveryOutcome {
            requireSettled(state); validateCleanup(state)
            val reference = requireNotNull(state.step.receipt)
            val node = state.evidence.json(reference)
            val cleanedRef = mapper.treeToValue(node["evidenceHead"], MaintenanceReference::class.java)
            val cleaned = state.evidence.document(cleanedRef, MaintenanceStep::class.java, E.stepFields)
            require(cleaned.phase == "cleaned" && cleaned.journal == state.step.journal && cleaned.calls == state.step.calls &&
                cleaned.cleanupBatches == state.step.cleanupBatches && cleaned.termination == state.step.termination)
            require(node == mapper.valueToTree<JsonNode>(receiptPayload(state, cleanedRef)))
            val path = receiptPath(state); val bytes = E.read(path)
            require(E.digest(bytes) == reference.sha256 && mapper.readTree(bytes) == node)
            @Suppress("UNCHECKED_CAST") val report = mapper.convertValue(node, Map::class.java) as Map<String, Any?>
            return RecoveryOutcome(state.gate.deviceId, state.gate.ownerId, true, path, reference.sha256, report)
        }
        fun completedOutcome(directory: Path, gate: LeaseGate): RecoveryOutcome {
            require(gate.state == "clean" && !gate.requiresRecovery)
            val state = load(directory, gate)
            require(state.step.phase == "complete")
            return validateReceipt(state).also { require(gate.reportPath == it.reportPath.toString() && gate.reportSha256 == it.sha256) }
        }
    }
}
