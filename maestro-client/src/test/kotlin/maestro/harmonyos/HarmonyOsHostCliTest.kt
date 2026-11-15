package maestro.harmonyos

import com.fasterxml.jackson.databind.ObjectMapper
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HarmonyOsHostCliTest {
    @Test
    fun `an empty device list is a successful discovery result`() {
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val client = HdcClient(runner = HdcCommandRunner { _, _ -> HdcCommandResult(0, "[Empty]\n", "") })

        assertEquals(0, HarmonyOsHostCli.run(listOf("devices"), client, output::add, errors::add))
        assertTrue(ObjectMapper().readTree(output.single())["devices"].isEmpty)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `failed discovery must not become a successful empty list`() {
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val client = HdcClient(runner = HdcCommandRunner { _, _ -> HdcCommandResult(1, "", "connection failed") })

        assertEquals(1, HarmonyOsHostCli.run(listOf("devices"), client, output::add, errors::add))
        assertTrue(output.isEmpty())
        assertTrue(errors.single().contains("connection failed"))
    }

    @Test
    fun `unknown commands fail without invoking hdc`() {
        var invoked = false
        val client = HdcClient(runner = HdcCommandRunner { _, _ ->
            invoked = true
            HdcCommandResult(0, "", "")
        })

        assertEquals(2, HarmonyOsHostCli.run(listOf("test", "flow.yaml"), client, {}, {}))
        assertFalse(invoked)
    }
}
