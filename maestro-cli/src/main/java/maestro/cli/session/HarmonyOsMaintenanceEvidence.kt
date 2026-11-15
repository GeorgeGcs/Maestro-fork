package maestro.cli.session

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

internal data class MaintenanceReference(val path: String, val sha256: String)

/** A gate points at immutable records; publishing a new head is the only mutable commit. */
internal data class MaintenanceGate(
    val maintenanceId: String,
    val checkpoint: MaintenanceReference,
    val head: MaintenanceReference,
)

internal typealias MaintenanceTool = HarmonyOsMaintenanceHostBoot.Tool

internal data class MaintenanceCheckpoint(
    val schemaVersion: Int,
    val maintenanceId: String,
    val deviceId: String,
    val ownerId: String,
    val generation: String,
    val createdAt: String,
    val originalGate: MaintenanceReference,
    val originalJournal: MaintenanceReference,
    val originalReceipt: MaintenanceReference,
    val hostBefore: MaintenanceReference,
    val tools: Map<String, MaintenanceTool>,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val transition: MaintenanceReference? = null,
)

internal data class MaintenanceCallEvidence(
    val hostCallId: String,
    val phase: String,
    val purpose: String,
    val argumentsSha256: String,
    val registeredJournal: MaintenanceReference,
    val outcome: MaintenanceReference?,
    val endedJournal: MaintenanceReference?,
)

internal data class MaintenanceStep(
    val schemaVersion: Int,
    val checkpointSha256: String,
    val revision: Int,
    val previous: MaintenanceReference?,
    val phase: String,
    val observedAt: String,
    val journal: MaintenanceReference,
    val calls: List<MaintenanceCallEvidence>,
    val hostResume: MaintenanceReference?,
    val phoneBefore: MaintenanceReference?,
    val hostComplete: MaintenanceReference?,
    val phoneAfter: MaintenanceReference?,
    val termination: MaintenanceReference?,
    val cleanupBatches: List<MaintenanceReference>,
    val receipt: MaintenanceReference?,
)

/** Files are private to one owner and maintenance identifier, never overwritten. */
internal class HarmonyOsMaintenanceEvidence(val root: Path) {
    init { regularAncestors(root); require(Files.isDirectory(root)) }

    fun save(kind: String, value: Any): MaintenanceReference = saveBytes(kind, mapper.writeValueAsBytes(value))

    fun saveBytes(kind: String, bytes: ByteArray): MaintenanceReference {
        require(kind.matches(Regex("[a-z][a-z-]{0,40}")))
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val path = root.resolve("$kind-${UUID.randomUUID()}.json")
        regularAncestors(path, missingLeaf = true)
        Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).use { channel ->
            val data = java.nio.ByteBuffer.wrap(bytes)
            while (data.hasRemaining()) channel.write(data)
            (channel as java.nio.channels.FileChannel).force(true)
        }
        return MaintenanceReference(path.fileName.toString(), digest(bytes))
    }

    fun bytes(reference: MaintenanceReference): ByteArray {
        require(reference.path.matches(Regex("[a-z][a-z-]{0,40}-[0-9a-f-]{36}\\.json")))
        require(reference.sha256.matches(Regex("[0-9a-f]{64}")))
        val path = root.resolve(reference.path)
        val bytes = read(path)
        require(digest(bytes) == reference.sha256) { "Maintenance evidence fingerprint changed." }
        return bytes
    }

    fun json(reference: MaintenanceReference): JsonNode = mapper.readTree(bytes(reference))

    fun <T> document(reference: MaintenanceReference, type: Class<T>, fields: Set<String>): T {
        val node = json(reference)
        exactFields(node, fields)
        return mapper.treeToValue(node, type)
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val checkpointFields = setOf("schemaVersion", "maintenanceId", "deviceId", "ownerId", "generation", "createdAt",
            "originalGate", "originalJournal", "originalReceipt", "hostBefore", "tools")
        val stepFields = setOf("schemaVersion", "checkpointSha256", "revision", "previous", "phase", "observedAt", "journal",
            "calls", "hostResume", "phoneBefore", "hostComplete", "phoneAfter", "termination", "cleanupBatches", "receipt")
        val phases = listOf("prepared", "host_verified", "phone_prepared", "terminated", "cleaning", "cleaned", "receipt_written", "complete")

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun exactFields(node: JsonNode, fields: Set<String>) {
            require(node.isObject && node.fieldNames().asSequence().toSet() == fields) { "Unexpected maintenance document fields." }
        }

        fun regularAncestors(path: Path, missingLeaf: Boolean = false) {
            require(path.isAbsolute && path.normalize() == path)
            var current: Path? = if (missingLeaf && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) path.parent else path
            while (current != null) {
                require(!Files.isSymbolicLink(current) && current.toRealPath() == current) { "Maintenance paths cannot follow links." }
                current = current.parent
            }
        }

        fun read(path: Path, limit: Long = MAX_BYTES.toLong()): ByteArray {
            regularAncestors(path)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) in 1..limit)
            val bytes = Files.readAllBytes(path)
            require(bytes.size.toLong() in 1..limit)
            return bytes
        }

        fun fingerprint(path: Path): MaintenanceTool {
            regularAncestors(path)
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val size = Files.size(path)
            require(size > 0)
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            require(Files.size(path) == size) { "Maintenance tool changed while being read." }
            return MaintenanceTool(path.toString(), hash.digest().joinToString("") { "%02x".format(it) }, size)
        }

        fun uuid(value: String) {
            require(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) &&
                UUID.fromString(value).toString() == value)
        }
    }
}
