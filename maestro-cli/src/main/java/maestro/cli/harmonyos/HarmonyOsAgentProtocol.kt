package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.TimeoutCancellationException
import maestro.MaestroException
import maestro.DeviceConnectionException
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.driver.HarmonyOSCommandException
import maestro.orchestra.error.ValidationError
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.FlowParseException
import maestro.orchestra.yaml.ConfigParseError
import maestro.orchestra.yaml.ToCommandsException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

internal data class AgentError(
    val kind: String,
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to kind, "code" to code, "message" to message,
    ).also { if (details != null) it["details"] = details }
}

internal class AgentProtocolException(val error: AgentError) : RuntimeException(error.message)

internal interface AgentRecorderConnection : RecorderConnection {
    fun executeAgentAction(parameters: JsonNode, attempt: AgentAttempt)
}

internal object HarmonyOsAgentProtocol {
    fun document(kind: String, revision: Long, data: Any): Map<String, Any?> = linkedMapOf(
        "protocol" to "maestro.harmonyos.agent", "schemaVersion" to 1,
        "kind" to kind, "revision" to revision, "observedAt" to Instant.now().toString(), "data" to data,
    )

    fun uuid(value: String, name: String): String {
        if (!value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
            runCatching { UUID.fromString(value).toString() }.getOrNull() != value) {
            throw AgentProtocolException(AgentError("invalid_input", "INVALID_PARAMS", "$name must be a lowercase UUID."))
        }
        return value
    }

    /** Classification follows exception types and stable codes, never localized diagnostics. */
    fun error(error: Throwable): AgentError {
        val causes = generateSequence(error) { it.cause }.take(32).toList()
        causes.filterIsInstance<AgentProtocolException>().firstOrNull()?.let { return it.error }
        val cause = causes.firstOrNull { it is HdcTimeoutException || it is TimeoutCancellationException ||
            it is TimeoutException || it is MaestroException.DriverTimeout }
            ?: causes.firstOrNull { it is InterruptedException || it is CancellationException }
            ?: causes.firstOrNull { it is MaestroException.AssertionFailure || it is RecorderError ||
                it is UnsupportedOperationException || it is MaestroException.InvalidCommand || it is HdcException || it is DeviceConnectionException ||
                it is ValidationError || it is SyntaxError || it is FlowParseException || it is ConfigParseError || it is ToCommandsException }
            ?: error
        val message = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        return when (cause) {
            is HdcTimeoutException, is TimeoutCancellationException, is TimeoutException,
            is MaestroException.DriverTimeout -> AgentError("timeout", "OPERATION_TIMEOUT", message)
            is InterruptedException, is CancellationException -> AgentError("cancelled", "CANCELLED", message)
            is MaestroException.AssertionFailure -> AgentError("assertion", "ASSERTION_FAILED", message)
            is UnsupportedOperationException -> AgentError("unsupported", "UNSUPPORTED_COMMAND", message)
            is MaestroException.InvalidCommand, is IllegalArgumentException, is ValidationError, is SyntaxError,
            is FlowParseException, is ConfigParseError, is ToCommandsException -> AgentError("invalid_input", "INVALID_PARAMS", message)
            is DeviceConnectionException -> AgentError("transport", "DEVICE_DISCONNECTED", message)
            is HarmonyOSCommandException -> AgentError("transport", "DEVICE_COMMAND_FAILED", message)
            is HdcException -> AgentError("transport", "HDC_ERROR", message)
            is RecorderError -> when (cause.code) {
                "INVALID_REQUEST", "REQUEST_TOO_LARGE", "RESPONSE_TOO_LARGE", "INVALID_PARAMS", "NOT_FOUND", "STALE_ATTEMPT" ->
                    AgentError("invalid_input", cause.code, message)
                "UNKNOWN_METHOD" -> AgentError("unsupported", cause.code, message)
                "DEVICE_BUSY", "NOT_CONNECTED", "AMBIGUOUS_SELECTOR" -> AgentError("environment", cause.code, message)
                "STALE_SNAPSHOT", "NO_SNAPSHOT" -> AgentError("environment", "STALE_SNAPSHOT", message)
                "UNSTABLE_PAGE" -> AgentError("timeout", "OPERATION_TIMEOUT", message)
                "ORIENTATION_CHANGED" -> AgentError("environment", "PRECONDITION_UNKNOWN", message)
                else -> AgentError("internal", "INTERNAL_ERROR", message, mapOf("recorderCode" to cause.code))
            }
            else -> AgentError("internal", "INTERNAL_ERROR", message)
        }
    }
}
