package maestro.cli.session

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@EnabledOnOs(OS.WINDOWS)
class HarmonyOsMaintenanceHostObserverTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    private fun tools(): Pair<Path, Path> =
        Files.writeString(directory.resolve("powershell.exe"), "fixture native file") to
            Files.writeString(directory.resolve("observe.ps1"), "fixture script")

    private fun evidence(observer: HarmonyOsMaintenanceHostObserver): String = mapper.writeValueAsString(mapOf(
        "protocol" to "maestro.harmonyos.host-boot-observation", "schemaVersion" to 1,
        "provider" to mapOf("name" to "windows-gettickcount64-cim", "version" to 1),
        "observedAt" to "2026-10-30T01:00:00.000Z", "uptimeMilliseconds" to "9007199254740993",
        "lastBootUpTime" to "2026-10-20T00:00:00.000Z", "machineIdSha256" to "c".repeat(64),
        "tools" to observer.tools.mapValues { (name, tool) ->
            mapOf("path" to tool.path, "sha256" to tool.sha256, "bytes" to tool.bytes) +
                if (name == "powershell") mapOf("version" to "7.5.0") else emptyMap()
        },
    ))

    @Test fun `provider runs with a bounded timeout and exact output argument`() {
        val (powershell, script) = tools()
        val output = directory.resolve("observation.json")
        lateinit var observer: HarmonyOsMaintenanceHostObserver
        observer = HarmonyOsMaintenanceHostObserver(powershell, script, HdcCommandRunner { command, timeout ->
            assertThat(command).containsExactly(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-File", script.toString(), "-OutputPath", output.toString()).inOrder()
            assertThat(timeout).isEqualTo(Duration.ofSeconds(20))
            Files.writeString(output, evidence(observer))
            HdcCommandResult(0, "", "")
        })
        val node = observer.observe(output)
        assertThat(node["uptimeMilliseconds"].asText()).isEqualTo("9007199254740993")
        assertThat(mapper.readTree(Files.readAllBytes(output))).isEqualTo(node)
    }

    @Test fun `changed frozen tool prevents dispatch`() {
        val (powershell, script) = tools()
        var calls = 0
        val observer = HarmonyOsMaintenanceHostObserver(powershell, script, HdcCommandRunner { _, _ ->
            calls++; HdcCommandResult(0, "", "")
        })
        Files.writeString(script, "changed")
        assertThrows<IllegalArgumentException> { observer.observe(directory.resolve("new.json")) }
        assertThat(calls).isEqualTo(0)
    }

    @Test fun `tool change during collection rejects a valid looking observation`() {
        val (powershell, script) = tools()
        val output = directory.resolve("new.json")
        lateinit var observer: HarmonyOsMaintenanceHostObserver
        observer = HarmonyOsMaintenanceHostObserver(powershell, script, HdcCommandRunner { _, _ ->
            Files.writeString(output, evidence(observer))
            Files.writeString(script, "changed")
            HdcCommandResult(0, "", "")
        })
        assertThrows<IllegalArgumentException> { observer.observe(output) }
        assertThat(Files.exists(output)).isTrue()
    }

    @Test fun `duplicate keys and trailing json cannot become boot evidence`() {
        val (powershell, script) = tools()
        for (duplicate in listOf(true, false)) {
            val output = directory.resolve("bad-$duplicate.json")
            lateinit var observer: HarmonyOsMaintenanceHostObserver
            observer = HarmonyOsMaintenanceHostObserver(powershell, script, HdcCommandRunner { _, _ ->
                val valid = evidence(observer)
                val malformed = if (duplicate) "{\"schemaVersion\":1," + valid.substring(1) else valid + " {}"
                Files.writeString(output, malformed)
                HdcCommandResult(0, "", "")
            })
            assertThrows<Exception> { observer.observe(output) }
        }
    }

    @Test fun `existing observation is preserved without dispatch`() {
        val (powershell, script) = tools()
        val output = Files.writeString(directory.resolve("existing.json"), "keep original")
        var calls = 0
        val observer = HarmonyOsMaintenanceHostObserver(powershell, script, HdcCommandRunner { _, _ ->
            calls++; HdcCommandResult(0, "", "")
        })
        assertThrows<IllegalArgumentException> { observer.observe(output) }
        assertThat(calls).isEqualTo(0)
        assertThat(Files.readString(output)).isEqualTo("keep original")
    }

    @Test fun `nonzero or diagnostic provider result cannot authorize evidence`() {
        val (powershell, script) = tools()
        for (code in listOf(0, 1)) {
            val output = directory.resolve("failed-$code.json")
            lateinit var observer: HarmonyOsMaintenanceHostObserver
            observer = HarmonyOsMaintenanceHostObserver(powershell, script, HdcCommandRunner { _, _ ->
                Files.writeString(output, evidence(observer))
                HdcCommandResult(code, "", if (code == 0) "provider warning" else "")
            })
            assertThrows<IllegalArgumentException> { observer.observe(output) }
        }
    }
}
