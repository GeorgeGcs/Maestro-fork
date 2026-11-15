package maestro.cli.session

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.harmonyos.AgentError
import maestro.cli.harmonyos.AgentProtocolException
import maestro.cli.harmonyos.HarmonyOsAgentProtocol
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.harmonyos.hdc.HarmonyOSRecordingResourceJournal
import maestro.harmonyos.hdc.RecordingResourceFailure
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.RecoveryIssue
import maestro.harmonyos.hdc.RecoveryReport
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** OS locks serialize callers; this durable gate also survives the owner process disappearing. */
internal class HarmonyOsSessionLease private constructor(
    val journal: HarmonyOSRecoveryJournal,
    private val directory: Path,
    private var gate: LeaseGate,
) {
    private var finished = false

    @Synchronized
    private fun noteUncertainCleanup(reason: String) {
        check(!finished) { "The device lease has already finished." }
        gate = gate.copy(requiresRecovery = true, uncertainReason = reason.take(4096), updatedAt = Instant.now().toString())
        writeGate(directory, gate)
    }

    @Synchronized
    fun finish(closeFailure: Throwable?, hdc: HdcClient) {
        if (finished) return
        var failure: Throwable? = null
        try {
            finishOwned(closeFailure, hdc)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            finished = true
            activeLeases.remove(gate.ownerId, this)
            try { journal.close() } catch (error: Throwable) {
                if (failure == null) throw error
                else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
    }

    private fun finishOwned(closeFailure: Throwable?, hdc: HdcClient) {
        var report: RecoveryReport? = null
        var failure: Throwable? = null
        val closeError = closeFailure?.let { recordingCloseError(it, journal.recordings) }
        if (closeError != null) {
            // Persist independently before cleanup, so a host crash cannot erase this blocker.
            gate = gate.copy(closeFailure = closeError.toMap(), updatedAt = Instant.now().toString())
            writeGate(directory, gate)
        } else if (gate.unresolvedRecording && journal.recordings?.isComplete() == true) {
            gate = gate.copy(unresolvedRecording = false, updatedAt = Instant.now().toString())
            writeGate(directory, gate)
        }
        try {
            journal.seal()
            // Multi-step flows leave several bounded batches of owned proof directories.
            // Share the explicit recovery budget; timeout still requires a separate recovery.
            report = if (gate.requiresRecovery || gate.unresolvedRecording) journal.snapshot() else
                journal.recover(hdc, Duration.ofSeconds(30), hostProcessGrace = Duration.ofSeconds(10))
        } catch (error: Throwable) {
            failure = error
        } finally {
            try { journal.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        val observed = report ?: RecoveryReport("unknown", 0, 0, 0,
            listOf(RecoveryIssue("RECOVERY_JOURNAL_UNAVAILABLE", "The resource journal could not confirm cleanup.")))
        val issues = observed.issues.toMutableList()
        if (gate.requiresRecovery) issues += RecoveryIssue("EXPLICIT_RECOVERY_REQUIRED",
            gate.uncertainReason ?: "The caller reported uncertain cleanup; an explicit recovery check is required.")
        if (closeError != null) issues += RecoveryIssue("DRIVER_CLEANUP_FAILED", closeError.message)
        if (gate.unresolvedRecording) issues += RecoveryIssue("RECORDING_CLEANUP_UNCONFIRMED", "Recording resources require explicit recovery.")
        if (failure != null) issues += RecoveryIssue("RECOVERY_JOURNAL_UNAVAILABLE", failure!!.message ?: failure!!.javaClass.simpleName)
        val effective = observed.copy(state = if (observed.state == "complete" && issues.isEmpty()) "complete" else "unknown",
            issues = issues.distinct())
        val updated = gate
        val outcome = writeReceipt(directory, updated, effective, "session_close")
        writeGate(directory, updated.copy(state = if (outcome.complete) "clean" else "quarantined",
            reportPath = outcome.reportPath.toString(), reportSha256 = outcome.sha256, updatedAt = Instant.now().toString()))
        if (!outcome.complete) {
            val blocked = recoveryRequired(updated, "The session ended without confirmed resource cleanup.")
            failure?.takeIf { it !== blocked }?.let(blocked::addSuppressed)
            throw blocked
        }
    }

    companion object {
        private val activeLeases = ConcurrentHashMap<String, HarmonyOsSessionLease>()

        fun noteUncertainCleanup(ownerId: String, reason: String) {
            HarmonyOsAgentProtocol.uuid(ownerId, "sessionId")
            val lease = activeLeases[ownerId] ?: throw AgentProtocolException(AgentError(
                "environment", "SESSION_LEASE_UNAVAILABLE", "The session has no active local device lease."))
            lease.noteUncertainCleanup(reason)
        }

        /** Must be called while holding the matching device OS lock, before any device operation. */
        @JvmOverloads
        fun begin(deviceId: String, ownerId: String, lockDirectory: Path,
            requiresRecordingCleanup: Boolean = false, hdc: HdcClient = HdcClient()): HarmonyOsSessionLease {
            HarmonyOsAgentProtocol.uuid(ownerId, "ownerId")
            validateDevice(deviceId)
            val directory = lockDirectory.toAbsolutePath().normalize()
            require(directory.toRealPath() == directory) { "Device lease directory must not contain symbolic links." }
            val previousGate = readGate(directory, deviceId)
            previousGate?.let { prior ->
                if (prior.state != "clean" || runCatching { existingReceipt(directory, prior).complete }.getOrDefault(false).not()) {
                    throw recoveryRequired(prior, "A previous session requires recovery before this device can be used.")
                }
                HarmonyOsCleanupFenceRecovery.verifyRetained(directory, prior, hdc)
            }
            val ownerDirectory = ownerDirectory(directory, deviceId, ownerId)
            Files.createDirectories(ownerDirectory.parent)
            require(ownerDirectory.parent.toRealPath() == ownerDirectory.parent) { "Device owner directories cannot follow links." }
            if (Files.exists(ownerDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw AgentProtocolException(AgentError("invalid_input", "SESSION_ALREADY_EXISTS", "A session owner identifier cannot be reused."))
            }
            Files.createDirectory(ownerDirectory)
            val journal = HarmonyOSRecoveryJournal.create(ownerDirectory.resolve("recovery-journal.json"), deviceId, ownerId)
            try {
                if (requiresRecordingCleanup) journal.enableRecordingResources()
            } catch (error: Throwable) {
                try { journal.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
                throw error
            }
            val gate = LeaseGate(deviceId, ownerId, "active", journal.path.toString(), Instant.now().toString(),
                unresolvedRecording = requiresRecordingCleanup, retainedFences = previousGate?.retainedFences.orEmpty())
            try { writeGate(directory, gate) } catch (error: Throwable) {
                try { journal.close() } catch (closeError: Throwable) { error.addSuppressed(closeError) }
                throw error
            }
            val lease = HarmonyOsSessionLease(journal, directory, gate)
            if (activeLeases.putIfAbsent(ownerId, lease) != null) {
                journal.close()
                throw AgentProtocolException(AgentError("invalid_input", "SESSION_ALREADY_EXISTS", "The session owner is already active."))
            }
            return lease
        }

        /** Read-only observation. Malformed metadata must never make a device look available. */
        fun requiresRecovery(deviceId: String, lockDirectory: Path): Boolean {
            val directory = lockDirectory.toAbsolutePath().normalize()
            val gate = readGate(directory, deviceId) ?: return false
            HarmonyOsCleanupFenceRecovery.verifyRetained(directory, gate)
            return gate.state != "clean" || !existingReceipt(directory, gate).complete
        }
    }
}

internal data class RecoveryOutcome(
    val deviceId: String,
    val sessionId: String,
    val complete: Boolean,
    val reportPath: Path,
    val sha256: String,
    val report: Map<String, Any?>,
)

internal object HarmonyOsRecoverySupport {
    /** Called before the owning session block returns, while its device OS lock is held. */
    fun noteUncertainCleanup(ownerId: String, reason: String = "The caller could not confirm session cleanup.") =
        HarmonyOsSessionLease.noteUncertainCleanup(ownerId, reason)

    /** All recovery is serialized against normal CLI and bridge sessions using their same lock. */
    fun recover(
        deviceId: String,
        ownerId: String,
        hdc: HdcClient = HdcClient(),
        lockDirectory: Path = HarmonyOsSessionSupport.defaultLockDirectory(),
        timeout: Duration = Duration.ofSeconds(30),
    ): RecoveryOutcome {
        validateDevice(deviceId)
        HarmonyOsAgentProtocol.uuid(ownerId, "sessionId")
        require(timeout > Duration.ZERO && timeout <= Duration.ofSeconds(30)) { "Recovery timeout must be within thirty seconds." }
        return HarmonyOsSessionSupport.withDeviceLock(deviceId, lockDirectory) {
            val directory = lockDirectory.toAbsolutePath().normalize()
            val gate = readGate(directory, deviceId) ?: throw AgentProtocolException(AgentError(
                "invalid_input", "NOT_FOUND", "No durable session record exists for this device."))
            if (gate.ownerId != ownerId) throw AgentProtocolException(AgentError(
                "invalid_input", "RECOVERY_SESSION_MISMATCH", "The requested session is not the current durable device owner.",
                mapOf("recoverySessionId" to gate.ownerId)))
            if (gate.maintenance != null) throw recoveryRequired(gate,
                "This owner is in maintenance recovery. Use the maintenance entry point; ordinary recovery cannot change it.")
            if (gate.cleanupFence != null) throw recoveryRequired(gate,
                "This owner uses cleanup-fence recovery. Use harmonyos-cleanup-recovery; keep its retained directories.")
            HarmonyOsCleanupFenceRecovery.verifyRetained(directory, gate, hdc)
            if (gate.state == "clean") return@withDeviceLock existingReceipt(directory, gate)
            val journalPath = checkedJournalPath(directory, gate)
            val journal = try { HarmonyOSRecoveryJournal.open(journalPath) } catch (error: Exception) {
                throw invalidMetadata("The recovery journal could not be opened; the device gate was retained.", gate.ownerId, error)
            }
            val deadline = System.nanoTime() + timeout.toNanos()
            fun remaining(): Duration {
                val left = deadline - System.nanoTime()
                if (left <= 0) throw HdcTimeoutException(timeout)
                return Duration.ofNanos(left)
            }
            var resolvedGate = gate
            val observed = journal.use {
                if (journal.deviceId != deviceId || journal.ownerId != ownerId) {
                    throw invalidMetadata("The resource journal does not belong to the gated device session.", ownerId)
                }
                try {
                    val readiness = journal.recover(hdc, remaining(), cleanupResources = false)
                    val resources = journal.recordings
                    if (readiness.issues.any { it.code != "OWNED_RESOURCES_REMAIN" }) return@use readiness
                    val recordingIssues = resources?.recover(hdc, journal, remaining(),
                        rejectedStartPermission(gate.closeFailure)).orEmpty().toMutableList()
                    if (resources != null && resources.isComplete() && !resources.verifyArchives(remaining())) {
                        recordingIssues += RecoveryIssue("RECORDING_ARCHIVE_UNCONFIRMED", "A preserved recording original is absent or changed.")
                    }
                    if (recordingIssues.isNotEmpty()) return@use journal.snapshot().let { current ->
                        current.copy(state = "unknown", issues = (current.issues + recordingIssues).distinct())
                    }
                    if (resources?.isComplete() == true) {
                        val closeResolved = resolvedRecordingFailure(gate.closeFailure, resources)
                        resolvedGate = gate.copy(unresolvedRecording = false,
                            closeFailure = if (closeResolved) null else gate.closeFailure)
                    }
                    journal.recover(hdc, remaining())
                } catch (error: HdcTimeoutException) {
                    // A deadline can expire between phases, before the next journal call can record it.
                    val current = journal.snapshot()
                    current.copy(state = "unknown", issues = (current.issues + RecoveryIssue(
                        "RECOVERY_QUERY_FAILED", "The shared recovery deadline expired; resource release remains unconfirmed.")).distinct())
                }
            }
            val issues = observed.issues.toMutableList()
            // A command completion record cannot resolve an unrelated driver/recording close failure.
            resolvedGate.closeFailure?.let { issues += RecoveryIssue("DRIVER_CLEANUP_FAILED",
                it["message"] as? String ?: "The previous driver cleanup failure has no confirming recovery evidence.") }
            if (resolvedGate.unresolvedRecording) issues += RecoveryIssue("RECORDING_CLEANUP_UNCONFIRMED",
                "The previous owner enabled screen recording and never confirmed its driver cleanup.")
            val report = observed.copy(state = if (observed.state == "complete" && issues.isEmpty()) "complete" else "unknown",
                issues = issues.distinct())
            val outcome = writeReceipt(directory, resolvedGate, report, "recovery")
            // Receipt first, gate second: interruption can leave a block, never a false handoff.
            writeGate(directory, resolvedGate.copy(state = if (outcome.complete) "clean" else "quarantined",
                requiresRecovery = !outcome.complete && gate.requiresRecovery,
                uncertainReason = if (outcome.complete) null else gate.uncertainReason,
                reportPath = outcome.reportPath.toString(), reportSha256 = outcome.sha256, updatedAt = Instant.now().toString()))
            outcome
        }
    }
}

private fun recordingCloseError(error: Throwable, resources: HarmonyOSRecordingResourceJournal?): AgentError {
    val fingerprint = HarmonyOSRecordingResourceJournal.failureFingerprint(error)
    val marker = error.suppressed.filterIsInstance<RecordingResourceFailure>().lastOrNull {
        it.fingerprint == fingerprint && resources?.ownsFailure(it.recordingId, it.fingerprint) == true
    }
    if (marker != null) {
        return AgentError("environment", "RECORDING_RESOURCE_CLEANUP_FAILED", error.message ?: error.javaClass.simpleName,
            mapOf("recordingId" to marker.recordingId, "failureFingerprint" to marker.fingerprint))
    }
    return HarmonyOsAgentProtocol.error(error)
}

private fun resolvedRecordingFailure(failure: Map<String, Any?>?, resources: HarmonyOSRecordingResourceJournal): Boolean {
    if (failure == null) return true
    if (failure["code"] != "RECORDING_RESOURCE_CLEANUP_FAILED") return false
    val details = failure["details"] as? Map<*, *> ?: return false
    val id = details["recordingId"] as? String ?: return false
    val fingerprint = details["failureFingerprint"] as? String ?: return false
    return resources.resolvesFailure(id, fingerprint)
}

internal fun rejectedStartPermission(failure: Map<String, Any?>?): HarmonyOSRecordingResourceJournal.RejectedStartPermission? {
    if (failure?.get("code") != "RECORDING_RESOURCE_CLEANUP_FAILED") return null
    val message = failure["message"] as? String ?: return null
    if (!message.contains("error: failed to start ability.") ||
        !message.contains("Error Code:10106102  Error Message:The device screen is locked during the application launch")) return null
    val details = failure["details"] as? Map<*, *> ?: return null
    val id = details["recordingId"] as? String ?: return null
    val fingerprint = details["failureFingerprint"] as? String ?: return null
    if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
    return HarmonyOSRecordingResourceJournal.RejectedStartPermission(id, fingerprint, 10106102)
}

internal data class LeaseGate(
    val deviceId: String,
    val ownerId: String,
    val state: String,
    val journalPath: String,
    val updatedAt: String,
    val closeFailure: Map<String, Any?>? = null,
    val reportPath: String? = null,
    val reportSha256: String? = null,
    val requiresRecovery: Boolean = false,
    val uncertainReason: String? = null,
    val unresolvedRecording: Boolean = false,
    val maintenance: MaintenanceGate? = null,
    val cleanupFence: CleanupFenceGate? = null,
    val retainedFences: List<RetainedCleanupFence> = emptyList(),
)

private val leaseMapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

private fun validateDevice(deviceId: String) {
    if (!deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")) || deviceId.length > 256) {
        throw AgentProtocolException(AgentError("invalid_input", "INVALID_PARAMS", "Invalid explicit device identifier."))
    }
}

internal fun gatePath(directory: Path, deviceId: String): Path = directory.resolve("${HarmonyOsSessionSupport.lockName(deviceId)}.owner.json")
internal fun ownerDirectory(directory: Path, deviceId: String, ownerId: String): Path =
    directory.resolve(HarmonyOsSessionSupport.lockName(deviceId)).resolve(ownerId)

internal fun readGate(directory: Path, deviceId: String): LeaseGate? {
    val path = gatePath(directory, deviceId)
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.exists(directory.resolve(HarmonyOsSessionSupport.lockName(deviceId)), LinkOption.NOFOLLOW_LINKS)) {
            throw invalidMetadata("The device has session records but its durable gate is missing.", null)
        }
        return null
    }
    try {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 1024 * 1024)
        require(path.toRealPath() == path)
        val node = leaseMapper.readTree(path.toFile())
        require(node.isObject && node.path("schemaVersion").isIntegralNumber && node.path("schemaVersion").intValue() in 1..3)
        val maintenanceVersion = node["schemaVersion"].intValue() == 2
        val fenceVersion = node["schemaVersion"].intValue() == 3
        require(node.fieldNames().asSequence().toSet() == setOf("schemaVersion", "deviceId", "ownerSessionId", "state", "journalPath",
            "updatedAt", "closeFailure", "reportPath", "reportSha256", "requiresRecovery", "uncertainReason", "unresolvedRecording") +
            (if (maintenanceVersion) setOf("maintenance") else if (fenceVersion) setOf("cleanupFence", "retainedFences") else emptySet<String>()))
        fun text(name: String): String = node.get(name).also { require(it != null && it.isTextual && it.asText().isNotBlank()) }.asText()
        require(text("deviceId") == deviceId)
        val ownerId = HarmonyOsAgentProtocol.uuid(text("ownerSessionId"), "ownerSessionId")
        val state = text("state").also { require(it in setOf("active", "quarantined", "clean")) }
        val time = text("updatedAt").also { Instant.parse(it) }
        val closeFailure = node["closeFailure"].takeUnless(JsonNode::isNull)?.let {
            require(it.isObject && it.path("kind").isTextual && it.path("code").isTextual && it.path("message").isTextual)
            @Suppress("UNCHECKED_CAST")
            (leaseMapper.convertValue(it, Map::class.java) as Map<String, Any?>)
        }
        val reportPath = node["reportPath"].takeUnless(JsonNode::isNull)?.let { require(it.isTextual); it.asText() }
        val reportHash = node["reportSha256"].takeUnless(JsonNode::isNull)?.let {
            require(it.isTextual && it.asText().matches(Regex("[0-9a-f]{64}"))); it.asText()
        }
        require((reportPath == null) == (reportHash == null))
        require(node["requiresRecovery"].isBoolean)
        val requiresRecovery = node["requiresRecovery"].booleanValue()
        val uncertainReason = node["uncertainReason"].takeUnless(JsonNode::isNull)?.let { require(it.isTextual); it.asText() }
        require(node["unresolvedRecording"].isBoolean)
        val unresolvedRecording = node["unresolvedRecording"].booleanValue()
        require(state != "clean" || (!requiresRecovery && closeFailure == null && !unresolvedRecording))
        val maintenance = if (maintenanceVersion) {
            require(state != "active" && closeFailure == null && !unresolvedRecording)
            val value = node["maintenance"]
            HarmonyOsMaintenanceEvidence.exactFields(value, setOf("maintenanceId", "checkpoint", "head"))
            for (name in listOf("checkpoint", "head")) {
                val reference = value[name]
                HarmonyOsMaintenanceEvidence.exactFields(reference, setOf("path", "sha256"))
                require(reference["path"].isTextual && reference["sha256"].isTextual &&
                    reference["path"].asText().matches(Regex("[a-z][a-z-]{0,40}-[0-9a-f-]{36}\\.json")) &&
                    reference["sha256"].asText().matches(Regex("[0-9a-f]{64}")))
            }
            require(value["maintenanceId"].isTextual)
            HarmonyOsMaintenanceEvidence.uuid(value["maintenanceId"].asText())
            leaseMapper.treeToValue(value, MaintenanceGate::class.java)
        } else null
        val fence = if (fenceVersion && !node["cleanupFence"].isNull) {
            require(state != "active" && closeFailure == null && !unresolvedRecording)
            leaseMapper.treeToValue(node["cleanupFence"], CleanupFenceGate::class.java).also {
                HarmonyOsMaintenanceEvidence.exactFields(node["cleanupFence"], setOf("recoveryId", "checkpoint", "head"))
                HarmonyOsMaintenanceEvidence.uuid(it.recoveryId)
            }
        } else null
        val retained = if (fenceVersion) {
            require(node["retainedFences"].isArray && node["retainedFences"].size() <= 8)
            node["retainedFences"].map { value ->
                HarmonyOsMaintenanceEvidence.exactFields(value, setOf("ownerId", "recoveryId", "checkpoint", "receipt"))
                leaseMapper.treeToValue(value, RetainedCleanupFence::class.java).also {
                    HarmonyOsMaintenanceEvidence.uuid(it.ownerId); HarmonyOsMaintenanceEvidence.uuid(it.recoveryId)
                }
            }.also { require(it.isNotEmpty() || fence != null) }
        } else emptyList()
        return LeaseGate(deviceId, ownerId, state, text("journalPath"), time, closeFailure, reportPath, reportHash,
            requiresRecovery, uncertainReason, unresolvedRecording, maintenance, fence, retained)
            .also { checkedJournalPath(directory, it) }
    } catch (error: Exception) {
        throw invalidMetadata("The durable device gate is missing required or trustworthy metadata.", null, error)
    }
}

internal fun checkedJournalPath(directory: Path, gate: LeaseGate): Path {
    val expected = ownerDirectory(directory, gate.deviceId, gate.ownerId).resolve("recovery-journal.json")
    require(Path.of(gate.journalPath).toAbsolutePath().normalize() == expected) { "Unexpected journal location." }
    require(expected.parent.toRealPath() == expected.parent && !Files.isSymbolicLink(expected)) { "Recovery journal cannot follow links." }
    return expected
}

internal fun writeGate(directory: Path, gate: LeaseGate) {
    val fenced = gate.cleanupFence != null || gate.retainedFences.isNotEmpty()
    require(!fenced || gate.maintenance == null)
    val payload = mapOf("schemaVersion" to if (fenced) 3 else if (gate.maintenance == null) 1 else 2, "deviceId" to gate.deviceId, "ownerSessionId" to gate.ownerId,
        "state" to gate.state, "journalPath" to gate.journalPath, "updatedAt" to gate.updatedAt,
        "closeFailure" to gate.closeFailure, "reportPath" to gate.reportPath, "reportSha256" to gate.reportSha256,
        "requiresRecovery" to gate.requiresRecovery, "uncertainReason" to gate.uncertainReason,
        "unresolvedRecording" to gate.unresolvedRecording) +
        (if (fenced) mapOf("cleanupFence" to gate.cleanupFence, "retainedFences" to gate.retainedFences)
        else if (gate.maintenance == null) emptyMap() else mapOf("maintenance" to gate.maintenance))
    writeLeaseFile(gatePath(directory, gate.deviceId), leaseMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload))
}

private fun writeReceipt(directory: Path, gate: LeaseGate, recovery: RecoveryReport, reason: String): RecoveryOutcome {
    val receipts = ownerDirectory(directory, gate.deviceId, gate.ownerId).resolve("recoveries")
    Files.createDirectories(receipts)
    require(receipts.toRealPath() == receipts) { "Recovery receipts cannot follow links." }
    val id = UUID.randomUUID().toString()
    val recordingPath = ownerDirectory(directory, gate.deviceId, gate.ownerId).resolve("recording-resources.json")
    val recordingEvidence = if (Files.exists(recordingPath, LinkOption.NOFOLLOW_LINKS)) {
        require(Files.isRegularFile(recordingPath, LinkOption.NOFOLLOW_LINKS) && recordingPath.toRealPath() == recordingPath)
        val bytes = Files.readAllBytes(recordingPath)
        val snapshot = receipts.resolve("$id-recordings.json")
        writeLeaseFile(snapshot, bytes, replace = false)
        mapOf("path" to snapshot.toString(), "sha256" to digest(bytes))
    } else null
    val report = linkedMapOf<String, Any?>("schemaVersion" to if (recordingEvidence == null) 1 else 2, "recoveryId" to id, "deviceId" to gate.deviceId,
        "sessionId" to gate.ownerId, "observedAt" to Instant.now().toString(), "reason" to reason,
        "previousState" to gate.state, "previousReportPath" to gate.reportPath, "previousReportSha256" to gate.reportSha256,
        "requiresRecovery" to gate.requiresRecovery, "uncertainReason" to gate.uncertainReason,
        "unresolvedRecording" to gate.unresolvedRecording,
        "closeFailure" to gate.closeFailure).apply {
            putAll(recovery.toMap())
            if (recordingEvidence != null) put("recordingResources", recordingEvidence)
        }
    val path = receipts.resolve("$id.json")
    val bytes = leaseMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report)
    writeLeaseFile(path, bytes, replace = false)
    return RecoveryOutcome(gate.deviceId, gate.ownerId, recovery.state == "complete", path, digest(bytes), report)
}

private fun existingReceipt(directory: Path, gate: LeaseGate): RecoveryOutcome {
    if (gate.cleanupFence != null) return HarmonyOsCleanupFenceRecovery.completedOutcome(directory, gate)
    if (gate.maintenance != null) return HarmonyOsMaintenanceRecovery.completedOutcome(directory, gate)
    try {
        val receipts = ownerDirectory(directory, gate.deviceId, gate.ownerId).resolve("recoveries")
        val path = Path.of(requireNotNull(gate.reportPath)).toAbsolutePath().normalize()
        require(path.parent == receipts && path.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.json")))
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path && Files.size(path) <= 4 * 1024 * 1024)
        val bytes = path.toFile().readBytes()
        require(digest(bytes) == gate.reportSha256)
        val node = leaseMapper.readTree(bytes)
        val receiptVersion = node.path("schemaVersion").let { require(it.isIntegralNumber && it.canConvertToInt()); it.intValue() }
        require(receiptVersion in 1..2)
        require(node.isObject && node.fieldNames().asSequence().toSet() == setOf("schemaVersion", "recoveryId", "deviceId",
            "sessionId", "observedAt", "reason", "previousState", "previousReportPath", "previousReportSha256",
            "requiresRecovery", "uncertainReason", "unresolvedRecording", "closeFailure", "state", "operations",
            "pendingOperations", "temporaryFiles", "issues") + if (receiptVersion == 2) setOf("recordingResources") else emptySet<String>())
        fun text(name: String): String = node[name].also { require(it.isTextual) }.asText()
        fun counter(name: String): Int = node[name].also {
            require(it.isIntegralNumber && it.canConvertToInt() && it.intValue() >= 0)
        }.intValue()
        require(text("deviceId") == gate.deviceId &&
            text("sessionId") == gate.ownerId && text("state") == "complete")
        val receiptId = HarmonyOsAgentProtocol.uuid(text("recoveryId"), "recoveryId")
        require(path.fileName.toString() == "$receiptId.json")
        if (receiptVersion == 2) {
            val reference = node["recordingResources"]
            require(reference.isObject && reference.fieldNames().asSequence().toSet() == setOf("path", "sha256") &&
                reference["path"].isTextual && reference["sha256"].isTextual)
            val snapshot = Path.of(reference["path"].asText()).toAbsolutePath().normalize()
            require(snapshot == receipts.resolve("$receiptId-recordings.json") && snapshot.toRealPath() == snapshot &&
                Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS) && Files.size(snapshot) <= 4 * 1024 * 1024)
            val snapshotBytes = Files.readAllBytes(snapshot)
            require(digest(snapshotBytes) == reference["sha256"].asText())
            val resources = leaseMapper.readTree(snapshotBytes)
            require(resources.path("deviceId").asText() == gate.deviceId && resources.path("ownerId").asText() == gate.ownerId &&
                resources.path("resources").isArray && resources["resources"].all { it.path("clean").isBoolean && it["clean"].booleanValue() })
        }
        Instant.parse(text("observedAt"))
        require(text("reason") in setOf("session_close", "recovery"))
        require(text("previousState") in setOf("active", "quarantined", "clean"))
        counter("operations")
        require(counter("pendingOperations") == 0 && counter("temporaryFiles") == 0)
        require(node["requiresRecovery"].isBoolean && node["unresolvedRecording"].isBoolean &&
            !node["unresolvedRecording"].booleanValue() && node["closeFailure"].isNull)
        require(node["uncertainReason"].isNull || node["uncertainReason"].isTextual)
        val previousPath = node["previousReportPath"]
        val previousHash = node["previousReportSha256"]
        require(previousPath.isNull == previousHash.isNull)
        if (!previousPath.isNull) {
            require(previousPath.isTextual && previousHash.isTextual && previousHash.asText().matches(Regex("[0-9a-f]{64}")))
            val previous = Path.of(previousPath.asText()).toAbsolutePath().normalize()
            require(previous.parent == receipts && previous != path && previous.fileName.toString().matches(Regex("[0-9a-f-]{36}\\.json")))
        }
        require(node.path("issues").isArray && node.path("issues").isEmpty)
        @Suppress("UNCHECKED_CAST") val report = leaseMapper.convertValue(node, Map::class.java) as Map<String, Any?>
        return RecoveryOutcome(gate.deviceId, gate.ownerId, true, path, requireNotNull(gate.reportSha256), report)
    } catch (error: Exception) {
        throw invalidMetadata("The completed device gate has no valid immutable cleanup receipt.", gate.ownerId, error)
    }
}

private fun recoveryRequired(gate: LeaseGate, message: String) = AgentProtocolException(AgentError(
    "environment", "DEVICE_RECOVERY_REQUIRED", message, mapOf("deviceId" to gate.deviceId, "recoverySessionId" to gate.ownerId)))

private fun invalidMetadata(message: String, ownerId: String?, cause: Throwable? = null) = AgentProtocolException(AgentError(
    "environment", "RECOVERY_METADATA_INVALID", message, ownerId?.let { mapOf("recoverySessionId" to it) },
)).also { if (cause != null) it.initCause(cause) }

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun writeLeaseFile(path: Path, bytes: ByteArray, replace: Boolean = true) {
    require(path.parent.toRealPath() == path.parent && !Files.isSymbolicLink(path)) { "Lease metadata cannot follow symbolic links." }
    val staging = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.pending")
    try {
        Files.createFile(staging)
        FileOutputStream(staging.toFile()).use { output -> output.write(bytes); output.fd.sync() }
        if (replace) Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE)
    } finally { Files.deleteIfExists(staging) }
}
