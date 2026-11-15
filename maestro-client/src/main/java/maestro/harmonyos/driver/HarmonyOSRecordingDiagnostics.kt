package maestro.harmonyos.driver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HarmonyOSRecordingResourceJournal
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.TreeSet

/** Host observations only. These events are neither ownership nor recovery evidence. */
internal class HarmonyOSRecordingDiagnostics(
    fileName: String,
    resourceId: String?,
    contextId: String? = null,
    private val emitLine: (String) -> Unit = {
        LoggerFactory.getLogger(HarmonyOSRecordingDiagnostics::class.java).info(it)
    },
    private val wallClock: () -> Instant = Instant::now,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    enum class Stage {
        CREATED, SERVICE, PROCESSES, CAPTURES, QUERY, START, READY, STOP, CAPTURE_ENDED,
        EXPORT, EXPORT_SCRATCH, EXPORT_MEDIA, STAT, RECEIVE, STAT_AFTER_RECEIVE, VALIDATE, ARCHIVE, COPY, FLUSH, DELETE,
        MEDIA_RETAINED, MEDIA_REMOVED, LIMIT_REACHED, RESOURCE_FAILURE,
        CLEANUP_REMOTE, CLEANUP_LOCAL, CLEANUP_TEMP, CLEANUP_RESOURCE, CLEANUP, CLOSE, ABORT, TRUNCATED,
    }

    enum class Result { BEGIN, RETURNED, ACKNOWLEDGED, OBSERVED, ABSENT, PRESENT, INVALID, INCOMPLETE, FAILED, SKIPPED }

    // URI values are independently checked against this recording's generated name before emission.
    // No response text, command arguments, host paths or exception messages are accepted.
    data class Fields(
        val attempt: Int? = null,
        val expectedBytes: Long? = null,
        val receivedBytes: Long? = null,
        val remoteAfterBytes: Long? = null,
        val copiedBytes: Long? = null,
        val count: Int? = null,
        val pids: Set<Int>? = null,
        val retentionRequested: Boolean? = null,
        val persistent: Boolean? = null,
        val priorFailure: Boolean? = null,
        val cleanupErrors: Int? = null,
        val mediaUri: String? = null,
        val uriUnavailable: Boolean? = null,
        val mediaUriPreviouslyObserved: Boolean? = null,
        val archiveBytes: Long? = null,
        val archiveSha256: String? = null,
        val archiveMetadataUnavailable: Boolean? = null,
    )

    private val recordingId = Regex("maestro-recording-([0-9a-f]{32})\\.mp4").matchEntire(fileName)?.groupValues?.get(1) ?: "unavailable"
    private val ownedMediaUri = Regex("file://media/Photo/[0-9]+/[A-Za-z0-9_.-]+/maestro-recording-$recordingId\\.mp4")
    private val resourceId = resourceId?.takeIf { Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(it) }
    private val contextId = contextId?.takeIf { Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(it) }
    private var startNanos: Long? = null
    private var lastElapsedMs = 0L
    private var sequence = 0
    private var detailCount = 0
    private var finalCount = 0
    private var dropped = 0
    private var emissionFailures = 0
    private var finalized = false
    private var queryStarted = false
    private var previousMediaUri: String? = null
    private val observations = mutableMapOf<Stage, Any>()

    fun mark(): Long? = try { nanoClock() } catch (_: Throwable) { null }

    fun event(stage: Stage, result: Result, fields: Fields = Fields(), since: Long? = null, error: Throwable? = null) {
        safely { append(stage, result, fields, since, error, terminal = false) }
    }

    fun state(stage: Stage, pids: Set<Int>) {
        safely {
            // Keep bounded diagnostic state even if the device response lists many processes.
            val value = pids.size to smallestPids(pids)
            if (observations.put(stage, value) != value) append(stage, Result.OBSERVED, Fields(count = pids.size, pids = pids), null, null, false)
        }
    }

    fun queryStarted() {
        safely {
            if (!queryStarted) {
                queryStarted = true
                append(Stage.QUERY, Result.BEGIN, Fields(), null, null, false)
            }
        }
    }

    fun query(uri: String?, since: Long?) {
        safely {
            val present = uri != null
            val safeUri = safeMediaUri(uri)
            val state = present to safeUri
            if (observations.put(Stage.QUERY, state) != state) {
                append(Stage.QUERY, if (present) Result.PRESENT else Result.ABSENT,
                    Fields(mediaUri = if (present) safeUri else previousMediaUri,
                        uriUnavailable = present && safeUri == null,
                        mediaUriPreviouslyObserved = !present && previousMediaUri != null), since, null, false)
            }
            if (present) previousMediaUri = safeUri
        }
    }

    fun finish(stage: Stage, error: Throwable?, since: Long? = null) {
        safely {
            if (!finalized) {
                append(stage, if (error == null) Result.RETURNED else Result.FAILED, Fields(), since, error, true)
                finalized = true
            }
        }
    }

    fun archived(attempt: Int, read: () -> HarmonyOSRecordingResourceJournal.Archive?) {
        safely {
            val archive = try { read() } catch (_: Throwable) { null }
            append(Stage.ARCHIVE, Result.OBSERVED,
                Fields(attempt = attempt, archiveBytes = archive?.size, archiveSha256 = archive?.sha256,
                    archiveMetadataUnavailable = archive == null), null, null, false)
        }
    }

    fun <T> step(stage: Stage, fields: Fields = Fields(), success: Result = Result.RETURNED, block: () -> T): T {
        val began = mark()
        event(stage, Result.BEGIN, fields)
        try {
            return block().also { event(stage, success, fields, began) }
        } catch (error: Throwable) {
            event(stage, Result.FAILED, fields, began, error)
            throw error
        }
    }

    @Synchronized
    private fun safely(block: () -> Unit) {
        try { block() } catch (_: Throwable) { emissionFailures++ }
    }

    private fun append(stage: Stage, result: Result, fields: Fields, since: Long?, error: Throwable?, terminal: Boolean) {
        if (finalized) return
        val finalStage = stage in FINAL_STAGES
        if (!terminal && (if (finalStage) finalCount >= FINAL_EVENTS else detailCount >= DETAIL_EVENTS)) {
            dropped++
            if (dropped == 1) write(Stage.TRUNCATED, Result.OBSERVED, Fields(), null, null, false)
            return
        }
        if (!terminal) { if (finalStage) finalCount++ else detailCount++ }
        write(stage, result, fields, since, error, terminal)
    }

    private fun write(stage: Stage, result: Result, fields: Fields, since: Long?, error: Throwable?, terminal: Boolean) {
        val now = nanoClock()
        val origin = startNanos ?: now.also { startNanos = it }
        val elapsed = ((now - origin).coerceAtLeast(0) / 1_000_000).coerceAtLeast(lastElapsedMs)
        lastElapsedMs = elapsed
        val values = linkedMapOf<String, Any>("result" to result.name.lowercase())
        since?.let { values["stageElapsedMs"] = (now - it).coerceAtLeast(0) / 1_000_000 }
        fields.attempt?.let { values["attempt"] = it }
        fields.expectedBytes?.let { values["expectedBytes"] = it }
        fields.receivedBytes?.let { values["receivedBytes"] = it }
        fields.remoteAfterBytes?.let { values["remoteAfterBytes"] = it }
        fields.copiedBytes?.let { values["copiedBytes"] = it }
        fields.count?.let { values["count"] = it }
        fields.pids?.let { values["pids"] = smallestPids(it); values["pidsTruncated"] = it.size > MAX_PIDS }
        fields.retentionRequested?.let { values["retentionRequested"] = it }
        fields.persistent?.let { values["persistent"] = it }
        fields.priorFailure?.let { values["priorFailure"] = it }
        fields.cleanupErrors?.let { values["cleanupErrors"] = it }
        fields.uriUnavailable?.let { values["uriUnavailable"] = it }
        fields.mediaUriPreviouslyObserved?.let { values["mediaUriPreviouslyObserved"] = it }
        fields.mediaUri?.let { uri ->
            val safeUri = safeMediaUri(uri)
            if (safeUri == null) values["uriUnavailable"] = true else values["mediaUri"] = safeUri
        }
        fields.archiveMetadataUnavailable?.let { values["archiveMetadataUnavailable"] = it }
        if (fields.archiveBytes != null || fields.archiveSha256 != null) {
            if (fields.archiveBytes != null && fields.archiveBytes >= 0 &&
                fields.archiveSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                values["archiveBytes"] = fields.archiveBytes
                values["archiveSha256"] = fields.archiveSha256
            } else values["archiveMetadataUnavailable"] = true
        }
        error?.let { values["errorKind"] = when (it) {
            is HdcTimeoutException -> "timeout"
            is InterruptedException -> "interrupted"
            is InvalidMp4RecordingException -> "invalid_mp4"
            is java.io.IOException -> "io"
            else -> "other"
        } }
        val seq = ++sequence
        if (terminal || stage == Stage.TRUNCATED) {
            values["truncated"] = dropped > 0
            values["droppedEvents"] = dropped
            values["emissionFailures"] = emissionFailures
        }
        if (terminal) { values["terminal"] = true; values["expectedEventCount"] = seq }
        val record = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "recordingId" to recordingId, "seq" to seq,
            "observedAt" to wallClock().toString(), "elapsedMs" to elapsed,
            "stage" to stage.name.lowercase(), "fields" to values, "contextId" to contextId,
        )
        resourceId?.let { record["resourceId"] = it }
        emitLine(PREFIX + mapper.writeValueAsString(record))
    }

    private fun safeMediaUri(uri: String?): String? = uri?.takeIf {
        recordingId != "unavailable" && it.length <= 512 && ownedMediaUri.matches(it)
    }

    private fun smallestPids(pids: Set<Int>): List<Int> {
        val smallest = TreeSet<Int>()
        for (pid in pids) {
            smallest.add(pid)
            if (smallest.size > MAX_PIDS) smallest.pollLast()
        }
        return smallest.toList()
    }

    companion object {
        const val PREFIX = "HARMONYOS_RECORDING_DIAGNOSTIC "
        const val DETAIL_EVENTS = 160
        const val FINAL_EVENTS = 24
        const val MAX_PIDS = 8
        private val FINAL_STAGES = setOf(Stage.CLEANUP_REMOTE, Stage.CLEANUP_LOCAL, Stage.CLEANUP_TEMP, Stage.CLEANUP_RESOURCE, Stage.CLEANUP)
        private val mapper by lazy { jacksonObjectMapper() }
    }
}
