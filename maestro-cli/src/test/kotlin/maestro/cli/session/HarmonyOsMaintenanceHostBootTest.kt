package maestro.cli.session

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.cli.session.HarmonyOsMaintenanceHostBoot.Observation
import maestro.cli.session.HarmonyOsMaintenanceHostBoot.Tool
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HarmonyOsMaintenanceHostBootTest {
    private val mapper = jacksonObjectMapper()
    private val powershell = Tool("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "a".repeat(64), 100)
    private val script = Tool("D:\\checkout\\scripts\\recovery\\observe-windows-boot.ps1", "b".repeat(64), 200)
    private fun fixture(): ObjectNode = mapper.valueToTree(mapOf(
        "protocol" to "maestro.harmonyos.host-boot-observation", "schemaVersion" to 1,
        "provider" to mapOf("name" to "windows-gettickcount64-cim", "version" to 1),
        "observedAt" to "2026-10-30T01:00:00.000Z", "uptimeMilliseconds" to "9007199254740993",
        "lastBootUpTime" to "2026-10-20T00:00:00.000Z", "machineIdSha256" to "c".repeat(64),
        "tools" to mapOf(
            "powershell" to mapOf("path" to powershell.path, "sha256" to powershell.sha256, "bytes" to powershell.bytes, "version" to "5.1.26100.1"),
            "script" to mapOf("path" to script.path, "sha256" to script.sha256, "bytes" to script.bytes)),
    ))
    private fun parse(node: JsonNode = fixture()) = HarmonyOsMaintenanceHostBoot.parse(node, powershell, script)
    private fun later(value: Observation = parse()) = value.copy(observedAt = "2026-10-30T02:00:00.000Z")
    private fun tool(node: ObjectNode, name: String) = node["tools"][name] as ObjectNode

    @Test fun `UInt64 values remain exact above double precision and at the maximum`() {
        for (tick in listOf("0", "9007199254740993", "18446744073709551615")) {
            val node = fixture().put("uptimeMilliseconds", tick)
            assertThat(parse(node).uptimeMilliseconds).isEqualTo(tick)
        }
        val node = fixture()
        val saved = parse(node)
        node.put("uptimeMilliseconds", "1")
        assertThat(saved.uptimeMilliseconds).isEqualTo("9007199254740993")
    }

    @Test fun `invalid unsigned encodings numbers and overflow are rejected`() {
        for (value in listOf(null, 0, 9007199254740992L, "", "00", "01", "-1", "+1", "1.0", "1e3", " 1", "1 ",
            "18446744073709551616", "9".repeat(100), listOf("1"))) {
            val node = fixture().set<ObjectNode>("uptimeMilliseconds", mapper.valueToTree(value))
            assertThrows<IllegalArgumentException> { parse(node) }
        }
    }

    @Test fun `missing and unknown fields are rejected at every object level`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.remove("observedAt"); Unit }, { it.put("handoffReady", true); Unit },
            { (it["provider"] as ObjectNode).put("extra", true); Unit },
            { (it["tools"] as ObjectNode).put("extra", true); Unit },
            { tool(it, "powershell").remove("version"); Unit },
            { tool(it, "script").put("version", "7.5.0"); Unit },
        )
        for (mutate in mutations) {
            val node = fixture(); mutate(node)
            assertThrows<IllegalArgumentException> { parse(node) }
        }
    }

    @Test fun `protocol provider and version require the documented values and types`() {
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("protocol", "other"); Unit }, { it.put("schemaVersion", 2); Unit }, { it.put("schemaVersion", "1"); Unit },
            { (it["provider"] as ObjectNode).put("name", "other"); Unit },
            { (it["provider"] as ObjectNode).put("version", 2); Unit },
            { (it["provider"] as ObjectNode).put("version", "1"); Unit },
            { it.set<ObjectNode>("provider", mapper.createArrayNode()); Unit },
        )
        for (mutate in mutations) { val node = fixture(); mutate(node); assertThrows<IllegalArgumentException> { parse(node) } }
    }

    @Test fun `UTC timestamps require exact valid millisecond Z form`() {
        for (field in listOf("observedAt", "lastBootUpTime")) {
            for (value in listOf("2026-02-30T00:00:00.000Z", "2026-10-30T01:00:00+00:00", "2026-10-30T01:00:00Z",
                "2026-10-30T01:00:00.0000Z", "2026-10-30T01:00:00.000z", "2026-10-30T24:00:00.000Z", "2016-12-31T23:59:60.000Z")) {
                assertThrows<IllegalArgumentException> { parse(fixture().put(field, value)) }
            }
            assertThrows<IllegalArgumentException> { parse(fixture().putNull(field)) }
        }
        assertThat(parse(fixture().put("observedAt", "0000-01-01T00:00:00.000Z")).observedAt).startsWith("0000")
    }

    @Test fun `hashes and PowerShell versions have strict textual forms`() {
        for (hash in listOf("", "C".repeat(64), "c".repeat(63))) {
            assertThrows<IllegalArgumentException> { parse(fixture().put("machineIdSha256", hash)) }
        }
        assertThrows<IllegalArgumentException> { parse(fixture().set<ObjectNode>("machineIdSha256", mapper.valueToTree(listOf("c".repeat(64))))) }
        for (value in listOf("", "7.5", "7.5.0-preview", "7.5.0.1.2", " 7.5.0")) {
            val node = fixture(); tool(node, "powershell").put("version", value)
            assertThrows<IllegalArgumentException> { parse(node) }
        }
        val node = fixture(); tool(node, "powershell").put("version", "7.5.0")
        assertThat(parse(node).powershellVersion).isEqualTo("7.5.0")
    }

    @Test fun `both tools must match independently frozen paths hashes and sizes`() {
        for (name in listOf("powershell", "script")) {
            for (field in listOf("path", "sha256", "bytes")) {
                val node = fixture(); val target = tool(node, name)
                when (field) {
                    "path" -> target.put(field, "C:\\other\\tool.exe")
                    "sha256" -> target.put(field, "d".repeat(64))
                    else -> target.put(field, 101)
                }
                assertThrows<IllegalArgumentException> { parse(node) }
            }
        }
        for (invalid in listOf(script.copy(path = "relative.ps1"), script.copy(sha256 = "wrong"), script.copy(bytes = 0),
            script.copy(bytes = 9_007_199_254_740_992L))) {
            assertThrows<IllegalArgumentException> { HarmonyOsMaintenanceHostBoot.parse(fixture(), powershell, invalid) }
        }
    }

    @Test fun `Windows path comparison matches drive UNC slash case and dot normalization`() {
        val node = fixture()
        tool(node, "script").put("path", "d:/CHECKOUT/scripts/other/../recovery/observe-windows-boot.ps1")
        parse(node)
        val unc = script.copy(path = "\\\\server\\share\\dir\\observe.ps1")
        tool(node, "script").put("path", "\\\\SERVER/share/dir/./observe.ps1")
        HarmonyOsMaintenanceHostBoot.parse(node, powershell, unc)
        for (invalid in listOf("relative.ps1", "\\rooted.ps1", "C:relative.ps1", "C:\\", "\\\\?\\C:\\script.ps1", "\\\\.\\pipe\\tool",
            "C:\\bad\u0000.ps1", "C:\\bad\n.ps1", "C:\\bad\u007f.ps1", "C:\\" + "a".repeat(32768))) {
            val changed = fixture(); tool(changed, "script").put("path", invalid)
            assertThrows<IllegalArgumentException> { HarmonyOsMaintenanceHostBoot.parse(changed, powershell, script.copy(path = invalid)) }
        }
        val trailing = fixture(); tool(trailing, "script").put("path", script.path + "\\")
        assertThrows<IllegalArgumentException> { parse(trailing) }
    }

    @Test fun `tool byte counts obey JavaScript safe integer boundaries`() {
        for (value in listOf(0, -1, 1.5, 9_007_199_254_740_992L, "200", listOf(200))) {
            val node = fixture(); tool(node, "script").set<ObjectNode>("bytes", mapper.valueToTree(value))
            assertThrows<IllegalArgumentException> { parse(node) }
        }
        val node = fixture(); tool(node, "script").put("bytes", 9_007_199_254_740_991L)
        HarmonyOsMaintenanceHostBoot.parse(node, powershell, script.copy(bytes = 9_007_199_254_740_991L))
        val integralDouble = fixture(); tool(integralDouble, "script").put("bytes", 200.0)
        parse(integralDouble)
    }

    @Test fun `restart compares UInt64 without rounding adjacent values`() {
        val before = parse()
        HarmonyOsMaintenanceHostBoot.requireRestarted(before, later().copy(uptimeMilliseconds = "9007199254740992", lastBootUpTime = "2026-10-30T01:30:00.000Z"))
        HarmonyOsMaintenanceHostBoot.requireRestarted(before.copy(uptimeMilliseconds = "18446744073709551615"),
            later().copy(uptimeMilliseconds = "18446744073709551614", lastBootUpTime = "2026-10-30T01:30:00.000Z"))
    }

    @Test fun `restart requires both a changed boot time and decreasing uptime`() {
        val before = parse()
        assertThrows<IllegalArgumentException> { HarmonyOsMaintenanceHostBoot.requireRestarted(before, later().copy(uptimeMilliseconds = "1")) }
        for (tick in listOf(before.uptimeMilliseconds, "9007199254740994")) {
            assertThrows<IllegalArgumentException> {
                HarmonyOsMaintenanceHostBoot.requireRestarted(before, later().copy(uptimeMilliseconds = tick, lastBootUpTime = "2026-10-30T01:30:00.000Z"))
            }
        }
    }

    @Test fun `same boot accepts equal or greater UInt64 uptime and rejects changes`() {
        val before = parse()
        for (tick in listOf(before.uptimeMilliseconds, "9007199254740994", "18446744073709551615")) {
            HarmonyOsMaintenanceHostBoot.requireSameBoot(before, later().copy(uptimeMilliseconds = tick))
        }
        assertThrows<IllegalArgumentException> { HarmonyOsMaintenanceHostBoot.requireSameBoot(before, later().copy(uptimeMilliseconds = "9007199254740992")) }
        assertThrows<IllegalArgumentException> { HarmonyOsMaintenanceHostBoot.requireSameBoot(before, later().copy(lastBootUpTime = "2026-10-30T01:30:00.000Z")) }
    }

    @Test fun `both comparisons require same host forward observations and same PowerShell version`() {
        val comparisons = listOf(HarmonyOsMaintenanceHostBoot::requireRestarted, HarmonyOsMaintenanceHostBoot::requireSameBoot)
        for (compare in comparisons) {
            for (after in listOf(later().copy(machineIdSha256 = "d".repeat(64)), parse(), parse().copy(observedAt = "2026-10-29T23:59:59.999Z"),
                later().copy(powershellVersion = "7.5.0"))) {
                assertThrows<IllegalArgumentException> { compare(parse(), after) }
            }
        }
    }

    @Test fun `directly constructed invalid observations cannot bypass pair validation`() {
        val comparisons = listOf(HarmonyOsMaintenanceHostBoot::requireRestarted, HarmonyOsMaintenanceHostBoot::requireSameBoot)
        for (compare in comparisons) for (invalid in listOf(later().copy(uptimeMilliseconds = "-1"),
            later().copy(uptimeMilliseconds = "18446744073709551616"), later().copy(observedAt = "tomorrow"),
            later().copy(lastBootUpTime = "invalid"), later().copy(machineIdSha256 = "bad"), later().copy(powershellVersion = "bad"))) {
            assertThrows<IllegalArgumentException> { compare(parse(), invalid) }
        }
    }
}
