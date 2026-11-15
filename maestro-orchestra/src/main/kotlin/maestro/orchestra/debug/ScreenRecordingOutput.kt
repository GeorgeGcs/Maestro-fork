package maestro.orchestra.debug

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.Maestro
import maestro.ScreenRecording
import maestro.debuglog.RecordingDiagnosticContext
import okio.ForwardingSink
import okio.Sink
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Starts a screen recording that writes into [file] through [sink].
 *
 * When no recording starts, [sink] is closed and [file] deleted, so no empty recording is left
 * on disk or reported as an artifact. That covers both outcomes: a recording already in
 * progress (returns null) and a driver that failed to start (the failure is rethrown).
 * A started recording owns this output sink until its handle is closed.
 */
internal suspend fun Maestro.startScreenRecordingInto(sink: Sink, file: File): ScreenRecording? {
    val output = RecordingOutputSink(sink, file)
    val recording = try {
        startScreenRecording(output)
    } catch (e: Exception) {
        discardRecordingOutput(output, file)
        throw e
    }
    if (recording == null) {
        discardRecordingOutput(output, file)
        return null
    }
    return object : ScreenRecording {
        override val startedAt = recording.startedAt
        private var closed = false

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                recording.close()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    output.close()
                } catch (closeError: Throwable) {
                    val original = failure
                    if (original == null) throw closeError
                    if (original !== closeError) original.addSuppressed(closeError)
                }
            }
        }
    }
}

internal class RecordingOutputSink(
    delegate: Sink,
    private val file: File,
    private val emitBinding: (String) -> Unit = {
        LoggerFactory.getLogger(RecordingOutputSink::class.java).info(it)
    },
) : ForwardingSink(delegate), RecordingDiagnosticContext {
    private var closed = false
    private var diagnosticContextId: String? = null

    @Synchronized
    override fun recordingDiagnosticContext(): String {
        diagnosticContextId?.let { return it }
        val id = UUID.randomUUID().toString()
        diagnosticContextId = id
        // Only a backend requesting diagnostics emits this link. Other platforms
        // keep the same output path and sink lifecycle without additional logging.
        runCatching {
            val outputPath = file.toPath().toAbsolutePath().normalize().toString()
            val available = outputPath.length <= 4096
            val binding = linkedMapOf(
                "schemaVersion" to 1,
                "contextId" to id,
                "outputPath" to outputPath.takeIf { available },
                "pathAvailable" to available,
            )
            emitBinding("MAESTRO_RECORDING_OUTPUT " + jacksonObjectMapper().writeValueAsString(binding))
        }
        return id
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        super.close()
    }
}

private fun discardRecordingOutput(sink: Sink, file: File) {
    // A failed close must not mask why the recording did not start.
    runCatching { sink.close() }
    file.delete()
}
