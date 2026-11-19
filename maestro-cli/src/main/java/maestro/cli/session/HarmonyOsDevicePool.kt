package maestro.cli.session

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.nio.file.ExtendedOpenOption
import maestro.cli.harmonyos.AgentError
import maestro.cli.harmonyos.AgentProtocolException
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcException
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

internal object HarmonyOsDevicePool {
    private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val deviceIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val deviceTypes = setOf("phone", "tablet", "emulator")
    val systems = setOf("harmonyos", "openharmony")
    const val STATE_DIRECTORY_ENV = "MAESTRO_HARMONYOS_SHARED_DEVICE_STATE"

    data class Device(val id: String, val kind: String, val system: String, val api: Int)
    data class Filter(val kind: String? = null, val system: String? = null, val minApi: Int? = null, val maxApi: Int? = null) {
        init {
            require(kind == null || kind in deviceTypes) { "Unknown device kind." }
            require(system == null || system in systems) { "Unknown device system." }
            require((minApi == null || minApi in 1..999) && (maxApi == null || maxApi in 1..999) &&
                (minApi == null || maxApi == null || minApi <= maxApi)) { "Invalid API range." }
        }
        fun matches(device: Device): Boolean = (kind == null || kind == device.kind) &&
            (system == null || system == device.system) &&
            (minApi == null || device.api >= minApi) && (maxApi == null || device.api <= maxApi)
    }

    data class CiAttempt(val runId: String, val attemptId: String)

    fun readInventory(path: Path): List<Device> {
        val input = readDocument(path.toAbsolutePath().normalize(), 256 * 1024)
        val rows = input.path("devices")
        require(input.isObject && input.fieldNames().asSequence().toSet() == setOf("schemaVersion", "devices") &&
            input.path("schemaVersion").isIntegralNumber && input.path("schemaVersion").asInt() == 1 &&
            rows.isArray && rows.size() in 1..32) { "Invalid device inventory." }
        return rows.map { row ->
            require(row.isObject && row.fieldNames().asSequence().toSet() == setOf("id", "kind", "system", "api")) {
                "Invalid device inventory entry."
            }
            val id = row.path("id").asText()
            val kind = row.path("kind").asText()
            val system = row.path("system").asText()
            val api = row.path("api")
            require(row.path("id").isTextual && id.matches(deviceIdPattern) && kind in deviceTypes && system in systems &&
                api.isIntegralNumber && api.canConvertToInt() && api.asInt() in 1..999) { "Invalid device inventory entry." }
            Device(id, kind, system, api.asInt())
        }.also { devices -> require(devices.distinctBy { it.id }.size == devices.size) { "Duplicate device inventory entry." } }
    }

    /** Checks are repeated inside the native session lock before the driver starts. */
    private fun ciOwnsGate(deviceId: String, directory: Path, attempt: CiAttempt? = null): Boolean {
        val path = directory.toAbsolutePath().normalize().resolve("${HarmonyOsSessionSupport.lockName(deviceId)}.json")
        val state = try {
            checkLocalPath(path)
            require(Files.isDirectory(path.parent, LinkOption.NOFOLLOW_LINKS))
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return false
            readDocument(path, 4 * 1024 * 1024)
        } catch (_: Exception) {
            throw unavailable("DEVICE_STATE_UNKNOWN", "Shared CI reservation state could not be verified.")
        }
        val status = state.path("state").asText()
        val kind = state.path("kind").asText("single")
        val launch = state.path("launchId").asText()
        val validIdentity = when (kind) {
            "single" -> state.path("runId").asText().matches(uuid) && state.path("attemptId").asText().matches(uuid) &&
                rooted(state.path("controlRecordPath").asText())
            "validation" -> state.path("unitInvocationId").asText() == launch && rooted(state.path("controlIndexPath").asText())
            else -> false
        }
        if (state.path("protocol").asText() != "maestro.harmonyos.ci-device" ||
            !state.path("schemaVersion").isIntegralNumber || state.path("schemaVersion").asInt() != 1 ||
            state.path("deviceId").asText() != deviceId || !launch.matches(uuid) || !validIdentity ||
            status !in setOf("launching", "running", "needs_inspection", "released")) {
            throw unavailable("DEVICE_STATE_UNKNOWN", "Shared CI reservation state is invalid.")
        }
        if (status == "released") return false
        if (status == "running" && kind == "single" && attempt != null &&
            attempt.runId == state.path("runId").asText() && attempt.attemptId == state.path("attemptId").asText()) return true
        throw unavailable(if (status == "needs_inspection") "DEVICE_RECOVERY_REQUIRED" else "DEVICE_BUSY",
            "A local CI reservation still owns this device. Confirm release before allocating it again.")
    }

    /**
     * The matching CI worker holds the same file open with FileShare.None. Other callers must
     * hold it for the session, including while the lower-level driver writes its cleanup receipt.
     */
    fun <T> withAllocationGate(deviceId: String, directory: Path, attempt: CiAttempt? = null, block: () -> T): T {
        if (ciOwnsGate(deviceId, directory, attempt)) return block()
        val path = directory.toAbsolutePath().normalize().resolve("${HarmonyOsSessionSupport.lockName(deviceId)}.ci.lock")
        try {
            checkLocalPath(path)
        } catch (_: Exception) {
            throw unavailable("DEVICE_STATE_UNKNOWN", "Shared device reservation lock path could not be verified.")
        }
        val options = mutableSetOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        if (File.separatorChar == '\\') {
            options += setOf(ExtendedOpenOption.NOSHARE_READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)
        }
        val channel = try {
            FileChannel.open(path, options)
        } catch (_: IOException) {
            throw unavailable("DEVICE_BUSY", "The shared reservation lock could not be acquired.")
        }
        channel.use {
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            lock ?: throw unavailable("DEVICE_BUSY", "Another local worker owns the shared reservation lock.")
            lock.use {
                ciOwnsGate(deviceId, directory)
                return block()
            }
        }
    }

    fun select(devices: List<Device>, filter: Filter, directory: Path, wait: Duration, hdc: HdcClient): Device {
        require(!wait.isNegative && wait <= Duration.ofSeconds(120))
        val matching = devices.filter(filter::matches)
        if (matching.isEmpty()) throw unavailable("DEVICE_POOL_NO_MATCH", "No registered device matches the requested filters.")
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Shared state directory must exist." }
        val stop = System.nanoTime() + wait.toNanos()
        do {
            val roundEnds = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            val attemptEnd = if (wait.isZero) roundEnds else minOf(roundEnds, stop)
            val available = hdc.listDevices().toSet()
            for (candidate in matching) {
                if (candidate.id !in available ||
                    HarmonyOsSessionSupport.probeDeviceLock(candidate.id) != HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE) continue
                try {
                    withAllocationGate(candidate.id, directory) { }
                } catch (_: AgentProtocolException) {
                    continue
                }
                if (verifyRuntime(candidate, hdc, attemptEnd)) return candidate
            }
            val remaining = stop - System.nanoTime()
            if (remaining <= 0) break
            Thread.sleep(minOf(500, Duration.ofNanos(remaining).toMillis().coerceAtLeast(1)))
        } while (System.nanoTime() < stop)
        throw unavailable("DEVICE_POOL_UNAVAILABLE", "No matching connected device is available with confirmed state.")
    }

    private fun verifyRuntime(candidate: Device, hdc: HdcClient, deadline: Long): Boolean {
        val device = HdcHarmonyOSDevice(candidate.id, hdc)
        fun read(key: String): String? {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            return try {
                device.execute(listOf("param", "get", key), minOf(Duration.ofSeconds(2), Duration.ofNanos(remaining))).trim()
            } catch (_: HdcException) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Device inventory probe interrupted.")
                null
            }
        }
        if (read("const.ohos.apiversion")?.toIntOrNull() != candidate.api ||
            read("const.product.os.dist.name")?.equals(candidate.system, ignoreCase = true) != true) return false
        val release = read("const.product.software.version")?.takeIf(String::isNotBlank) ?: return false
        val emulator = release.startsWith("emulator ", ignoreCase = true)
        return if (candidate.kind == "emulator") emulator else !emulator &&
            read("const.product.devicetype")?.equals(candidate.kind, ignoreCase = true) == true
    }

    private fun rooted(value: String): Boolean = runCatching { Path.of(value).isAbsolute }.getOrDefault(false)

    private fun checkLocalPath(path: Path) {
        var cursor: Path? = path
        while (cursor != null) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor) && cursor.toRealPath() == cursor.toAbsolutePath().normalize()) {
                    "Device state must not follow file or directory links."
                }
            }
            cursor = cursor.parent
        }
    }

    private fun readDocument(path: Path, limit: Int): JsonNode {
        checkLocalPath(path)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Expected a local file." }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
        require(bytes.size <= limit) { "Device allocation file is too large." }
        return mapper.readTree(bytes) ?: error("Empty device allocation file.")
    }

    private fun unavailable(code: String, message: String) = AgentProtocolException(AgentError("environment", code, message))
}
