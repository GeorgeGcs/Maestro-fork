package maestro.orchestra.debug

import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactManifest
import java.io.File

/** Values are snapshots of the executor, never a listener-maintained execution stack. */
data class RetryExecutionFrame(val invocationId: String, val commandSequence: Int, val tryNo: Int)

data class ExecutionContext(
    val executionSequence: Int,
    val parentExecutionSequence: Int?,
    val retryContext: List<RetryExecutionFrame>,
    val flowScopeOwner: Int?,
    val phase: String,
    val optionalAncestorSequences: List<Int>,
)

data class ExecutionEvent(val sequence: Long, val elapsedNanos: Long)

data class RetryExecutionEvent(
    val event: ExecutionEvent,
    val kind: String,
    val invocationId: String,
    val commandSequence: Int,
    val parentContext: List<RetryExecutionFrame>,
    val maxRetries: Int,
    val optional: Boolean,
    val tryNo: Int? = null,
    val outcome: String? = null,
    val error: Throwable? = null,
)

data class ExecutionTraceEnd(
    val event: ExecutionEvent,
    /** Reserved by the executor for the writer's completion marker. */
    val completionSequence: Long,
    val expectedEventCount: Long,
    val lastExecutionSequence: Int,
    val activeCommands: Int,
    val activeRetries: Int,
    val success: Boolean?,
    val error: Throwable?,
)

/** Allocates only flow-level log outputs in the generator-owned bundle. */
class ExecutionTraceArtifacts(
    val allocate: (String, ArtifactFormat) -> File?,
    val refreshManifest: () -> Unit,
    val currentManifest: () -> ArtifactManifest? = { null },
)
