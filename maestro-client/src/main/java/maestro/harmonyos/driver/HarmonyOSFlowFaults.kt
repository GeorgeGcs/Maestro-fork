package maestro.harmonyos.driver

import com.fasterxml.jackson.databind.ObjectMapper

data class HarmonyOSFlowFault(
    val event: String,
    val timeMs: Long,
    val reason: String,
    val details: Map<String, String> = emptyMap(),
    val occurredAt: Boolean = false,
) {
    val message: String get() = if (reason.isBlank()) event else "$event: ${reason.lineSequence().first()}"

    fun diagnosticText(): String = buildString {
        appendLine("Event: $event")
        appendLine("Time: $timeMs")
        appendLine(reason)
        details.forEach { (field, value) -> appendLine("$field: $value") }
    }
}

/** Offset includes uncertainty from the time spent sending and receiving the device clock read. */
data class HarmonyOSFaultClock(val minimumOffsetMs: Long, val maximumOffsetMs: Long) {
    fun hostWindow(deviceTimeMs: Long): LongRange =
        (deviceTimeMs + minimumOffsetMs)..(deviceTimeMs + maximumOffsetMs)
}

data class HarmonyOSFlowFaults(
    val crash: HarmonyOSFlowFault?,
    val freeze: HarmonyOSFlowFault?,
    val events: List<HarmonyOSFlowFault> = listOfNotNull(crash, freeze),
    val clock: HarmonyOSFaultClock? = null,
)

private val mapper = ObjectMapper()
private val crashEvents = setOf("CPP_CRASH", "JS_ERROR", "RUST_PANIC", "CJ_ERROR", "APP_CRASH")
private val bundleFields = listOf("PACKAGE_NAME", "MODULE", "PROCESS_NAME", "PNAME", "bundle_name")
// The event schema includes file paths for separate device logs. Keep only the inline diagnostics.
private val diagnosticFields = listOf("PID", "SUB_EVENT_TYPE", "FINGERPRINT", "DIAG_INFO", "STACK",
    "FIRST_FRAME", "SECOND_FRAME", "LAST_FRAME", "SUMMARY", "MSG")

internal fun parseFlowFaults(
    output: String, appId: String, startMs: Long, endMs: Long, clock: HarmonyOSFaultClock? = null,
): HarmonyOSFlowFaults {
    val faults = output.lineSequence().filter { it.trimStart().startsWith("{") }.mapNotNull { line ->
        val event = runCatching { mapper.readTree(line) }.getOrNull() ?: return@mapNotNull null
        val name = event.path("name_").asText()
        if (name !in crashEvents && name != "APP_FREEZE") return@mapNotNull null
        val bundle = event.path("PACKAGE_NAME").asText().ifBlank { event.path("bundle_name").asText() }
        if (bundle.isNotBlank()) {
            if (bundle != appId) return@mapNotNull null
        } else if (bundleFields.none { event.path(it).asText() == appId }) return@mapNotNull null
        val occurredAt = event.path("HAPPEN_TIME").asLong().takeIf { it > 0 }
        val time = occurredAt ?: event.path("time_").asLong()
        if (time <= 0) return@mapNotNull null
        val hostWindow = clock?.hostWindow(time) ?: time..time
        if (hostWindow.first < startMs || hostWindow.last > endMs) return@mapNotNull null
        val reason = listOf("REASON", "SUMMARY", "MSG").firstNotNullOfOrNull { key ->
            event.path(key).asText().takeIf(String::isNotBlank)
        }.orEmpty().take(4096)
        val details = diagnosticFields.mapNotNull { field ->
            val value = event.path(field).asText()
            if (value.isBlank() || value == reason) null else field to value.take(4096)
        }.toMap()
        HarmonyOSFlowFault(name, time, reason, details, occurredAt != null)
    }.distinct().sortedBy { it.timeMs }.toList()
    val crash = faults.lastOrNull { it.event != "APP_FREEZE" }
    val freeze = faults.lastOrNull { it.event == "APP_FREEZE" }
    val recent = (faults.takeLast(16) + listOfNotNull(crash, freeze)).distinct().sortedBy { it.timeMs }
    return HarmonyOSFlowFaults(crash, freeze, recent, clock)
}
