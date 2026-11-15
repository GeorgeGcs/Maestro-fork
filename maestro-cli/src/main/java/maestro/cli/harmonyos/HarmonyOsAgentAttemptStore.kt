package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

private val agentMapper = jacksonObjectMapper()
private val activeAttempts = ConcurrentHashMap<Path, AgentAttempt>()
private val registrationLocks = ConcurrentHashMap<Path, Any>()
private val terminalStatuses = setOf("passed", "failed", "blocked", "cancelled", "unknown")

/** Durable registration is separate from execution, so a disconnected client cannot replay an action. */
internal class HarmonyOsAgentAttemptStore(root: Path) {
    private val root = root.toAbsolutePath().normalize().resolve("runs")

    init {
        Files.createDirectories(this.root)
        require(this.root.toRealPath() == this.root) { "Agent artifacts root must not contain symbolic links." }
    }

    fun register(runId: String, attemptId: String, attemptNo: Int, sessionId: String?, deviceId: String,
        inputKind: String, input: JsonNode): AgentAttempt {
        HarmonyOsAgentProtocol.uuid(runId, "runId")
        HarmonyOsAgentProtocol.uuid(attemptId, "attemptId")
        sessionId?.let { HarmonyOsAgentProtocol.uuid(it, "sessionId") }
        if (attemptNo < 1 || inputKind !in setOf("action", "flow") || deviceId.isBlank()) {
            fail("INVALID_PARAMS", "attemptNo, input kind or deviceId is invalid.")
        }
        val runDirectory = root.resolve(runId)
        Files.createDirectories(runDirectory)
        require(runDirectory.toRealPath() == runDirectory) { "Run directory must not be a symbolic link." }
        val canonical = canonical(agentMapper.valueToTree(mapOf("kind" to inputKind, "deviceId" to deviceId, "request" to input)))
        val inputBytes = agentMapper.writeValueAsBytes(canonical)
        val hash = sha256(inputBytes)
        synchronized(registrationLocks.computeIfAbsent(root) { Any() }) {
            FileChannel.open(root.resolve("registration.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val directory = runDirectory.resolve(attemptId)
                    Files.newDirectoryStream(root).use { runs -> runs.forEach { other ->
                        if (other != runDirectory && Files.isDirectory(other, LinkOption.NOFOLLOW_LINKS) &&
                            Files.exists(other.resolve(attemptId), LinkOption.NOFOLLOW_LINKS)) {
                            fail("ATTEMPT_CONFLICT", "An attemptId cannot be reused under another runId.")
                        }
                    } }
                    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                        val registration = directory.resolve("registration.json")
                        val priorHash = if (Files.isRegularFile(registration, LinkOption.NOFOLLOW_LINKS)) {
                            agentMapper.readTree(registration.toFile()).path("sha256").asText()
                        } else null
                        fail(if (priorHash == hash) "ATTEMPT_ALREADY_EXISTS" else "ATTEMPT_CONFLICT",
                            "This attemptId is already registered; query its result or use a new attemptId.")
                    }
                    var largestAttempt = 0
                    Files.newDirectoryStream(runDirectory).use { paths -> paths.forEach { path ->
                        val registration = path.resolve("registration.json")
                        if (Files.isRegularFile(registration, LinkOption.NOFOLLOW_LINKS)) {
                            val previous = agentMapper.readTree(registration.toFile())
                            largestAttempt = maxOf(largestAttempt, previous.path("attemptNo").asInt())
                            if (previous.path("sha256").asText() != hash) fail("ATTEMPT_CONFLICT", "A runId must keep the same execution input.")
                        }
                    } }
                    if (attemptNo <= largestAttempt) fail("STALE_ATTEMPT", "attemptNo must increase within a run.")
                    Files.createDirectory(directory)
                    writeAtomic(directory.resolve("input.json"), inputBytes)
                    val owner = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                    val lock = owner.lock()
                    val data = linkedMapOf<String, Any?>(
                        "runId" to runId, "attemptId" to attemptId, "attemptNo" to attemptNo, "sessionId" to sessionId,
                        "deviceId" to deviceId, "input" to mapOf("kind" to inputKind, "path" to "input.json", "sha256" to hash),
                        "flow" to null, "status" to "pending", "startedAt" to null, "endedAt" to null,
                        "effects" to "none", "error" to null, "failedStepId" to null,
                        "cleanup" to mapOf("state" to "not_started", "errors" to emptyList<Any>()),
                        "artifacts" to linkedMapOf("baseDir" to directory.toString(), "manifestPaths" to mutableListOf("agent-manifest.json"),
                            "junitPath" to null, "evidenceIndexPath" to "evidence-index.json"),
                    )
                    writeJson(directory.resolve("registration.json"), mapOf("sha256" to hash, "attemptNo" to attemptNo, "data" to data))
                    return AgentAttempt(directory, data, mutableListOf(), owner, lock).also {
                        activeAttempts[directory] = it
                        try { it.persist() } catch (error: Throwable) { it.closeOwner(); throw error }
                    }
                }
            }
        }
    }

    fun result(runId: String, attemptId: String): Map<String, Any?> = lookup(runId, attemptId).result()
    fun evidence(runId: String, attemptId: String): Map<String, Any?> = lookup(runId, attemptId).evidence()

    private fun lookup(runId: String, attemptId: String): AgentAttempt {
        HarmonyOsAgentProtocol.uuid(runId, "runId")
        HarmonyOsAgentProtocol.uuid(attemptId, "attemptId")
        val directory = root.resolve(runId).resolve(attemptId)
        activeAttempts[directory]?.let { return it }
        if (!Files.isRegularFile(directory.resolve("registration.json"), LinkOption.NOFOLLOW_LINKS)) {
            fail("NOT_FOUND", "No result exists for this run and attempt.")
        }
        require(directory.toRealPath() == directory) { "Attempt directory must not contain symbolic links." }
        synchronized(registrationLocks.computeIfAbsent(directory) { Any() }) {
            activeAttempts[directory]?.let { return it }
            val owner = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { owner.tryLock() } catch (_: OverlappingFileLockException) { null }
            // A single atomic checkpoint ties the two public documents to one revision, even
            // if another process is reading while a command publishes new evidence.
            val checkpoint = directory.resolve("state.json")
            val state = if (Files.isRegularFile(checkpoint)) agentMapper.readTree(checkpoint.toFile()) else null
            val document = state?.path("result")
            val data = if (document != null) mutableObject(document.path("data"))
                else mutableObject(agentMapper.readTree(directory.resolve("registration.json").toFile()).path("data"))
            val entries = state?.path("evidence")?.path("data")?.path("entries")?.map { mutableObject(it) }?.toMutableList()
                ?: mutableListOf()
            val attempt = AgentAttempt(directory, data, entries, if (lock == null) null else owner, lock,
                document?.path("revision")?.asLong() ?: 0)
            if (lock == null) owner.close()
            else if (!attempt.isTerminal) {
                attempt.setCleanup("unknown")
                attempt.finishWithError(AgentError("transport", "EXECUTOR_LOST", "The executor ended without a terminal result."), "unknown")
            } else attempt.closeOwner()
            return attempt
        }
    }
}

internal class AgentAttempt internal constructor(
    val directory: Path,
    private val data: MutableMap<String, Any?>,
    private val entries: MutableList<MutableMap<String, Any?>>,
    private val owner: FileChannel?,
    private val ownerLock: FileLock?,
    private var revision: Long = 0,
) {
    val runId: String get() = data.getValue("runId") as String
    val attemptId: String get() = data.getValue("attemptId") as String
    val isTerminal: Boolean @Synchronized get() = data["status"] in terminalStatuses
    val status: String @Synchronized get() = data.getValue("status") as String
    val effects: String @Synchronized get() = data.getValue("effects") as String
    val hasStarted: Boolean @Synchronized get() = data["startedAt"] != null
    @Volatile var isCancellationRequested: Boolean = false
        private set
    @Volatile var cancellationRequestedAtMillis: Long? = null
        private set
    private var cancellationHandler: (() -> Unit)? = null
    private var sessionCleanupConfirmed = false

    @Synchronized fun result(): Map<String, Any?> = snapshot(HarmonyOsAgentProtocol.document("runResult", revision, data))
    @Synchronized fun evidence(): Map<String, Any?> = snapshot(HarmonyOsAgentProtocol.document("evidenceIndex", revision,
        mapOf("runId" to runId, "attemptId" to attemptId, "entries" to entries)))

    fun requestCancel(): Boolean {
        val handler = synchronized(this) {
            if (isTerminal || isCancellationRequested) return false
            cancellationRequestedAtMillis = System.currentTimeMillis()
            isCancellationRequested = true
            data["status"] = "cancelling"
            persist()
            cancellationHandler
        }
        // The executing coroutine may have moved its blocking device call to Dispatchers.IO.
        // Cancelling its Job reaches that call; interrupting only the owner thread does not.
        handler?.invoke()
        return true
    }

    fun bindCancellation(handler: () -> Unit): AutoCloseable {
        val alreadyRequested = synchronized(this) {
            check(cancellationHandler == null) { "This attempt already has an execution cancellation handler." }
            cancellationHandler = handler
            isCancellationRequested
        }
        if (alreadyRequested) handler()
        return AutoCloseable {
            synchronized(this) { if (cancellationHandler === handler) cancellationHandler = null }
        }
    }

    fun checkCancelled() {
        if (isCancellationRequested || Thread.currentThread().isInterrupted) throw CancellationException("Attempt cancellation requested.")
    }

    @Synchronized fun markRunning() {
        checkCancelled()
        check(!isTerminal) { "The attempt is already terminal." }
        data["status"] = "running"
        if (data["startedAt"] == null) data["startedAt"] = Instant.now().toString()
        data["cleanup"] = mapOf("state" to "in_progress", "errors" to emptyList<Any>())
        sessionCleanupConfirmed = false
        persist()
    }

    @Synchronized fun setDeviceId(deviceId: String) { require(deviceId.isNotBlank()); data["deviceId"] = deviceId; persist() }

    @Synchronized fun setFlow(path: Path) {
        val target = directory.resolve("flow.yaml")
        if (path.toAbsolutePath().normalize() != target) writeAtomic(target, path.toFile().readBytes())
        data["flow"] = mapOf("path" to "flow.yaml", "sha256" to sha256(target.toFile().readBytes()))
        persist()
    }

    @Synchronized fun setCleanup(state: String, errors: List<AgentError> = emptyList()) {
        require(state in setOf("not_started", "in_progress", "complete", "failed", "unknown"))
        require((state != "complete" || errors.isEmpty()) && (state != "failed" || errors.isNotEmpty()))
        data["cleanup"] = mapOf("state" to state, "errors" to errors.map { it.toMap() })
        sessionCleanupConfirmed = false
        persist()
    }

    /** Only the CLI's normal session-return boundary can confirm final resource cleanup. */
    @Synchronized internal fun confirmSessionCleanup() {
        setCleanup("complete")
        sessionCleanupConfirmed = true
    }

    @Synchronized fun startStep(flow: String?, index: Int, name: String, mutating: Boolean): String {
        checkCancelled()
        if (data["startedAt"] == null) markRunning()
        val stepId = UUID.randomUUID().toString()
        if (mutating) data["effects"] = "possible"
        val source = flow?.let { mapOf("flow" to it, "commandIndex" to index, "commandName" to name) }
        entries += linkedMapOf("stepId" to stepId, "source" to source,
            "status" to "unknown", "error" to null, "evidence" to mutableListOf<Map<String, Any?>>())
        persist()
        return stepId
    }

    @Synchronized fun finishStep(stepId: String, status: String, error: AgentError? = null) {
        require(status in setOf("passed", "failed", "blocked", "cancelled", "unknown", "skipped"))
        val entry = entries.first { it["stepId"] == stepId }
        entry["status"] = status
        entry["error"] = error?.toMap()
        if (error != null) data["failedStepId"] = stepId
        persist()
    }

    @Synchronized fun recordFile(stepId: String?, kind: String, path: Path, phase: String, capturedAt: Instant = Instant.now()) {
        val relative = relative(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            recordUnavailable(stepId, kind, "missing", phase)
            return
        }
        val hash = try { sha256(path.toFile().readBytes()) } catch (error: java.io.IOException) {
            recordUnavailable(stepId, kind, "capture_failed", phase, HarmonyOsAgentProtocol.error(error))
            return
        }
        appendEvidence(stepId, mapOf("kind" to kind, "state" to "available", "path" to relative,
            "sha256" to hash, "capturedAt" to capturedAt.toString(), "phase" to phase, "error" to null))
    }

    @Synchronized fun recordUnavailable(stepId: String?, kind: String, state: String, phase: String, error: AgentError? = null) {
        require(state == "missing" || (state == "capture_failed" && error != null))
        appendEvidence(stepId, mapOf("kind" to kind, "state" to state, "path" to null, "sha256" to null,
            "capturedAt" to null, "phase" to phase, "error" to error?.toMap()))
    }

    @Synchronized fun recordManifest(path: Path) {
        @Suppress("UNCHECKED_CAST") val manifests = artifacts()["manifestPaths"] as MutableList<String>
        val relative = relative(path)
        if (relative !in manifests) manifests += relative
        persist()
    }

    @Synchronized fun setJUnit(path: Path) { artifacts()["junitPath"] = relative(path); persist() }

    @Synchronized fun finishSuccess() {
        if (isTerminal) return
        if (isCancellationRequested) { finishCancelled(); return }
        data["status"] = "passed"
        data["error"] = null
        data["failedStepId"] = null
        finish()
    }

    @Synchronized fun finishFailure(error: Throwable) {
        if (isTerminal) return
        var classified = HarmonyOsAgentProtocol.error(error)
        if (error.suppressed.isNotEmpty()) {
            val additional = error.suppressed.map { HarmonyOsAgentProtocol.error(it) }
            val cleanup = data["cleanup"] as Map<*, *>
            if (sessionCleanupConfirmed) {
                // These errors preceded confirmed session teardown. Keep them diagnostic,
                // without replacing its final cleanup outcome or the execution failure.
                classified = classified.copy(details = classified.details.orEmpty() +
                    ("suppressedErrors" to additional.map { it.toMap() }))
            } else if (cleanup["state"] == "unknown") {
                val existing = (cleanup["errors"] as List<*>).filterIsInstance<Map<*, *>>()
                data["cleanup"] = mapOf("state" to "unknown",
                    "errors" to (existing + additional.map { it.toMap() }).distinct())
            } else setCleanup("failed", additional)
        }
        finishWithError(classified)
    }

    @Synchronized fun finishCancelled() = finishWithError(AgentError("cancelled", "CANCELLED", "Attempt cancellation completed."))

    @Synchronized fun finishWithError(error: AgentError, status: String? = null) {
        if (isTerminal) return
        val target = status ?: when {
            error.kind == "cancelled" -> "cancelled"
            data["startedAt"] == null -> "blocked"
            error.kind in setOf("timeout", "transport") && effects != "none" -> "unknown"
            else -> "failed"
        }
        require(target in terminalStatuses && target != "passed")
        data["status"] = target
        data["error"] = error.toMap()
        if (target == "blocked") {
            data["startedAt"] = null; data["effects"] = "none"; data["failedStepId"] = null
        }
        if (data["failedStepId"] == null) {
            val stage = entries.firstOrNull { it["stepId"] == null } ?: linkedMapOf<String, Any?>(
                "stepId" to null, "source" to null, "evidence" to mutableListOf<Map<String, Any?>>(),
            ).also { entries += it }
            stage["status"] = target
            stage["error"] = error.toMap()
            @Suppress("UNCHECKED_CAST") val evidence = stage["evidence"] as MutableList<Map<String, Any?>>
            if (evidence.isEmpty()) evidence += mapOf("kind" to "log", "state" to "missing", "path" to null,
                "sha256" to null, "capturedAt" to null, "phase" to "failure", "error" to null)
        }
        if (!sessionCleanupConfirmed && error.kind in setOf("cancelled", "timeout", "transport") && data["startedAt"] != null) {
            val cleanup = data["cleanup"] as Map<*, *>
            if (cleanup["state"] !in setOf("failed", "unknown")) {
                data["cleanup"] = mapOf("state" to "unknown", "errors" to emptyList<Any>())
            }
        }
        finish()
    }

    private fun finish() {
        data["endedAt"] = Instant.now().toString()
        val cleanup = data["cleanup"] as Map<*, *>
        if (cleanup["state"] in setOf("in_progress", "not_started")) {
            data["cleanup"] = mapOf("state" to "complete", "errors" to emptyList<Any>())
        }
        persist()
        closeOwner()
        activeAttempts.remove(directory, this)
    }

    internal fun closeOwner() {
        runCatching { ownerLock?.release() }
        runCatching { owner?.close() }
        activeAttempts.remove(directory, this)
    }

    @Synchronized internal fun persist() {
        val interrupted = Thread.interrupted()
        try {
            revision++
            val result = result()
            val evidence = evidence()
            writeJson(directory.resolve("state.json"), mapOf("result" to result, "evidence" to evidence))
            writeJson(directory.resolve("evidence-index.json"), evidence)
            writeJson(directory.resolve("result.json"), result)
            val files = (listOf("input.json", "result.json", "evidence-index.json", "state.json", "registration.json") +
                (if (data["flow"] != null) listOf("flow.yaml") else emptyList()) +
                listOfNotNull(artifacts()["junitPath"] as? String) +
                (artifacts()["manifestPaths"] as List<*>).filterIsInstance<String>().filter { it != "agent-manifest.json" })
                .distinct().filter { Files.isRegularFile(directory.resolve(it)) }
            writeJson(directory.resolve("agent-manifest.json"), mapOf(
                "schemaVersion" to 1, "runId" to runId, "attemptId" to attemptId,
                "files" to files.map { mapOf("path" to it, "sha256" to sha256(directory.resolve(it).toFile().readBytes())) },
            ))
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    @Suppress("UNCHECKED_CAST") private fun artifacts() = data["artifacts"] as MutableMap<String, Any?>

    private fun appendEvidence(stepId: String?, evidence: Map<String, Any?>) {
        val entry = entries.firstOrNull { it["stepId"] == stepId } ?: linkedMapOf<String, Any?>(
            "stepId" to stepId, "source" to null, "status" to if (isTerminal) status else "unknown",
            "error" to null, "evidence" to mutableListOf<Map<String, Any?>>(),
        ).also { entries += it }
        @Suppress("UNCHECKED_CAST") val collection = entry["evidence"] as MutableList<Map<String, Any?>>
        collection += evidence
        persist()
    }

    private fun relative(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        require(absolute.startsWith(directory) && absolute != directory) { "Evidence must stay inside this attempt." }
        if (Files.exists(absolute)) require(absolute.toRealPath().startsWith(directory.toRealPath())) { "Evidence link escapes its attempt." }
        return directory.relativize(absolute).joinToString("/")
    }
}

private fun fail(code: String, message: String): Nothing = throw AgentProtocolException(AgentError("invalid_input", code, message))
private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun canonical(node: JsonNode): JsonNode = when {
    node.isObject -> agentMapper.createObjectNode().also { output -> node.fieldNames().asSequence().sorted().forEach { output.set<JsonNode>(it, canonical(node[it])) } }
    node.isArray -> agentMapper.createArrayNode().also { output -> node.forEach { output.add(canonical(it)) } }
    else -> node
}
@Suppress("UNCHECKED_CAST") private fun mutableObject(node: JsonNode): MutableMap<String, Any?> = agentMapper.convertValue(node, MutableMap::class.java) as MutableMap<String, Any?>
private fun snapshot(value: Map<String, Any?>): Map<String, Any?> = mutableObject(agentMapper.valueToTree(value))
private fun writeJson(path: Path, value: Any) = writeAtomic(path, agentMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value))
private fun writeAtomic(path: Path, bytes: ByteArray) {
    // Cancellation may arrive during the write, not only before it. File streams avoid
    // InterruptibleChannel closing a half-written checkpoint when the worker is interrupted.
    val interrupted = Thread.interrupted()
    val staging = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.pending")
    try {
        Files.createFile(staging)
        FileOutputStream(staging.toFile()).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(staging)
        if (interrupted) Thread.currentThread().interrupt()
    }
}
