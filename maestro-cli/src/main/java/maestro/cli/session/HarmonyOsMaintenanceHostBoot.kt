package maestro.cli.session

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigInteger
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Validates observations only. It neither observes the host nor authorizes device cleanup. */
internal object HarmonyOsMaintenanceHostBoot {
    data class Tool(val path: String, val sha256: String, val bytes: Long)
    data class Observation(
        val observedAt: String,
        val uptimeMilliseconds: String,
        val lastBootUpTime: String,
        val machineIdSha256: String,
        val powershellVersion: String,
    )

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val uint64Max = BigInteger("18446744073709551615")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val unsignedDecimal = Regex("0|[1-9][0-9]{0,19}")
    private val version = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")
    private val utc = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
    private val utcFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter(Locale.ROOT)
    private val driveRoot = Regex("^[A-Za-z]:[\\\\/]")
    private val uncRoot = Regex("^\\\\\\\\[^\\\\/]+[\\\\/][^\\\\/]+[\\\\/]")

    fun parse(node: JsonNode, powershell: Tool, script: Tool): Observation {
        validateTool(powershell)
        validateTool(script)
        fields(node, "protocol", "schemaVersion", "provider", "observedAt", "uptimeMilliseconds",
            "lastBootUpTime", "machineIdSha256", "tools")
        require(text(node, "protocol") == "maestro.harmonyos.host-boot-observation" &&
            numberIsOne(node["schemaVersion"])) { "Unsupported host boot observation protocol/version." }
        val provider = node["provider"]
        fields(provider, "name", "version")
        require(text(provider, "name") == "windows-gettickcount64-cim" && numberIsOne(provider["version"])) {
            "Unsupported boot provider."
        }
        val tools = node["tools"]
        fields(tools, "powershell", "script")
        fingerprint(tools["powershell"], powershell, powershell = true)
        fingerprint(tools["script"], script, powershell = false)
        return Observation(text(node, "observedAt"), text(node, "uptimeMilliseconds"),
            text(node, "lastBootUpTime"), text(node, "machineIdSha256"), text(tools["powershell"], "version"))
            .also(::validateObservation)
    }

    fun requireRestarted(before: Observation, after: Observation) {
        pair(before, after)
        require(after.lastBootUpTime != before.lastBootUpTime) { "boot_time_unchanged" }
        require(ticks(after.uptimeMilliseconds) < ticks(before.uptimeMilliseconds)) { "uptime_did_not_decrease" }
    }

    // Caller must independently validate the immutable, explicitly approved tool transition.
    fun requireRestartedAfterToolMigration(before: Observation, after: Observation) {
        validateObservation(before); validateObservation(after)
        require(before.machineIdSha256 == after.machineIdSha256) { "machine_mismatch" }
        require(after.observedAt > before.observedAt) { "observation_order_not_confirmed" }
        require(after.lastBootUpTime != before.lastBootUpTime) { "boot_time_unchanged" }
        require(ticks(after.uptimeMilliseconds) < ticks(before.uptimeMilliseconds)) { "uptime_did_not_decrease" }
    }

    fun requireSameBoot(before: Observation, after: Observation) {
        pair(before, after)
        require(after.lastBootUpTime == before.lastBootUpTime) { "boot_time_changed" }
        require(ticks(after.uptimeMilliseconds) >= ticks(before.uptimeMilliseconds)) { "uptime_decreased" }
    }

    private fun pair(before: Observation, after: Observation) {
        validateObservation(before)
        validateObservation(after)
        require(before.machineIdSha256 == after.machineIdSha256) { "machine_mismatch" }
        require(after.observedAt > before.observedAt) { "observation_order_not_confirmed" }
        require(after.powershellVersion == before.powershellVersion) { "powershell_version_mismatch" }
    }

    private fun validateObservation(value: Observation) {
        canonicalUtc(value.observedAt)
        canonicalUtc(value.lastBootUpTime)
        ticks(value.uptimeMilliseconds)
        require(sha256.matches(value.machineIdSha256)) { "Invalid machineIdSha256." }
        require(version.matches(value.powershellVersion)) { "Invalid PowerShell version." }
    }

    private fun ticks(value: String): BigInteger {
        require(unsignedDecimal.matches(value)) { "uptimeMilliseconds must be an unsigned 64-bit decimal string." }
        return BigInteger(value).also {
            require(it <= uint64Max) { "uptimeMilliseconds exceeds UInt64." }
        }
    }

    private fun canonicalUtc(value: String) {
        require(utc.matches(value)) { "Timestamp must be canonical UTC with milliseconds." }
        val parsed = try { Instant.parse(value) } catch (error: Exception) {
            throw IllegalArgumentException("Invalid UTC timestamp.", error)
        }
        require(utcFormatter.format(parsed) == value) { "Timestamp must be canonical UTC with milliseconds." }
    }

    private fun fingerprint(node: JsonNode, expected: Tool, powershell: Boolean) {
        if (powershell) fields(node, "path", "sha256", "bytes", "version") else fields(node, "path", "sha256", "bytes")
        val actual = Tool(text(node, "path"), text(node, "sha256"), safeBytes(node["bytes"]))
        validateTool(actual)
        if (powershell) require(version.matches(text(node, "version"))) { "Invalid PowerShell version." }
        require(normalizeWindows(actual.path) == normalizeWindows(expected.path) &&
            actual.sha256 == expected.sha256 && actual.bytes == expected.bytes) { "Observation tool does not match its trusted freeze." }
    }

    private fun validateTool(tool: Tool) {
        normalizeWindows(tool.path)
        require(sha256.matches(tool.sha256)) { "Tool sha256 must be lowercase SHA-256." }
        require(tool.bytes in 1..MAX_SAFE_INTEGER) { "Tool bytes must be a positive safe integer." }
    }

    private fun safeBytes(node: JsonNode): Long {
        require(node.isNumber) { "Tool bytes must be a number." }
        val value = node.doubleValue()
        require(value.isFinite() && value >= 1 && value <= MAX_SAFE_INTEGER.toDouble() && value == value.toLong().toDouble()) {
            "Tool bytes must be a positive safe integer."
        }
        return value.toLong()
    }

    // Lexical win32.normalize comparison, independent of the OS running the parser.
    // This is a tool identity comparison, not a filesystem/symlink ownership check.
    private fun normalizeWindows(value: String): String {
        require(value.length <= 32767 && (driveRoot.containsMatchIn(value) || uncRoot.containsMatchIn(value)) &&
            value.none { it.code in 0..31 || it.code == 127 } &&
            !value.startsWith("\\\\?\\") && !value.startsWith("\\\\.\\")) { "Tool path must be an absolute Windows file path." }
        val raw = value.replace('/', '\\')
        val drive = driveRoot.containsMatchIn(value)
        val basenameInput = if (drive) raw.substring(2) else raw
        require(basenameInput.trimEnd('\\').substringAfterLast('\\').isNotEmpty()) { "Tool path has no basename." }
        val components = raw.substring(2).split('\\').filter { it.isNotEmpty() }
        val root = if (drive) raw.substring(0, 2) + "\\" else "\\\\${components[0]}\\${components[1]}\\"
        val tail = if (drive) components else components.drop(2)
        val normalized = mutableListOf<String>()
        for (component in tail) when (component) {
            "." -> Unit
            ".." -> if (normalized.isNotEmpty()) { normalized.removeAt(normalized.lastIndex) }
            else -> normalized += component
        }
        val trailing = if (normalized.isNotEmpty() && raw.endsWith('\\')) "\\" else ""
        return (root + normalized.joinToString("\\") + trailing).lowercase(Locale.ROOT)
    }

    private fun numberIsOne(node: JsonNode) = node.isNumber && node.doubleValue() == 1.0
    private fun text(node: JsonNode, name: String): String {
        require(node[name]?.isTextual == true) { "$name must be a string." }
        return node[name].textValue()
    }
    private fun fields(node: JsonNode, vararg names: String) {
        require(node.isObject && node.fieldNames().asSequence().toSet() == names.toSet()) { "Missing or unexpected observation fields." }
    }
}
