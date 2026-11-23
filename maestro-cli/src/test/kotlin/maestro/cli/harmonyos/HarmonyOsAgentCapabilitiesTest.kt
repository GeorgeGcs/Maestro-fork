package maestro.cli.harmonyos

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.session.HarmonyOsSessionSupport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HarmonyOsAgentCapabilitiesTest {
    private val mapper = jacksonObjectMapper()
    @TempDir lateinit var temporary: Path
    private class Probe : AgentEnvironmentProbe {
        var targets = listOf("test-target")
        var lock = HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE
        var discoveryFailure: Exception? = null
        var calls = 0
        val shell = mutableListOf<List<String>>()
        var bundle = ""
        var rpcListener: Boolean? = null
        override fun layoutRpcListener(deviceId: String) = rpcListener
        override fun devices(): List<String> { calls++; discoveryFailure?.let { throw it }; return targets }
        override fun lockStatus(deviceId: String) = lock
        override fun execute(deviceId: String, arguments: List<String>): String {
            assertEquals("test-target", deviceId)
            shell += arguments
            return when (arguments) {
                listOf("which", "uitest") -> "/system/bin/uitest"
                listOf("which", "mediatool") -> "/system/bin/mediatool"
                listOf("bm", "dump", "-n", "com.example.demo") -> bundle
                else -> fail("Unexpected device command: $arguments")
            }
        }
    }

    private fun preflight(probe: Probe, json: String = """{"deviceId":"test-target","commands":["takeScreenshot"]}""") =
        mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(HarmonyOsAgentCapabilities(probe).preflight(mapper.readTree(json)))

    @Test fun `describe does not contact devices and does not claim verification`() {
        val probe = Probe()
        val result = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(HarmonyOsAgentCapabilities(probe).describe())
        assertEquals(0, probe.calls)
        assertTrue(probe.shell.isEmpty())
        assertEquals(10, result["methods"].size())
        assertTrue(result["commands"].all { it["verification"]["state"].asText() == "unverified" })
    }

    @Test fun `missing explicit target is blocked without fallback or shell calls`() {
        val probe = Probe().apply { targets = listOf("another-target") }
        val result = preflight(probe)
        assertEquals("blocked", result["readiness"].asText())
        assertTrue(result["checks"].any { it["code"].asText() == "DEVICE_NOT_FOUND" })
        assertTrue(probe.shell.isEmpty())
    }

    @Test fun `busy target is blocked without competing shell calls`() {
        val probe = Probe().apply { lock = HarmonyOsSessionSupport.DeviceLockStatus.BUSY }
        assertEquals("blocked", preflight(probe)["readiness"].asText())
        assertTrue(probe.shell.isEmpty())
    }

    @Test fun `failed discovery is unknown rather than absent`() {
        val probe = Probe().apply { discoveryFailure = IllegalStateException("unavailable") }
        val result = preflight(probe)
        assertEquals("unknown", result["readiness"].asText())
        assertFalse(result["checks"].any { it["code"].asText() == "DEVICE_NOT_FOUND" })
        assertTrue(probe.shell.isEmpty())
    }

    @Test fun `ready describes only confirmed screenshot prerequisites`() {
        val probe = Probe()
        assertEquals("ready", preflight(probe)["readiness"].asText())
        assertEquals(listOf(listOf("which", "uitest")), probe.shell)
        val screen = preflight(probe, """{"deviceId":"test-target","commands":["tapOn"]}""")
        assertEquals("unknown", screen["readiness"].asText())
        assertTrue(screen["checks"].any { it["name"].asText() == "screenUnlocked" && it["status"].asText() == "unknown" })
    }

    @Test fun `RPC listener observation remains optional for file-backed commands`() {
        val probe = Probe()
        for ((listener, code) in listOf(
            true to "UITEST_RPC_LISTENER_PRESENT",
            false to "UITEST_RPC_LISTENER_MISSING",
            null to "UITEST_RPC_LISTENER_UNKNOWN",
        )) {
            probe.rpcListener = listener
            val result = preflight(probe, """{"deviceId":"test-target","commands":["tapOn"]}""")
            val check = result["checks"].first { it["name"].asText() == "layoutRpcListener" }
            assertEquals("unknown", result["readiness"].asText())
            assertFalse(check["required"].asBoolean())
            assertEquals(code, check["code"].asText())
        }
    }

    @Test fun `keyboard dismissal is advertised for flows without claiming device readiness`() {
        val probe = Probe()
        val result = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(HarmonyOsAgentCapabilities(probe).describe())
        val command = result["commands"].single { it["name"].asText() == "hideKeyboard" }
        assertEquals("supported", command["implementation"].asText())
        val check = preflight(probe, """{"deviceId":"test-target","appId":"com.example.demo","commands":["hideKeyboard"]}""")
        assertEquals("unknown", check["readiness"].asText())
        assertFalse(check["checks"].any { it["status"].asText() == "failed" })
        assertTrue(probe.shell.all { it.first() in setOf("which", "bm") })
    }

    @Test fun `invalid requests are rejected before device discovery`() {
        val probe = Probe()
        listOf(
            """{"deviceId":"test-target","commands":[]}""",
            """{"deviceId":"test-target","commands":["back","back"]}""",
            """{"deviceId":"--help","commands":["back"]}""",
            """{"deviceId":"test-target","commands":["back"],"appId":"bad name"}""",
            """{"deviceId":"test-target","commands":["back"],"extra":true}""",
        ).forEach { json -> assertThrows(RecorderError::class.java) { preflight(probe, json) } }
        assertEquals(0, probe.calls)
    }

    @Test fun `ability and module observations remain scoped to confirmed bundle`() {
        val probe = Probe().apply { bundle = """{"name":"com.example.demo","hapModuleInfos":[{"moduleName":"entry","abilityInfos":[{"name":"EntryAbility"}]}]}""" }
        val params = """{"deviceId":"test-target","commands":["launchApp"],"appId":"com.example.demo","moduleName":"other","abilityName":"EntryAbility"}"""
        assertTrue(preflight(probe, params)["checks"].any { it["code"].asText() == "MODULE_NOT_FOUND" })
        probe.bundle = """{"name":"com.example.other","hapModuleInfos":[{"moduleName":"entry"}]}"""
        val unknown = preflight(probe, params)
        assertEquals("unknown", unknown["readiness"].asText())
        assertFalse(unknown["checks"].any { it["code"].asText() == "MODULE_NOT_FOUND" })
    }

    @Test fun `disabled recording and unsupported commands are blocked without actions`() {
        val probe = Probe()
        val recording = HarmonyOsAgentCapabilities(probe, false).preflight(mapper.readTree("""{"deviceId":"test-target","commands":["startRecording"]}"""))
        assertEquals("blocked", recording["readiness"])
        assertEquals("blocked", preflight(probe, """{"deviceId":"test-target","commands":["setLocation"]}""")["readiness"].asText())
        assertTrue(probe.shell.all { it.first() == "which" })
    }

    @Test fun `clear state preflight distinguishes regular applications and atomic services without clearing data`() {
        val probe = Probe().apply {
            bundle = """{"name":"com.example.demo","applicationInfo":{"bundleType":0,"userDataClearable":true}}"""
        }
        val params = """{"deviceId":"test-target","commands":["clearState"],"appId":"com.example.demo"}"""
        assertEquals("ready", preflight(probe, params)["readiness"].asText())
        probe.bundle = probe.bundle.replace("\"bundleType\":0", "\"bundleType\":1")
        val blocked = preflight(probe, params)
        assertEquals("blocked", blocked["readiness"].asText())
        assertTrue(blocked["checks"].any { it["code"].asText() == "DATA_CLEAR_TARGET_UNSUPPORTED" })
        assertTrue(probe.shell.all { it.first() == "which" || it.take(2) == listOf("bm", "dump") })
    }

    @Test fun `lock probe neither creates files nor reserves device`() {
        val directory = temporary.resolve("locks")
        assertEquals(HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE, HarmonyOsSessionSupport.probeDeviceLock("test-target", directory))
        assertFalse(Files.exists(directory))
        HarmonyOsSessionSupport.withDeviceLock("test-target", directory) {
            assertEquals(HarmonyOsSessionSupport.DeviceLockStatus.BUSY, HarmonyOsSessionSupport.probeDeviceLock("test-target", directory))
        }
        assertEquals(HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE, HarmonyOsSessionSupport.probeDeviceLock("test-target", directory))
        val lockFile = Files.list(directory).use { it.findFirst().orElseThrow() }
        Files.delete(lockFile)
        Files.createDirectory(lockFile)
        assertEquals(HarmonyOsSessionSupport.DeviceLockStatus.UNKNOWN, HarmonyOsSessionSupport.probeDeviceLock("test-target", directory))
    }
}
