package maestro.harmonyos.hdc

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

class HarmonyOSUptimeObservationTest {
    private fun text(duration: String, users: String = "0 users", clock: String = "23:02:14", end: String = "\n") =
        " $clock up $duration,  $users,  load average: 12.18, 12.26, 12.44$end"
    private fun observe(duration: String, clock: String = "23:02:14") = HarmonyOSUptimeObservation.parse(text(duration, clock = clock))

    @Test fun `real observed English output preserves raw text and bounds its minute interval`() {
        val raw = " 23:02:14 up 4 days, 35 min,  0 users,  load average: 12.18, 12.26, 12.44\r\n"
        val observation = HarmonyOSUptimeObservation.parse(raw)
        assertThat(observation.raw).isEqualTo(raw)
        assertThat(observation.minutes).isEqualTo("5795")
        assertThat(observation.lowerBoundMilliseconds).isEqualTo("347700000")
        assertThat(observation.upperBoundMillisecondsExclusive).isEqualTo("347760000")
    }

    @Test fun `zero minutes singular days users and padded hours follow Toybox formatting`() {
        assertThat(observe("0 min").minutes).isEqualTo("0")
        assertThat(observe("59 min").minutes).isEqualTo("59")
        assertThat(observe(" 1:00").minutes).isEqualTo("60")
        assertThat(observe(" 9:59").minutes).isEqualTo("599")
        assertThat(observe("23:59").minutes).isEqualTo("1439")
        assertThat(HarmonyOSUptimeObservation.parse(text("1 day,  1:05", "1 user")).minutes).isEqualTo("1505")
        assertThat(observe("2 days, 10:05").minutes).isEqualTo("3485")
        val zero = observe("0 min")
        assertThat(zero.lowerBoundMilliseconds).isEqualTo("0")
        assertThat(zero.upperBoundMillisecondsExclusive).isEqualTo("60000")
    }

    @Test fun `day and minute arithmetic never rounds through double or overflows Long`() {
        for (days in listOf("9007199254740993", "18446744073709551615", "9".repeat(100))) {
            val observed = observe("$days days, 23:59")
            val expected = BigInteger(days) * BigInteger.valueOf(1440) + BigInteger.valueOf(1439)
            assertThat(observed.minutes).isEqualTo(expected.toString())
            assertThat(observed.lowerBoundMilliseconds).isEqualTo((expected * BigInteger.valueOf(60000)).toString())
            assertThat(observed.upperBoundMillisecondsExclusive).isEqualTo(((expected + BigInteger.ONE) * BigInteger.valueOf(60000)).toString())
        }
    }

    @Test fun `current time is syntactically validated but never used to infer reboot`() {
        for (clock in listOf("24:00:00", "23:60:00", "23:59:60", "1:00:00", "01:2:03", "01:02:3", "aa:bb:cc")) {
            assertThrows<IllegalArgumentException> { observe("1 min", clock) }
        }
        HarmonyOSUptimeObservation.requireRestarted(observe("2 min", "00:00:00"), observe("1 min", "23:59:59"))
        assertThrows<IllegalArgumentException> {
            HarmonyOSUptimeObservation.requireRestarted(observe("1 min", "23:59:59"), observe("2 min", "00:00:00"))
        }
    }

    @Test fun `noncanonical duration spellings and invalid ranges are refused`() {
        for (duration in listOf("0 days, 1 min", "1 days, 1 min", "2 day, 1 min", "01 day, 1 min", "-1 day, 1 min",
            "+1 day, 1 min", "1.0 days, 1 min", "1e3 days, 1 min", "60 min", "01 min", "-1 min", "+1 min",
            "1:00", "01:00", " 0:30", "24:00", " 1:60", " 1:5", "1 hour", "1 minute", "1 week, 0 days, 0 hours, 0 minutes")) {
            assertThrows<IllegalArgumentException> { observe(duration) }
        }
    }

    @Test fun `users and all load values are fully checked without float parsing`() {
        for (users in listOf("0 user", "1 users", "2 user", "01 user", "-1 users", "+1 user", "1.0 user")) {
            assertThrows<IllegalArgumentException> { HarmonyOSUptimeObservation.parse(text("1 min", users)) }
        }
        for (load in listOf("NaN", "Infinity", "-1.00", "+1.00", "1", "1.0", "1.000", "01.00", "1e3")) {
            for (original in listOf("12.18", "12.26", "12.44")) {
                assertThrows<IllegalArgumentException> { HarmonyOSUptimeObservation.parse(text("1 min").replace(original, load)) }
            }
        }
        HarmonyOSUptimeObservation.parse(text("1 min").replace("12.18, 12.26, 12.44", "0.00, 123456789.12, 1.00"))
    }

    @Test fun `extra lines diagnostics alternate output modes and missing newline are rejected`() {
        val valid = text("1 min")
        for (raw in listOf("Permission denied\n", "up 1 minute\n", "2026-10-30 01:02:03\n", valid + valid,
            valid + "\n", "warning\n" + valid, valid + "warning", valid.trim(), valid.dropLast(1), valid.dropLast(1) + "\r",
            valid.replace(" up ", " UP "), valid.replace(",  0 users", ", 0 users"), valid.replace("load average:", "load averages:"),
            valid.replace("1 min", "1 min\u0000"), valid.replace("1 min", "１ min"))) {
            assertThrows<IllegalArgumentException> { HarmonyOSUptimeObservation.parse(raw) }
        }
        HarmonyOSUptimeObservation.parse(valid)
        HarmonyOSUptimeObservation.parse(valid.replace("\n", "\r\n"))
    }

    @Test fun `the output bound is checked before unbounded integer input`() {
        val template = text("1 days, 1 min")
        val days = "9".repeat(512 - template.length + 1)
        val boundary = text("$days days, 1 min")
        assertThat(boundary.length).isEqualTo(512)
        HarmonyOSUptimeObservation.parse(boundary)
        assertThrows<IllegalArgumentException> { HarmonyOSUptimeObservation.parse(text("${days}9 days, 1 min")) }
        assertThrows<IllegalArgumentException> { HarmonyOSUptimeObservation.parse("9".repeat(10000)) }
    }

    @Test fun `only a strictly lower minute interval confirms the reboot criterion`() {
        HarmonyOSUptimeObservation.requireRestarted(observe("1 day, 0 min"), observe("23:59"))
        HarmonyOSUptimeObservation.requireRestarted(observe("1 min"), observe("0 min"))
        HarmonyOSUptimeObservation.requireRestarted(observe("9007199254740993 days, 0 min"), observe("9007199254740992 days, 23:59"))
        for ((before, after) in listOf("0 min" to "0 min", "1 min" to "1 min", "1 min" to "2 min", "23:59" to "1 day, 0 min")) {
            assertThrows<IllegalArgumentException> { HarmonyOSUptimeObservation.requireRestarted(observe(before), observe(after)) }
        }
    }
}
