package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

/** Claimed before logging, dependency initialization or analytics can write to stdout. */
internal object HarmonyOsBridgeStdout {
    var output: OutputStream = System.out
        private set

    fun claim() {
        output = System.out
        System.setOut(System.err)
    }
}

internal class RecorderError(val code: String, message: String) : RuntimeException(message)

internal data class RecorderConfig(
    val deviceId: String,
    val appId: String,
    val abilityName: String?,
    val moduleName: String?,
    val artifactsDir: Path,
    val sessionId: String? = null,
)

internal interface RecorderConnection {
    fun snapshot(): Any
    fun action(parameters: JsonNode): Any
}

internal interface RecorderBackend {
    fun devices(): List<String>
    fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit)
    fun recover(deviceId: String, sessionId: String): Map<String, Any?> =
        throw AgentProtocolException(AgentError("unsupported", "UNSUPPORTED_COMMAND", "This backend cannot prove resource recovery."))
    fun noteUncertainCleanup(sessionId: String) = Unit
}

internal class HarmonyOsBridgeServer(
    input: InputStream,
    private val output: OutputStream,
    private val diagnostics: PrintStream,
    private val backend: RecorderBackend,
    capabilities: HarmonyOsAgentCapabilities = HarmonyOsAgentCapabilities(),
) {
    private val rawInput = input
    private val input = input.buffered()
    private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val agent = HarmonyOsAgentController(backend, diagnostics, capabilities)
    private val outputGuard = Any()
    private val usedIds = mutableSetOf<Long>()
    private val agentIds = mutableSetOf<Long>()
    private data class Request(val id: Long, val method: String, val params: JsonNode)
    @Volatile private var eof = false
    @Volatile private var outputFailed = false

    fun run(): Int {
        var exitCode = 0
        try {
            while (!eof) {
                val request = nextRequest() ?: continue
                try {
                    if (request.method.startsWith("agent.")) {
                        dispatchAgent(request)
                        continue
                    }
                    // The synchronous legacy protocol cannot take over an agent-owned device.
                    if (agent.hasUnreleasedSession()) throw RecorderError("DEVICE_BUSY", "Release the agent session before using legacy bridge methods.")
                    when (request.method) {
                        "devices" -> devices(request)
                        "connect" -> connect(request)
                        "disconnect" -> {
                            request.params.onlyFields()
                            success(request, mapOf("disconnected" to true))
                        }
                        "snapshot", "action" -> throw RecorderError("NOT_CONNECTED", "Connect to a device first.")
                        else -> throw RecorderError("UNKNOWN_METHOD", "Unknown bridge method: ${request.method}")
                    }
                } catch (error: Exception) {
                    failure(request.id, error, request.method.startsWith("agent."))
                }
            }
        } catch (error: IOException) {
            diagnostics.println(error.stackTraceToString())
            exitCode = 1
        } finally {
            if (!agent.close(drainQueries = eof && !outputFailed && exitCode == 0)) exitCode = 1
        }
        return if (outputFailed) 1 else exitCode
    }

    private fun dispatchAgent(request: Request) {
        agent.dispatch(request.method, request.params,
            { result -> success(request, result) }, { error -> failure(request.id, error, true) })
    }

    private fun connect(request: Request) {
        val config = parseRecorderConfig(request.params)
        var acknowledged = false
        var disconnect: Request? = null
        try {
            backend.withSession(config) { connection ->
                success(request, mapOf("deviceId" to config.deviceId, "appId" to config.appId))
                acknowledged = true
                disconnect = connectedLoop(connection)
            }
        } catch (error: Exception) {
            if (!acknowledged) throw error
            val pending = disconnect
            if (pending != null) failure(pending.id, error) else diagnostics.println(error.stackTraceToString())
            return
        }
        // withSession has returned: both the driver and the device lock are now closed.
        disconnect?.let { success(it, mapOf("disconnected" to true)) }
    }

    private fun connectedLoop(connection: RecorderConnection): Request? {
        while (!eof) {
            val request = nextRequest() ?: continue
            try {
                if (request.method.startsWith("agent.")) {
                    if (request.method in setOf("agent.describe", "agent.result", "agent.evidence", "agent.session")) dispatchAgent(request)
                    else throw RecorderError("DEVICE_BUSY", "Disconnect the legacy recorder before opening an agent session.")
                    continue
                }
                when (request.method) {
                    "disconnect" -> {
                        request.params.onlyFields()
                        return request
                    }
                    "devices" -> devices(request)
                    "snapshot" -> {
                        request.params.onlyFields()
                        success(request, connection.snapshot())
                    }
                    "action" -> success(request, connection.action(request.params))
                    "connect" -> throw RecorderError("ALREADY_CONNECTED", "Disconnect the current device before connecting again.")
                    else -> throw RecorderError("UNKNOWN_METHOD", "Unknown bridge method: ${request.method}")
                }
            } catch (error: Exception) {
                failure(request.id, error, request.method.startsWith("agent."))
            }
        }
        return null
    }

    private fun devices(request: Request) {
        request.params.onlyFields()
        success(request, mapOf("devices" to backend.devices().map { mapOf("id" to it) }))
    }

    private fun nextRequest(): Request? {
        val bytes = ByteArrayOutputStream()
        var oversized = false
        while (true) {
            val value = input.read()
            if (value < 0) {
                eof = true
                if (bytes.size() == 0 && !oversized) return null
                break
            }
            if (value == '\n'.code) break
            if (bytes.size() < MAX_REQUEST_BYTES) bytes.write(value) else oversized = true
        }
        var id: Long? = null
        var agentRequest = false
        try {
            if (oversized) throw RecorderError("REQUEST_TOO_LARGE", "Request exceeds 64 KiB.")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val line = decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            val request = mapper.readTree(line) ?: throw RecorderError("INVALID_REQUEST", "Expected a JSON request.")
            if (!request.isObject) throw RecorderError("INVALID_REQUEST", "Request must be an object.")
            agentRequest = request.path("method").asText().startsWith("agent.")
            val idNode = request.get("id")
            if (idNode == null || !idNode.isIntegralNumber || !idNode.canConvertToLong() ||
                idNode.longValue() !in 1..9_007_199_254_740_991L
            ) throw RecorderError("INVALID_REQUEST", "id must be a positive safe integer.")
            id = idNode.longValue()
            request.onlyFields("id", "method", "params")
            val method = request.requiredText("method")
            if ((agentRequest && id in usedIds) || id in agentIds) {
                throw RecorderError("INVALID_REQUEST", "Request id must not be reused on this connection.")
            }
            usedIds.add(id)
            if (agentRequest) agentIds.add(id)
            val params = request.get("params") ?: mapper.createObjectNode()
            if (!params.isObject) throw RecorderError("INVALID_PARAMS", "params must be an object.")
            return Request(id, method, params)
        } catch (error: Exception) {
            failure(id, if (error is RecorderError) error else RecorderError("INVALID_REQUEST", "Invalid UTF-8 JSON request: ${error.message}"), agentRequest)
            return null
        }
    }

    private fun success(request: Request, result: Any) {
        val bytes = mapper.writeValueAsBytes(mapOf("id" to request.id, "result" to result))
        if (bytes.size > MAX_RESPONSE_BYTES) throw RecorderError("RESPONSE_TOO_LARGE", "Response exceeds 32 MiB.")
        write(bytes)
    }

    private fun failure(id: Long?, error: Exception, agentRequest: Boolean = false) {
        diagnostics.println(error.stackTraceToString())
        val code = (error as? RecorderError)?.code ?: "DEVICE_ERROR"
        val message = (error.message ?: error.javaClass.simpleName).take(4096)
        val body = if (agentRequest) HarmonyOsAgentProtocol.error(error).toMap() else mapOf("code" to code, "message" to message)
        write(mapper.writeValueAsBytes(mapOf("id" to id, "error" to body)))
    }

    private fun write(bytes: ByteArray) {
        try {
            synchronized(outputGuard) {
                output.write(bytes)
                output.write('\n'.code)
                output.flush()
            }
        } catch (error: IOException) {
            eof = true
            outputFailed = true
            agent.requestShutdown()
            runCatching { rawInput.close() }
            throw error
        }
    }

    companion object {
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    }
}

internal fun parseRecorderConfig(parameters: JsonNode): RecorderConfig {
    parameters.onlyFields("deviceId", "appId", "abilityName", "moduleName", "artifactsDir")
    val artifactPath = Path.of(parameters.requiredText("artifactsDir"))
    if (!artifactPath.isAbsolute) throw RecorderError("INVALID_PARAMS", "artifactsDir must be an absolute path.")
    val config = RecorderConfig(parameters.requiredText("deviceId"), parameters.requiredText("appId"),
        parameters.optionalText("abilityName"), parameters.optionalText("moduleName"), artifactPath.normalize())
    if (!config.deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*"))) {
        throw RecorderError("INVALID_PARAMS", "Invalid device identifier.")
    }
    for (value in listOfNotNull(config.appId, config.abilityName, config.moduleName)) {
        if (value.startsWith('-') || value.any { it.isWhitespace() || it == '\u0000' } || value.contains("\${")) {
            throw RecorderError("INVALID_PARAMS", "Application and ability identifiers cannot contain whitespace or expressions.")
        }
    }
    return config
}

internal fun JsonNode.onlyFields(vararg names: String) {
    if (!isObject || fieldNames().asSequence().any { it !in names }) {
        throw RecorderError("INVALID_PARAMS", "Unexpected parameter; allowed fields: ${names.joinToString()}.")
    }
}

internal fun JsonNode.requiredText(name: String, allowEmpty: Boolean = false): String {
    val value = get(name)
    if (value == null || !value.isTextual || (!allowEmpty && value.textValue().isBlank()) || value.textValue().contains('\u0000')) {
        throw RecorderError("INVALID_PARAMS", "$name must be ${if (allowEmpty) "a" else "a non-empty"} string without NUL.")
    }
    return value.textValue()
}

internal fun JsonNode.optionalText(name: String): String? = if (has(name)) requiredText(name) else null
