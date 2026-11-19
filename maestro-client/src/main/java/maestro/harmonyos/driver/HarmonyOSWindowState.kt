package maestro.harmonyos.driver

import maestro.harmonyos.hdc.HdcException

internal class HarmonyOSWindowFormatException(message: String) : HdcException(message)

/** Parse columns by their names, retaining window ownership across rotated and multi-window layouts. */
internal class HarmonyOSWindowState(private val text: String) {
    private val columns = text.lineSequence().map { it.substringBefore('[').trim().split(Regex("\\s+")) }
        .firstOrNull { it.contains("Pid") && (it.contains("WinId") || it.contains("WindowId")) }
        ?: throw HarmonyOSWindowFormatException("WindowManagerService did not provide a recognized window table.")
    private val rows = text.lineSequence().filter { '[' in it && it.trimStart().firstOrNull()?.isLetter() == true }
        .mapNotNull { line ->
            val values = line.substringBefore('[').trim().split(Regex("\\s+"))
            if (values.size < columns.size || values.first() == "WindowName") null
            else columns.drop(1).zip(values.takeLast(columns.size - 1)).toMap()
        }.toList()

    fun focusedPid(): String? {
        val focus = Regex("(?m)^\\s*Focus window:\\s*(-?\\d+)\\s*$").findAll(text).map { it.groupValues[1] }.toList()
        if (focus.size != 1) throw HarmonyOSWindowFormatException("WindowManagerService returned no unique focused window.")
        if (focus.single() == "-1") return null
        val idColumn = if ("WinId" in columns) "WinId" else "WindowId"
        val owners = rows.filter { it[idColumn] == focus.single() }.mapNotNull { it["Pid"] }.distinct()
        // Focus and window rows can change between samples while an Ability starts.
        if (owners.isEmpty()) return null
        return owners.singleOrNull()?.takeIf { it.toLongOrNull() != null }
            ?: throw HarmonyOSWindowFormatException("The focused window has no unique process owner.")
    }

    // OpenHarmony WindowType.WINDOW_TYPE_INPUT_METHOD_FLOAT is 2105.
    // Negative Z-order is an inactive window retained by the scene board.
    fun keyboardVisible(): Boolean {
        if ("Type" !in columns || "ZOrd" !in columns) {
            throw HarmonyOSWindowFormatException("The window table has no keyboard visibility columns.")
        }
        return rows.any { it["Type"] == "2105" && (it["ZOrd"]?.toIntOrNull() ?: -1) >= 0 }
    }
}
