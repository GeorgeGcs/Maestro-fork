package maestro.harmonyos.hdc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.debuglog.LogConfig
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.EnumMap
import java.util.UUID

/** Categories are selected before shell wrapping. Never derive log fields from arbitrary argv. */
internal enum class HdcCommandCategory {
    LIST_TARGETS, LAYOUT_FORWARD_LIST, DUMP_LAYOUT, SCREEN_CAPTURE, INPUT_TAP, INPUT_SWIPE, INPUT_TEXT, INPUT_KEY,
    INPUT_OTHER, FILE_RECEIVE, REMOVE_FILE, APP_START, APP_STOP, PROCESS_QUERY, SERVICE_QUERY,
    MEDIA_QUERY, MEDIA_RECEIVE, MEDIA_DELETE, FILE_STAT, FILE_TEST, SHELL_SCRIPT, SHELL_OTHER, UNKNOWN;

    companion object {
        fun shell(arguments: List<String>): HdcCommandCategory = when (arguments.firstOrNull()) {
            "uitest" -> when (arguments.getOrNull(1)) {
                "dumpLayout" -> DUMP_LAYOUT
                "screenCap" -> SCREEN_CAPTURE
                "uiInput" -> when (arguments.getOrNull(2)) {
                    "click", "doubleClick", "longClick" -> INPUT_TAP
                    "swipe", "drag" -> INPUT_SWIPE
                    "text" -> INPUT_TEXT
                    "keyEvent" -> INPUT_KEY
                    else -> INPUT_OTHER
                }
                else -> SHELL_OTHER
            }
            "rm" -> REMOVE_FILE
            "aa" -> when (arguments.getOrNull(1)) { "start" -> APP_START; "force-stop" -> APP_STOP; else -> SHELL_OTHER }
            "ps" -> PROCESS_QUERY
            "hidumper" -> SERVICE_QUERY
            "stat" -> FILE_STAT
            "test" -> FILE_TEST
            "mediatool" -> when (arguments.getOrNull(1)) {
                "query" -> MEDIA_QUERY; "recv" -> MEDIA_RECEIVE; "delete" -> MEDIA_DELETE; else -> SHELL_OTHER
            }
            "sh" -> SHELL_SCRIPT
            else -> SHELL_OTHER
        }
    }
}

/** Diagnostic observations only, not command acknowledgements or recovery evidence. */
internal class HdcCallDiagnostics(
    private val category: HdcCommandCategory,
    timeout: Duration,
    operationId: String? = null,
    private val emitLine: (String) -> Unit = ::log,
) {
    enum class Phase { DISPATCH, SPAWN, WAIT_EXIT, STDOUT_READ, STDERR_READ, DRAIN_STDOUT, DRAIN_STDERR, TERMINATE, CUSTOM_RUNNER, HOST_RETURN, CHECK_RESULT, FINISH }

    private val callId = UUID.randomUUID().toString()
    private val operationId = safeId(operationId)
    private var hostCallId: String? = null
    private val budgetMs = timeout.toMillis()
    private var processBudgetMs: Long? = null
    private val startedAt = Instant.now().toString()
    private val startedNanos = System.nanoTime()
    private val phaseStarts = EnumMap<Phase, Long>(Phase::class.java)
    private var lastPhase = Phase.DISPATCH
    private var failurePhase: Phase? = null
    private var failureKind: String? = null
    private var failureCause: Throwable? = null
    private var timedOut = false
    private var interrupted = false
    private var exitCode: Int? = null
    private var sequence = 0
    private var finished = false

    @Synchronized fun bindHostCall(id: String) { safely { hostCallId = safeId(id) } }
    @Synchronized fun processBudget(timeout: Duration) { safely { processBudgetMs = timeout.toMillis() } }

    @Synchronized fun begin(phase: Phase, active: Boolean = true) { safely {
        if (finished) return@safely
        phaseStarts[phase] = System.nanoTime()
        if (active) lastPhase = phase
        append(phase, "begin")
    } }

    @Synchronized fun end(phase: Phase, exit: Int? = null, bytes: Long? = null) { safely {
        if (finished) return@safely
        if (exit != null) exitCode = exit
        append(phase, "returned", bytes = bytes)
    } }

    @Synchronized fun phaseFailed(phase: Phase, error: Throwable) { safely {
        if (!finished) append(phase, "failed", phaseFailure = kind(error))
    } }

    @Synchronized fun failure(error: Throwable, phase: Phase = lastPhase) { safely {
        if (failureCause !== error) {
            failureCause = error
            failureKind = kind(error)
            failurePhase = phase
            timedOut = error is HdcTimeoutException
            interrupted = error is InterruptedException
        }
    } }

    @Synchronized fun finish() { safely {
        if (finished) return@safely
        finished = true
        append(Phase.FINISH, if (failureKind == null) "returned" else "failed")
    } }

    private fun append(phase: Phase, event: String, bytes: Long? = null, phaseFailure: String? = null) {
        // No loop/poll events. The cap also protects against accidental future instrumentation loops.
        if (sequence >= 32 && phase != Phase.FINISH) return
        val now = System.nanoTime()
        val fields = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "callId" to callId, "sequence" to ++sequence,
            "operationId" to operationId, "hostCallId" to hostCallId, "category" to category.name,
            "startedAt" to startedAt, "at" to Instant.now().toString(),
            "elapsedMs" to ((now - startedNanos).coerceAtLeast(0) / 1_000_000),
            "budgetMs" to budgetMs, "processBudgetMs" to processBudgetMs,
            "phase" to phase.name, "event" to event,
            "phaseElapsedMs" to phaseStarts[phase]?.let { (now - it).coerceAtLeast(0) / 1_000_000 },
            "exitCode" to exitCode, "bytes" to bytes, "timedOut" to timedOut, "interrupted" to interrupted,
            "lastPhase" to lastPhase.name, "failurePhase" to failurePhase?.name,
            "failureKind" to failureKind, "phaseFailureKind" to phaseFailure,
        )
        emitLine("MAESTRO_HDC_CALL " + mapper.writeValueAsString(fields))
    }

    private inline fun safely(action: () -> Unit) {
        val wasInterrupted = Thread.currentThread().isInterrupted
        try { action() } catch (_: Throwable) { /* Diagnostics must not replace the command outcome. */ }
        finally { if (wasInterrupted) Thread.currentThread().interrupt() }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
        private val marker = MarkerFactory.getMarker(LogConfig.HDC_DIAGNOSTIC_MARKER)
        private val identifier = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private fun safeId(value: String?) = value?.takeIf(identifier::matches)
        private fun kind(error: Throwable): String = when (error) {
            is HdcTimeoutException -> "timeout"
            is InterruptedException -> "interrupted"
            is HdcOutputLimitException -> "output_limit"
            is HdcCommandException -> "command_failed"
            is IOException -> "io_error"
            else -> "other"
        }
        internal fun log(line: String) { LoggerFactory.getLogger(HdcCallDiagnostics::class.java).info(marker, line) }
    }
}
