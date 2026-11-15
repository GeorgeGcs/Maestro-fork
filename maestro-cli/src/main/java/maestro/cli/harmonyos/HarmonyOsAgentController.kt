package maestro.cli.harmonyos

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The input reader never executes a device operation. One owner thread holds each device session. */
internal class HarmonyOsAgentController(
    private val backend: RecorderBackend,
    private val diagnostics: PrintStream,
    private val capabilities: HarmonyOsAgentCapabilities = HarmonyOsAgentCapabilities(),
    private val shutdownWaitMs: Long = 5_000,
) {
    private val guard = Any()
    private val mapper = jacksonObjectMapper()
    private val sessions = linkedMapOf<String, Session>()
    private val stores = linkedMapOf<String, HarmonyOsAgentAttemptStore>()
    private var current: Session? = null
    private var stopping = false
    @Volatile private var schemaVersion = 1
    private var versionFixed = false
    private var recovering = false
    private var recoveryRevision = 0L
    @Volatile private var abandonedQueries = false
    private val queries = Executors.newSingleThreadExecutor { task ->
        Thread(task, "maestro-agent-readonly").apply { isDaemon = true }
    }

    private class Session(val id: String, val config: RecorderConfig, val store: HarmonyOsAgentAttemptStore) {
        val queue = LinkedBlockingQueue<Job>()
        lateinit var worker: Thread
        lateinit var completion: Thread
        var closeError: Exception? = null
        var opened = false
        var finished = false
        var state = "unknown"
        var lockState = "unknown"
        var cleanupState = "not_started"
        val cleanupErrors = mutableListOf<Map<String, Any?>>()
        var revision = 0L
        var active: AgentAttempt? = null
        var operation: Job? = null
        var releaseRequested = false
        var uncertainResources = false
        var cleanupPersistenceError: Exception? = null
    }

    private sealed interface Job {
        class Snapshot(val success: (Any) -> Unit, val failure: (Exception) -> Unit) : Job
        class Action(val parameters: JsonNode, val attempt: AgentAttempt) : Job
        data object Stop : Job
    }

    fun hasUnreleasedSession(): Boolean = synchronized(guard) {
        recovering || (current?.let { !handoffReady(it) } ?: false)
    }

    /** New protocol methods can complete out of request order; JSONL id correlates each response. */
    fun dispatch(method: String, parameters: JsonNode, sendSuccess: (Any) -> Unit, failure: (Exception) -> Unit) {
        val success: (Any) -> Unit = { value ->
            @Suppress("UNCHECKED_CAST")
            sendSuccess(if (value is Map<*, *> && value["protocol"] == "maestro.harmonyos.agent")
                (value as Map<String, Any?>) + ("schemaVersion" to schemaVersion) else value)
        }
        try {
            synchronized(guard) {
                if (stopping) fail("environment", "DEVICE_BUSY", "The bridge is shutting down.")
                if (method != "agent.describe") versionFixed = true
            }
            when (method) {
                "agent.describe" -> {
                    parameters.onlyFields("schemaVersion")
                    val requested = parameters.get("schemaVersion")
                    if (requested != null && (!requested.isIntegralNumber || !requested.canConvertToInt() || requested.intValue() !in 1..2)) {
                        fail("invalid_input", "UNSUPPORTED_PROTOCOL_VERSION", "Supported protocol versions are 1 and 2.")
                    }
                    synchronized(guard) {
                        val version = requested?.intValue() ?: schemaVersion
                        if (versionFixed && version != schemaVersion) fail("invalid_input", "PROTOCOL_VERSION_FIXED", "Negotiate the protocol before other agent requests.")
                        schemaVersion = version
                        versionFixed = true
                    }
                    val data = capabilities.describe().toMutableMap()
                    if (schemaVersion == 2) data["methods"] = HarmonyOsAgentCapabilities.METHODS + "agent.recover"
                    success(HarmonyOsAgentProtocol.document("capabilities", 0, data))
                }
                "agent.preflight" -> {
                    synchronized(guard) {
                        if (current?.let { !it.finished && it.state != "idle" } == true) {
                            fail("environment", "DEVICE_BUSY", "A device operation is in progress. Query session status first.")
                        }
                    }
                    queries.execute { reply(success, failure) {
                        HarmonyOsAgentProtocol.document("preflight", 0, capabilities.preflight(parameters))
                    } }
                }
                "agent.connect" -> connect(parseRecorderConfig(parameters), success, failure)
                "agent.session" -> {
                    parameters.onlyFields("sessionId")
                    success(synchronized(guard) { sessionDocument(session(parameters)) })
                }
                "agent.snapshot" -> snapshot(parameters, success, failure)
                "agent.action" -> success(action(parameters))
                "agent.cancel" -> success(cancel(parameters))
                "agent.release" -> success(release(parameters))
                "agent.recover" -> {
                    if (schemaVersion != 2) fail("unsupported", "UNKNOWN_METHOD", "Recovery requires protocol version 2.")
                    recover(parameters, success, failure)
                }
                "agent.result", "agent.evidence" -> {
                    parameters.onlyFields("runId", "attemptId")
                    val runId = HarmonyOsAgentProtocol.uuid(parameters.requiredText("runId"), "runId")
                    val attemptId = HarmonyOsAgentProtocol.uuid(parameters.requiredText("attemptId"), "attemptId")
                    queries.execute { reply(success, failure) { readAttempt(method, runId, attemptId) } }
                }
                else -> fail("unsupported", "UNKNOWN_METHOD", "Unknown agent method: $method")
            }
        } catch (error: Exception) {
            deliverFailure(failure, error)
        }
    }

    private fun connect(config: RecorderConfig, success: (Any) -> Unit, failure: (Exception) -> Unit) {
        val managed = synchronized(guard) {
            if (recovering) fail("environment", "DEVICE_BUSY", "Recovery is in progress.")
            if (current?.let { !handoffReady(it) } == true) {
                fail("environment", "DEVICE_BUSY", "The previous session has not confirmed resource release.")
            }
            val store = stores.getOrPut(config.artifactsDir.toString()) { HarmonyOsAgentAttemptStore(config.artifactsDir) }
            Session(UUID.randomUUID().toString(), config, store).also { value ->
                value.worker = Thread({ ownSession(value, success, failure) }, "maestro-agent-${value.id}").apply { isDaemon = true }
                value.completion = Thread({
                    value.worker.join()
                    finishSession(value)
                }, "maestro-agent-close-${value.id}").apply { isDaemon = true }
                sessions[value.id] = value
                current = value
            }
        }
        managed.worker.start()
        managed.completion.start()
    }

    private fun ownSession(managed: Session, success: (Any) -> Unit, failure: (Exception) -> Unit) {
        var connectAcknowledged = false
        try {
            backend.withSession(managed.config.copy(sessionId = managed.id)) { rawConnection ->
                val connection = rawConnection as? AgentRecorderConnection
                    ?: fail("unsupported", "UNSUPPORTED_COMMAND", "The recorder backend does not implement agent actions.")
                val connected = synchronized(guard) {
                    managed.opened = true
                    managed.lockState = "held"
                    managed.state = when {
                        managed.releaseRequested -> "releasing"
                        managed.uncertainResources -> "unknown"
                        else -> "idle"
                    }
                    managed.revision++
                    sessionDocument(managed)
                }
                connectAcknowledged = true
                reply(success, failure) { connected }
                workLoop(managed, connection)
                synchronized(guard) { managed.cleanupPersistenceError }?.let { throw it }
                // Clear an already handled interrupt before driver/recording/file cleanup runs.
                Thread.interrupted()
            }
            if (!managed.opened) throw IllegalStateException("The backend returned without opening a session.")
        } catch (error: Exception) {
            managed.closeError = error
            diagnostics.println(error.stackTraceToString())
        } catch (error: Throwable) {
            managed.closeError = IllegalStateException("The session worker terminated unexpectedly.", error)
            diagnostics.println(error.stackTraceToString())
        } finally {
            Thread.interrupted()
            if (!connectAcknowledged) {
                deliverFailure(failure, managed.closeError ?: IllegalStateException("Session initialization failed."))
            }
        }
    }

    /** A separate observer publishes closed only after the actual owner thread has exited. */
    private fun finishSession(managed: Session) {
        synchronized(guard) {
                managed.finished = true
                managed.active?.let { attempt ->
                    if (!attempt.isTerminal) runCatching {
                        attempt.finishFailure(managed.closeError ?: IllegalStateException("The session ended before the action completed."))
                    }
                    completedAttempts.getOrPut(managed.id) { mutableSetOf() }.add(attempt.attemptId)
                }
                managed.active = null
                managed.operation = null
                managed.state = "closed"
                val rejectedBeforeOwnership = !managed.opened && managed.closeError?.let {
                    it.suppressed.isEmpty() && HarmonyOsAgentProtocol.error(it).code in setOf("DEVICE_NOT_FOUND", "DEVICE_BUSY", "DEVICE_RECOVERY_REQUIRED")
                } == true
                if (managed.closeError == null || rejectedBeforeOwnership) {
                    // This is reached only after withSession returned, including lock release.
                    managed.lockState = "released"
                    managed.cleanupState = if (managed.uncertainResources) "unknown" else "complete"
                } else if (HarmonyOsAgentProtocol.error(managed.closeError!!).code == "DEVICE_RECOVERY_REQUIRED") {
                    managed.lockState = "released"
                    managed.cleanupState = "unknown"
                    managed.uncertainResources = true
                    managed.cleanupErrors += structuredCleanupErrors(managed.closeError!!)
                } else {
                    managed.lockState = "unknown"
                    managed.cleanupState = "failed"
                    managed.cleanupErrors += structuredCleanupErrors(managed.closeError!!)
                }
                managed.revision++
        }
    }

    private fun workLoop(managed: Session, connection: AgentRecorderConnection) {
        while (true) {
            val job = try {
                managed.queue.take()
            } catch (_: InterruptedException) {
                if (synchronized(guard) { managed.releaseRequested }) break else continue
            }
            if (job === Job.Stop) break
            synchronized(guard) { managed.operation = job }
            var completed: (() -> Unit)? = null
            try {
                when (job) {
                    is Job.Action -> executeAction(managed, connection, job)
                    is Job.Snapshot -> completed = executeSnapshot(managed, connection, job)
                    Job.Stop -> Unit
                }
            } finally {
                Thread.interrupted()
                synchronized(guard) {
                    managed.operation = null
                    managed.active?.let { completedAttempts.getOrPut(managed.id) { mutableSetOf() }.add(it.attemptId) }
                    managed.active = null
                    managed.state = when {
                        managed.releaseRequested -> "releasing"
                        managed.uncertainResources -> "unknown"
                        else -> "idle"
                    }
                    managed.revision++
                }
            }
            completed?.invoke()
            // Deliver an in-flight snapshot's response before terminating an owner whose gate
            // metadata could not be persisted. Such an owner must never return normally.
            synchronized(guard) { managed.cleanupPersistenceError }?.let { throw it }
            if (synchronized(guard) { managed.releaseRequested }) break
        }
        // A release may win before the queued operation starts. Every queued response still completes.
        while (true) {
            when (val queued = managed.queue.poll() ?: break) {
                is Job.Action -> {
                    queued.attempt.requestCancel()
                    if (!queued.attempt.isTerminal) queued.attempt.finishCancelled()
                    observeAttemptCleanup(managed, queued.attempt)
                    synchronized(guard) { completedAttempts.getOrPut(managed.id) { mutableSetOf() }.add(queued.attempt.attemptId) }
                }
                is Job.Snapshot -> deliverFailure(queued.failure,
                    AgentProtocolException(AgentError("cancelled", "CANCELLED", "The session was released before snapshot capture.")))
                Job.Stop -> Unit
            }
        }
    }

    private fun executeAction(managed: Session, connection: AgentRecorderConnection, job: Job.Action) {
        try {
            if (job.attempt.isCancellationRequested) {
                job.attempt.finishCancelled()
            } else {
                connection.executeAgentAction(job.parameters, job.attempt)
                if (!job.attempt.isTerminal) {
                    job.attempt.finishFailure(IllegalStateException("The action returned without a terminal execution result."))
                }
            }
        } catch (error: Exception) {
            Thread.interrupted()
            if (!job.attempt.isTerminal) job.attempt.finishFailure(error)
        } finally {
            observeAttemptCleanup(managed, job.attempt)
        }
    }

    private fun observeAttemptCleanup(managed: Session, attempt: AgentAttempt) {
        var data = mapper.valueToTree<JsonNode>(attempt.result()).path("data")
        // An executor may swallow an interrupt and return a normal outcome. Preserve that outcome,
        // but do not treat it as proof that an accepted cancellation released device resources.
        if (attempt.isCancellationRequested && data.hasNonNull("startedAt") && data.path("status").asText() != "cancelled" &&
            data.path("cleanup").path("state").asText() in setOf("complete", "not_started")) {
            attempt.setCleanup("unknown", listOf(AgentError("internal", "CANCELLATION_NOT_CONFIRMED",
                "The executor returned without confirming an accepted cancellation.")))
            data = mapper.valueToTree<JsonNode>(attempt.result()).path("data")
        }
        val cleanup = data.path("cleanup")
        if (cleanup.path("state").asText() !in setOf("complete", "not_started")) {
            markUncertainResources(managed, cleanup.path("errors").map { error ->
                mapper.convertValue(error, object : TypeReference<Map<String, Any?>>() {})
            })
        }
    }

    private fun markUncertainResources(managed: Session, errors: List<Map<String, Any?>>): Exception? {
        synchronized(guard) {
            managed.uncertainResources = true
            managed.cleanupState = "unknown"
            managed.cleanupErrors += errors
            managed.revision++
        }
        return try {
            backend.noteUncertainCleanup(managed.id)
            null
        } catch (error: Exception) {
            synchronized(guard) {
                if (managed.cleanupPersistenceError == null) managed.cleanupPersistenceError = error
                managed.cleanupErrors += structuredCleanupErrors(error)
                managed.revision++
            }
            error
        }
    }

    private fun executeSnapshot(managed: Session, connection: AgentRecorderConnection, job: Job.Snapshot): () -> Unit {
        var captureStarted = false
        return try {
            synchronized(guard) {
                if (managed.releaseRequested) fail("cancelled", "CANCELLED", "The session is being released.")
            }
            captureStarted = true
            val raw = connection.snapshot()
            val snapshot = mapper.convertValue(raw, object : TypeReference<Map<String, Any?>>() {}) + ("sessionId" to managed.id)
            // Reply after workLoop has restored idle, so a response-triggered action cannot race it.
            val completed: () -> Unit = {
                val revision = synchronized(guard) { managed.revision }
                reply(job.success, job.failure) { HarmonyOsAgentProtocol.document("snapshot", revision, snapshot) }
            }
            completed
        } catch (error: Exception) {
            val interrupted = error is InterruptedException || Thread.currentThread().isInterrupted
            Thread.interrupted()
            val outcomeUnconfirmed = captureStarted && HarmonyOsAgentProtocol.error(error).kind in setOf("timeout", "transport", "cancelled")
            if (interrupted || outcomeUnconfirmed || error.suppressed.isNotEmpty()) {
                markUncertainResources(managed, structuredCleanupErrors(error))
                    ?.takeIf { it !== error }?.let(error::addSuppressed)
            }
            val completed: () -> Unit = { deliverFailure(job.failure, error) }
            completed
        }
    }

    private fun snapshot(parameters: JsonNode, success: (Any) -> Unit, failure: (Exception) -> Unit) {
        parameters.onlyFields("sessionId")
        synchronized(guard) {
            val managed = session(parameters)
            requireIdle(managed)
            managed.state = "inspecting"
            managed.revision++
            managed.queue.add(Job.Snapshot(success, failure))
        }
    }

    private fun action(parameters: JsonNode): Map<String, Any?> {
        parameters.onlyFields("sessionId", "runId", "attemptId", "attemptNo", "action")
        val runId = HarmonyOsAgentProtocol.uuid(parameters.requiredText("runId"), "runId")
        val attemptId = HarmonyOsAgentProtocol.uuid(parameters.requiredText("attemptId"), "attemptId")
        val sequence = parameters.get("attemptNo")
        if (sequence == null || !sequence.isIntegralNumber || !sequence.canConvertToInt() || sequence.intValue() < 1) {
            fail("invalid_input", "INVALID_PARAMS", "attemptNo must be a positive integer.")
        }
        val action = parameters.get("action")
        if (action == null || !action.isObject) fail("invalid_input", "INVALID_PARAMS", "action must be an object.")
        synchronized(guard) {
            val managed = session(parameters)
            requireIdle(managed)
            val input = mapper.valueToTree<JsonNode>(mapOf(
                "deviceId" to managed.config.deviceId, "appId" to managed.config.appId,
                "abilityName" to managed.config.abilityName, "moduleName" to managed.config.moduleName,
                "action" to action,
            ))
            val attempt = managed.store.register(runId, attemptId, sequence.intValue(), managed.id,
                managed.config.deviceId, "action", input)
            val accepted = attempt.result()
            managed.active = attempt
            managed.state = "busy"
            managed.revision++
            managed.queue.add(Job.Action(action.deepCopy<JsonNode>(), attempt))
            return accepted
        }
    }

    private fun cancel(parameters: JsonNode): Map<String, Any?> {
        parameters.onlyFields("sessionId", "attemptId")
        val attemptId = HarmonyOsAgentProtocol.uuid(parameters.requiredText("attemptId"), "attemptId")
        synchronized(guard) {
            val managed = session(parameters)
            val active = managed.active
            val acceptance = when {
                active == null || active.attemptId != attemptId -> {
                    // Only attempts known to this session may be acknowledged as already finished.
                    if (completedAttempts[managed.id]?.contains(attemptId) == true) "already_finished"
                    else fail("invalid_input", "STALE_ATTEMPT", "The attempt is not active in this session.")
                }
                active.isTerminal -> "already_finished"
                active.isCancellationRequested -> "already_requested"
                else -> {
                    if (requestAttemptCancel(active)) {
                        if (!managed.releaseRequested) managed.state = "cancelling"
                        managed.revision++
                        if (managed.operation is Job.Action && !active.isTerminal) managed.worker.interrupt()
                        "accepted"
                    } else if (active.isTerminal) "already_finished" else "already_requested"
                }
            }
            return HarmonyOsAgentProtocol.document("cancelAck", managed.revision, mapOf(
                "sessionId" to managed.id, "attemptId" to attemptId, "acceptance" to acceptance,
            ))
        }
    }

    private val completedAttempts = linkedMapOf<String, MutableSet<String>>()

    private fun release(parameters: JsonNode): Map<String, Any?> {
        parameters.onlyFields("sessionId")
        synchronized(guard) {
            val managed = session(parameters)
            requestRelease(managed)
            return sessionDocument(managed)
        }
    }

    private fun requestRelease(managed: Session) {
        if (managed.finished || managed.releaseRequested) return
        managed.releaseRequested = true
        managed.state = "releasing"
        managed.cleanupState = "in_progress"
        managed.revision++
        val cancelledActive = managed.active?.takeUnless { it.isTerminal }?.let {
            requestAttemptCancel(it)
            it.isCancellationRequested && !it.isTerminal
        } == true
        if (!managed.opened || managed.operation is Job.Snapshot || (managed.operation is Job.Action && cancelledActive)) {
            managed.worker.interrupt()
        }
        managed.queue.offer(Job.Stop)
    }

    private fun requestAttemptCancel(attempt: AgentAttempt): Boolean {
        if (!attempt.requestCancel()) return false
        val data = mapper.valueToTree<JsonNode>(attempt.result()).path("data")
        if (data.hasNonNull("startedAt") && data.path("cleanup").path("state").asText() in setOf("not_started", "in_progress", "complete")) {
            attempt.setCleanup("unknown")
        }
        return true
    }

    private fun requireIdle(managed: Session) {
        if (managed.uncertainResources) {
            fail("environment", "PRECONDITION_UNKNOWN", "A prior operation left unconfirmed device resources. Release and inspect the device.")
        }
        if (!managed.opened || managed.state != "idle" || managed.releaseRequested || managed.finished) {
            fail("environment", "DEVICE_BUSY", "The session is not idle; query agent.session before issuing another device operation.")
        }
    }

    private fun session(parameters: JsonNode): Session {
        val id = HarmonyOsAgentProtocol.uuid(parameters.requiredText("sessionId"), "sessionId")
        return sessions[id] ?: fail("invalid_input", "NOT_FOUND", "The session is not owned by this bridge.")
    }

    private fun sessionDocument(managed: Session): Map<String, Any?> {
        val cleanup = mapOf("state" to managed.cleanupState, "errors" to managed.cleanupErrors.toList())
        return HarmonyOsAgentProtocol.document("session", managed.revision, mapOf(
            "sessionId" to managed.id, "owner" to "bridge", "state" to managed.state,
            "activeAttemptId" to managed.active?.attemptId, "lockState" to managed.lockState,
            "cleanup" to cleanup, "handoffReady" to handoffReady(managed),
        ))
    }

    private fun handoffReady(managed: Session) = managed.finished && managed.state == "closed" &&
        managed.active == null && managed.lockState == "released" && managed.cleanupState == "complete"

    private fun recover(parameters: JsonNode, success: (Any) -> Unit, failure: (Exception) -> Unit) {
        parameters.onlyFields("deviceId", "sessionId")
        val id = HarmonyOsAgentProtocol.uuid(parameters.requiredText("sessionId"), "sessionId")
        val deviceId = parameters.requiredText("deviceId")
        if (deviceId.length > 256 || !deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")))
            fail("invalid_input", "INVALID_PARAMS", "Invalid explicit device identifier.")
        synchronized(guard) {
            if (recovering || sessions.values.any { !it.finished })
                fail("environment", "DEVICE_BUSY", "Release the session and wait for the owner to finish before recovery.")
            sessions[id]?.let { if (it.config.deviceId != deviceId)
                fail("invalid_input", "RECOVERY_SESSION_MISMATCH", "The session belongs to another device.") }
            recovering = true
        }
        try {
            queries.execute {
                var result: Map<String, Any?>? = null
                var error: Exception? = null
                try {
                    val recovered = backend.recover(deviceId, id)
                    check(recovered["deviceId"] == deviceId && recovered["sessionId"] == id) { "Recovery returned a different owner." }
                    val cleanup = recovered["cleanup"] as Map<*, *>
                    val confirmed = recovered["state"] == "complete" && cleanup["state"] == "complete" &&
                        (cleanup["errors"] as List<*>).isEmpty() && recovered["pendingOperations"] == 0 && recovered["temporaryFiles"] == 0
                    check(recovered["handoffReady"] == confirmed) { "Recovery did not provide consistent completion evidence." }
                    synchronized(guard) {
                        sessions[id]?.let { managed ->
                            managed.state = "closed"
                            managed.lockState = "released"
                            managed.cleanupState = cleanup["state"] as String
                            managed.cleanupErrors.clear()
                            @Suppress("UNCHECKED_CAST")
                            managed.cleanupErrors.addAll(cleanup["errors"] as List<Map<String, Any?>>)
                            managed.uncertainResources = recovered["handoffReady"] != true
                            managed.revision++
                        }
                        result = HarmonyOsAgentProtocol.document("recoveryReport", ++recoveryRevision, recovered)
                    }
                } catch (caught: Exception) { error = caught }
                finally { synchronized(guard) { recovering = false } }
                if (error != null) deliverFailure(failure, error!!) else reply(success, failure) { result!! }
            }
        } catch (error: Exception) {
            synchronized(guard) { recovering = false }
            throw error
        }
    }

    private fun readAttempt(method: String, runId: String, attemptId: String): Map<String, Any?> {
        val known = synchronized(guard) { stores.values.toList() }
        for (store in known) {
            try { return if (method == "agent.result") store.result(runId, attemptId) else store.evidence(runId, attemptId) }
            catch (error: AgentProtocolException) { if (error.error.code != "NOT_FOUND") throw error }
        }
        fail("invalid_input", "NOT_FOUND", "The attempt was not found in this bridge's configured artifact roots.")
    }

    fun requestShutdown() {
        beginShutdown(drainQueries = false)
    }

    private fun beginShutdown(drainQueries: Boolean) {
        synchronized(guard) {
            stopping = true
            sessions.values.forEach(::requestRelease)
        }
        // EOF closes the request stream, but the client may still be reading responses.
        // Transport failures and cancellation must still be able to interrupt a drain.
        if (drainQueries) queries.shutdown()
        else if (queries.shutdownNow().isNotEmpty()) abandonedQueries = true
    }

    fun close(drainQueries: Boolean = false): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownWaitMs)
        beginShutdown(drainQueries)
        val workers = synchronized(guard) { sessions.values.flatMap { listOf(it.worker, it.completion) } }
        var waitCompleted = true
        for (worker in workers) {
            if (worker === Thread.currentThread()) continue
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) { if (worker.isAlive) waitCompleted = false; break }
            try { worker.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); waitCompleted = false; break }
            if (worker.isAlive) { waitCompleted = false; break }
        }
        val remaining = deadline - System.nanoTime()
        if (remaining > 0 && !queries.isTerminated) {
            try { if (!queries.awaitTermination(remaining, TimeUnit.NANOSECONDS)) waitCompleted = false }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); waitCompleted = false }
        }
        if (!queries.isTerminated) waitCompleted = false
        // Do not grant another timeout, or report success if cancellation finishes just after it.
        if (!waitCompleted) requestShutdown()
        val complete = waitCompleted && !abandonedQueries &&
            synchronized(guard) { !recovering && sessions.values.all(::handoffReady) }
        if (!complete) diagnostics.println("Agent shutdown did not confirm execution and resource cleanup; device handoff is not ready.")
        return complete
    }

    private fun reply(success: (Any) -> Unit, failure: (Exception) -> Unit, value: () -> Any) {
        try { success(value()) }
        catch (error: IOException) { diagnostics.println(error.stackTraceToString()); requestShutdown() }
        catch (error: Exception) { deliverFailure(failure, error) }
    }

    private fun deliverFailure(failure: (Exception) -> Unit, error: Exception) {
        try { failure(error) } catch (outputError: Exception) {
            diagnostics.println(outputError.stackTraceToString())
            requestShutdown()
        }
    }

    private fun fail(kind: String, code: String, message: String): Nothing =
        throw AgentProtocolException(AgentError(kind, code, message))

    private fun structuredCleanupErrors(error: Throwable): List<Map<String, Any?>> {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val errors = mutableListOf<Map<String, Any?>>()
        fun collect(current: Throwable) {
            if (!visited.add(current)) return
            errors += HarmonyOsAgentProtocol.error(current).toMap()
            current.suppressed.forEach(::collect)
        }
        collect(error)
        return errors
    }
}
