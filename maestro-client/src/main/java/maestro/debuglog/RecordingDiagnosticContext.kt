package maestro.debuglog

/** Optional, opaque link between a recorder's observations and its output sink. */
interface RecordingDiagnosticContext {
    /** Implementations may emit a diagnostic binding; callers must tolerate failure. */
    fun recordingDiagnosticContext(): String?
}
