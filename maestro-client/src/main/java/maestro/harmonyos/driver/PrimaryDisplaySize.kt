package maestro.harmonyos.driver

private val sessionHeader = Regex("(?m)^\\s*\\[SCREEN SESSION]\\h*$")
private val nextSection = Regex("(?m)^\\h*\\[")
private val displayId = Regex("(?m)^\\h*DisplayId:\\h*(\\d+)\\h*$")
private val activeMode = Regex("(?m)^\\h*activeModes<id, W, H, RS>:\\h*\\d+,\\h*(\\d+),\\h*(\\d+),\\h*\\d+\\h*$")
private val rotation = Regex("(?m)^\\h*Rotation:\\h*([0-3])\\h*$")

/** Read the primary screen session in physical pixels. An unfamiliar dump falls back to UITest capture. */
internal fun primaryDisplaySize(dump: String): Pair<Int, Int>? {
    val primary = sessionHeader.split(dump).drop(1)
        .map { nextSection.split(it, limit = 2).first() }
        .singleOrNull { displayId.find(it)?.groupValues?.get(1) == "0" }
        ?: return null
    val mode = activeMode.find(primary) ?: return null
    val width = mode.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = mode.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    if (width.toLong() * height > 16_000_000) return null
    return when (rotation.find(primary)?.groupValues?.get(1)) {
        "0", "2" -> width to height
        "1", "3" -> height to width
        else -> null
    }
}
