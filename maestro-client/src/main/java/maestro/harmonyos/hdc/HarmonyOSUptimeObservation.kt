package maestro.harmonyos.hdc

import java.math.BigInteger

/**
 * Supported format/semantics: no-argument English Toybox uptime, based on
 * https://raw.githubusercontent.com/openharmony/third_party_toybox/master/toys/other/uptime.c
 * That implementation reads sysinfo and divides uptime seconds by 60 before formatting.
 * This contract does not identify the device vendor's exact implementation or a boot UUID.
 */
class HarmonyOSUptimeObservation private constructor(val raw: String, val minutes: String) {
    /** Interval for the provider's reported uptime, not a millisecond-precision clock sample. */
    val lowerBoundMilliseconds: String get() = (BigInteger(minutes) * MILLIS_PER_MINUTE).toString()
    val upperBoundMillisecondsExclusive: String get() = ((BigInteger(minutes) + BigInteger.ONE) * MILLIS_PER_MINUTE).toString()

    companion object {
        const val PROVIDER = "toybox-uptime-english-floor-minute-v1"
        private const val MAX_OUTPUT_LENGTH = 512
        private val MILLIS_PER_MINUTE = BigInteger.valueOf(60_000)
        private val MINUTES_PER_DAY = BigInteger.valueOf(1_440)
        private val MINUTES_PER_HOUR = BigInteger.valueOf(60)
        private const val DECIMAL_LOAD = "(?:0|[1-9][0-9]*)\\.[0-9]{2}"
        // Spaces mirror the published printf format, including %2d for nonzero hours.
        // Exactly one LF or CRLF is accepted; no trimming can hide diagnostics or another line.
        private val output = Regex(
            " ([0-9]{2}):([0-5][0-9]):([0-5][0-9]) up " +
                "(?:([1-9][0-9]*) (day|days), )?" +
                "(?:( [1-9]|1[0-9]|2[0-3]):([0-5][0-9])|([0-9]|[1-5][0-9]) min),  " +
                "(0|[1-9][0-9]*) (user|users),  load average: " +
                "$DECIMAL_LOAD, $DECIMAL_LOAD, $DECIMAL_LOAD\\r?\\n"
        )

        fun parse(raw: String): HarmonyOSUptimeObservation {
            require(raw.length <= MAX_OUTPUT_LENGTH) { "Uptime output exceeds 512 characters." }
            val match = output.matchEntire(raw) ?: throw IllegalArgumentException("Unsupported uptime output.")
            require(match.groupValues[1].toInt() <= 23) { "Invalid current-time hour in uptime output." }
            val days = match.groupValues[4].takeIf { it.isNotEmpty() }?.let(::BigInteger) ?: BigInteger.ZERO
            if (match.groupValues[4].isNotEmpty()) {
                require(match.groupValues[5] == if (days == BigInteger.ONE) "day" else "days") { "Invalid day plurality." }
            }
            val users = BigInteger(match.groupValues[9])
            require(match.groupValues[10] == if (users == BigInteger.ONE) "user" else "users") { "Invalid user plurality." }
            val withinDay = if (match.groupValues[6].isNotEmpty()) {
                BigInteger(match.groupValues[6].trim()) * MINUTES_PER_HOUR + BigInteger(match.groupValues[7])
            } else BigInteger(match.groupValues[8])
            return HarmonyOSUptimeObservation(raw, (days * MINUTES_PER_DAY + withinDay).toString())
        }

        /** Caller must bind both observations to the same explicit device and trusted command. */
        fun requireRestarted(before: HarmonyOSUptimeObservation, after: HarmonyOSUptimeObservation) {
            require(BigInteger(after.minutes) < BigInteger(before.minutes)) { "Uptime did not strictly decrease." }
        }
    }
}
