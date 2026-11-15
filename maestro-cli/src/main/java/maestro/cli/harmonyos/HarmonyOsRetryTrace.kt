package maestro.cli.harmonyos

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.debug.ExecutionContext
import maestro.orchestra.debug.ExecutionEvent
import maestro.orchestra.debug.ExecutionTraceArtifacts
import maestro.orchestra.debug.ExecutionTraceEnd
import maestro.orchestra.debug.RetryExecutionEvent
import maestro.orchestra.debug.RetryExecutionFrame
import maestro.utils.TempFileHandler
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Optional evidence only. Its failures never enter the agent's strict reportingFailure path. */
internal class HarmonyOsRetryTrace(
    runId: String,
    attemptId: String,
    flowPath: String,
    flowSha256: String?,
    private val openWriter: (Path) -> Writer = {
        Files.newBufferedWriter(it, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    },
) {
    private val mapper = jacksonObjectMapper()
    val flowExecutionId: String = UUID.randomUUID().toString()
    private val identity = linkedMapOf<String, Any?>(
        "protocol" to "maestro.harmonyos.retry-trace", "schemaVersion" to 2,
        "runId" to runId, "attemptId" to attemptId, "flowExecutionId" to flowExecutionId,
        "flowPath" to flowPath, "flowSha256" to flowSha256,
    )
    private data class Step(val id: String, val context: ExecutionContext, val fields: Map<String, Any?>, var ended: Boolean = false)
    private data class Retry(val owner: Int, val parents: List<RetryExecutionFrame>, val optional: Boolean,
        var lastTry: Int = 0, var activeTry: Int? = null, var ended: Boolean = false)
    private val steps = linkedMapOf<Int, Step>()
    private val stepIds = mutableSetOf<String>()
    private val retries = linkedMapOf<String, Retry>()
    private val outcomeCounts = linkedMapOf<String, Int>()
    private val coverage = linkedMapOf<String, MutableList<Int>>()
    private var artifacts: ExecutionTraceArtifacts? = null
    private var tracePath: Path? = null
    private var summaryPath: Path? = null
    private var writer: Writer? = null
    private var nextSequence = 0L
    private var end: ExecutionTraceEnd? = null
    private var sealed = false
    private var retryUsed = false
    private var optionalRetryUsed = false
    var traceFailure: Throwable? = null
        private set
    var artifactManifest: ArtifactManifest? = null
        private set

    fun failed(error: Throwable) { if (traceFailure == null) traceFailure = error }

    private inline fun record(block: () -> Unit) {
        if (traceFailure != null || sealed) return
        try { block() } catch (error: Throwable) { failed(error) }
    }

    fun ready(outputs: ExecutionTraceArtifacts) = record {
        check(artifacts == null) { "Trace outputs were already allocated." }
        artifacts = outputs
        val trace = checkNotNull(outputs.allocate("retry-trace.jsonl", ArtifactFormat.TXT)) { "No artifacts bundle is available." }.toPath()
        val summary = checkNotNull(outputs.allocate("retry-summary.json", ArtifactFormat.JSON)).toPath()
        check(!Files.exists(trace) && !Files.exists(summary)) { "Trace outputs must not replace a previous run." }
        tracePath = trace
        summaryPath = summary
        atomicSummary(summary, summary("incomplete", null, null))
        writer = openWriter(trace)
        append(identity + mapOf("event" to "traceStarted"))
    }

    fun stepStarted(id: String, context: ExecutionContext, fields: Map<String, Any?>, event: ExecutionEvent) = record {
        check(fields.containsKey("sourceIdentity")) { "Step source identity is missing." }
        val sourceIdentity = fields["sourceIdentity"]
        check(when (fields["sourceOrigin"]) {
            "generated" -> sourceIdentity == null && fields["sourcePath"] == null && fields["sourceSha256"] == null
            "yaml" -> sourceIdentity is String && sourceIdentity.isNotBlank() && Path.of(sourceIdentity).isAbsolute
            else -> false
        }) { "Step source identity does not match its origin." }
        check(context.executionSequence == steps.size) { "Execution sequence is missing or duplicated." }
        check(stepIds.add(id)) { "An existing step ID was reused." }
        context.parentExecutionSequence?.let { check(steps[it]?.ended == false) { "Parent command is not active." } }
        val data = fields + mapOf("stepId" to id, "executionSequence" to context.executionSequence,
            "parentExecutionSequence" to context.parentExecutionSequence,
            "retryContext" to retryContextData(context.retryContext), "flowScopeOwner" to context.flowScopeOwner,
            "phase" to context.phase, "optionalAncestorSequences" to context.optionalAncestorSequences)
        steps[context.executionSequence] = Step(id, context, data)
        event("stepStarted", event, data)
    }

    fun stepFinished(id: String, context: ExecutionContext, outcome: String, error: Throwable?, event: ExecutionEvent) = record {
        val step = checkNotNull(steps[context.executionSequence]) { "Step completion has no start." }
        check(step.id == id && step.context == context && !step.ended) { "Step completion does not match its execution." }
        // Validate against the executor's current attempts, not a source-position heuristic.
        retryContextData(context.retryContext)
        step.ended = true
        outcomeCounts[outcome] = outcomeCounts.getOrDefault(outcome, 0) + 1
        coverage.getOrPut(outcome) { mutableListOf() }.add(context.executionSequence)
        event(if (outcome == "Interrupted") "stepInterrupted" else "stepFinished", event,
            step.fields + mapOf("outcome" to if (outcome == "Interrupted") null else outcome, "error" to errorInfo(error)))
    }

    fun retry(value: RetryExecutionEvent) = record {
        val owner = checkNotNull(steps[value.commandSequence]) { "Retry has no registered command step." }
        check(!owner.ended && owner.context.retryContext == value.parentContext) { "Retry owner/context does not match." }
        val parents = retryContextData(value.parentContext)
        when (value.kind) {
            "retryBlockStarted" -> {
                check(!retries.containsKey(value.invocationId)) { "Retry invocation was reused." }
                retries[value.invocationId] = Retry(value.commandSequence, value.parentContext, value.optional)
            }
            "retryTryStarted" -> {
                val retry = retryState(value)
                val number = checkNotNull(value.tryNo)
                check(retry.activeTry == null && number == retry.lastTry + 1) { "Retry attempt order does not match." }
                retry.lastTry = number
                retry.activeTry = number
                if (number > 1) {
                    retryUsed = true
                    if (retry.optional) optionalRetryUsed = true
                }
            }
            "retryTryFinished" -> {
                val retry = retryState(value)
                check(retry.activeTry == value.tryNo && value.tryNo != null) { "Retry completion has no active attempt." }
                check(steps.values.none { !it.ended && it.context.retryContext.any { frame ->
                    frame.invocationId == value.invocationId && frame.tryNo == value.tryNo
                } }) { "Retry finished with unfinished child steps." }
                retry.activeTry = null
            }
            "retryScheduled" -> {
                val retry = retryState(value)
                check(retry.activeTry == null && value.tryNo == retry.lastTry + 1) { "Retry schedule is out of order." }
            }
            "retryBlockFinished" -> {
                val retry = retryState(value)
                check(retry.activeTry == null) { "Retry block still has an active attempt." }
                retry.ended = true
            }
            else -> error("Unknown retry event.")
        }
        event(value.kind, value.event, mapOf("retryInvocationId" to value.invocationId,
            "retryCommandStepId" to owner.id, "executionSequence" to value.commandSequence,
            "parentRetryInvocationId" to value.parentContext.lastOrNull()?.invocationId,
            "parentTryNo" to value.parentContext.lastOrNull()?.tryNo, "retryContext" to parents,
            "maxRetries" to value.maxRetries, "optional" to value.optional, "tryNo" to value.tryNo,
            "outcome" to value.outcome, "error" to errorInfo(value.error)))
    }

    private fun retryState(value: RetryExecutionEvent): Retry = checkNotNull(retries[value.invocationId]).also {
        check(!it.ended && it.owner == value.commandSequence && it.parents == value.parentContext && it.optional == value.optional)
    }

    private fun retryContextData(frames: List<RetryExecutionFrame>): List<Map<String, Any?>> = frames.mapIndexed { index, frame ->
        val retry = checkNotNull(retries[frame.invocationId]) { "Retry frame has no start event." }
        check(!retry.ended && retry.activeTry == frame.tryNo && retry.owner == frame.commandSequence &&
            retry.parents == frames.take(index)) { "Retry frame is not the current attempt." }
        mapOf("retryInvocationId" to frame.invocationId, "tryNo" to frame.tryNo,
            "retryCommandStepId" to checkNotNull(steps[frame.commandSequence]).id)
    }

    fun ended(value: ExecutionTraceEnd) {
        record {
            check(end == null) { "Executor ended twice." }
            event("flowFinished", value.event, mapOf("success" to value.success, "error" to errorInfo(value.error),
                "expectedEventCount" to value.expectedEventCount, "lastExecutionSequence" to value.lastExecutionSequence))
            end = value
        }
    }

    /** Called after the real CLI executor has returned or thrown, outside listener dispatch. */
    fun seal(success: Boolean?, failure: Throwable? = null) {
        if (sealed) return
        record {
            val end = checkNotNull(end) { "Executor completion notification is missing." }
            check(end.activeCommands == 0 && end.activeRetries == 0) { "Executor contexts were not released." }
            check(steps.values.all { it.ended } && retries.values.all { it.ended && it.activeTry == null }) { "Trace has unfinished executions." }
            check(end.lastExecutionSequence == steps.size - 1) { "Trace is missing executed steps." }
            check(end.success == success) { "CLI and executor outcomes disagree." }
            check(end.completionSequence == nextSequence && end.expectedEventCount == nextSequence + 1) { "Trace is missing events." }
            event("traceCompleted", ExecutionEvent(end.completionSequence, end.event.elapsedNanos),
                mapOf("expectedEventCount" to end.expectedEventCount))
        }
        try { writer?.close() } catch (error: Throwable) { failed(error) }
        writer = null
        val hash = try { tracePath?.takeIf { Files.isRegularFile(it) }?.let(::sha256) }
            catch (error: Throwable) { failed(error); null }
        sealed = true
        val path = summaryPath ?: return
        try {
            atomicSummary(path, summary(if (traceFailure == null) "complete" else "incomplete", hash, success))
            artifacts?.refreshManifest?.invoke()
            artifactManifest = artifacts?.currentManifest?.invoke()
        } catch (error: Throwable) {
            failed(error)
            // A failed final publication must not leave a complete summary behind.
            runCatching {
                atomicSummary(path, summary("incomplete", hash, success))
                artifacts?.refreshManifest?.invoke()
                artifactManifest = artifacts?.currentManifest?.invoke()
            }
        }
    }

    private fun event(name: String, event: ExecutionEvent, data: Map<String, Any?>) {
        check(event.sequence == nextSequence) { "Trace event sequence is missing or duplicated." }
        append(mapOf("event" to name, "sequence" to event.sequence, "elapsedNanos" to event.elapsedNanos,
            "flowExecutionId" to flowExecutionId) + data)
        nextSequence++
    }

    private fun append(data: Map<String, Any?>) {
        val output = checkNotNull(writer) { "Trace writer is unavailable." }
        output.write(mapper.writeValueAsString(data))
        output.write("\n")
        output.flush()
    }

    private fun summary(state: String, hash: String?, success: Boolean?) = identity + mapOf(
        "state" to state, "traceCompleted" to (state == "complete"), "tracePath" to "retry-trace.jsonl",
        "traceSha256" to hash, "eventCount" to nextSequence, "expectedEventCount" to end?.expectedEventCount,
        "stepCount" to steps.size, "retryUsed" to if (state == "complete") retryUsed else null,
        "optionalRetryUsed" to if (state == "complete") optionalRetryUsed else null,
        "firstPassStatus" to when { state != "complete" -> "unknown"; success != true || retryUsed -> "not_passed"; else -> "passed" },
        "outcomeCounts" to outcomeCounts, "coverageIndex" to coverage, "error" to errorInfo(traceFailure),
    )

    private fun atomicSummary(path: Path, document: Map<String, Any?>) {
        TempFileHandler().use { scratch ->
            val temporary = path.resolveSibling(path.fileName.toString() + "." + UUID.randomUUID() + ".tmp").toFile()
            scratch.addFile(temporary)
            Files.writeString(temporary.toPath(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temporary.toPath(), path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun errorInfo(error: Throwable?) = error?.let { mapOf("class" to it.javaClass.name, "message" to it.message) }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
