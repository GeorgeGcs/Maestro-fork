package maestro.harmonyos.hdc

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.function.Supplier

/** Explicit recovery of a reviewed legacy read-only diagnostic, never a normal Flow permission. */
object HarmonyOSDiagnosticCompletionRecovery {
    private data class Scope(val device: String, val owner: String, val operation: String, val hash: String, val host: String)
    private val active = ThreadLocal<Scope>()

    @JvmStatic fun <T> withReviewedFaultQuery(device: String, owner: String, operation: String, commandHash: String,
        trace: Path, traceHash: String, action: Supplier<T>): T {
        require(device.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")))
        require(UUID.fromString(owner).toString() == owner && UUID.fromString(operation).toString() == operation)
        require(commandHash.matches(Regex("[0-9a-f]{64}")) && traceHash.matches(Regex("[0-9a-f]{64}")))
        val file = trace.toAbsolutePath().normalize()
        require(file.parent.toRealPath() == file.parent && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        require(Files.size(file) <= 16 * 1024 * 1024)
        val bytes = Files.readAllBytes(file)
        val observedHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(observedHash == traceHash) { "The reviewed diagnostic trace changed." }
        val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        val events = bytes.toString(Charsets.UTF_8).lineSequence().filter { "MAESTRO_HDC_CALL " in it }
            .map { mapper.readTree(it.substringAfter("MAESTRO_HDC_CALL ")) }
            .filter { it["operationId"]?.asText() == operation }.toList()
        require(events.isNotEmpty() && events.all { it["schemaVersion"]?.asInt() == 1 && it["category"]?.asText() == "FAULT_QUERY" }) {
            "Only an archived legacy FAULT_QUERY diagnostic is supported."
        }
        val calls = events.map { it["callId"]?.takeIf { value -> value.isTextual }?.asText() }.distinct()
        val hosts = events.mapNotNull { it["hostCallId"]?.takeIf { value -> value.isTextual }?.asText() }.distinct()
        require(calls.size == 1 && calls.single() != null && UUID.fromString(calls.single()).toString() == calls.single())
        require(hosts.size == 1 && UUID.fromString(hosts.single()).toString() == hosts.single())
        require(events.all { it["hostCallId"] == null || it["hostCallId"].isNull || it["hostCallId"].isTextual })
        check(active.get() == null) { "A reviewed recovery scope is already active." }
        active.set(Scope(device, owner, operation, commandHash, hosts.single()))
        return try { action.get() } finally { active.remove() }
    }

    internal fun accepts(device: String, owner: String, operation: String, hash: String, stoppedHosts: List<String> = emptyList()): Boolean =
        active.get()?.let { it.device == device && it.owner == owner && it.operation == operation && it.hash == hash && it.host in stoppedHosts } == true
}
