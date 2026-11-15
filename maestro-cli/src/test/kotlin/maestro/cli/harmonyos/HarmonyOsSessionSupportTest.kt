package maestro.cli.harmonyos

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.cli.command.StartDeviceCommand
import maestro.cli.device.PickDeviceInteractor
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.cli.session.MaestroSessionManager
import maestro.device.Platform
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcException
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HarmonyOsSessionSupportTest {
    @Test fun `recording limit defaults to three minutes and does not enable recording`() {
        val defaults = HarmonyOsSessionSupport.driverConfig(emptyMap())
        assertThat(defaults.recordingMaxDuration.seconds).isEqualTo(180L)
        val configured = HarmonyOsSessionSupport.driverConfig(mapOf("MAESTRO_HARMONYOS_RECORDING_MAX_SECONDS" to "300"))
        assertThat(configured.recordingMaxDuration.seconds).isEqualTo(300L)
        assertThat(configured.enableScreenRecording).isFalse()
    }

    @Test fun `recording limit accepts the supported bounds and trims whitespace`() {
        for (value in listOf("1", "300", " 600 ")) {
            val config = HarmonyOsSessionSupport.driverConfig(mapOf("MAESTRO_HARMONYOS_RECORDING" to "huawei-system",
                "MAESTRO_HARMONYOS_RECORDING_MAX_SECONDS" to value))
            assertThat(config.enableScreenRecording).isTrue()
            assertThat(config.recordingMaxDuration.seconds).isEqualTo(value.trim().toLong())
        }
    }

    @Test fun `invalid recording limits fail instead of silently using the default`() {
        for (value in listOf("", " ", "0", "-1", "601", "1.5", "abc", "9223372036854775808")) {
            assertThat(assertThrows<CliError> {
                HarmonyOsSessionSupport.driverConfig(mapOf("MAESTRO_HARMONYOS_RECORDING_MAX_SECONDS" to value))
            }).hasMessageThat().contains("integer from 1 to 600")
        }
    }

    @Test
    fun `selects an explicit HDC target without requiring it to be the first device`() {
        val commands = mutableListOf<List<String>>()
        val hdc = listing("device-a\ndevice-b\n", commands)

        assertThat(HarmonyOsSessionSupport.selectDevice("device-b", hdc)).isEqualTo("device-b")
        assertThat(commands).containsExactly(listOf("test-hdc", "list", "targets"))
    }

    @Test
    fun `a single HDC target is selected automatically`() {
        assertThat(HarmonyOsSessionSupport.selectDevice(null, listing("device-a\n"))).isEqualTo("device-a")
    }

    @Test
    fun `empty ambiguous and missing targets fail without choosing a different device`() {
        assertThat(assertThrows<CliError> {
            HarmonyOsSessionSupport.selectDevice(null, listing("[Empty]\n"))
        }).hasMessageThat().contains("No HDC device")
        assertThat(assertThrows<CliError> {
            HarmonyOsSessionSupport.selectDevice(null, listing("device-a\ndevice-b\n"))
        }).hasMessageThat().contains("Multiple HDC devices")
        assertThat(assertThrows<CliError> {
            HarmonyOsSessionSupport.selectDevice("device-c", listing("device-a\n"))
        }).hasMessageThat().contains("not available")
    }

    @Test
    fun `target identifiers are case sensitive`() {
        assertThrows<CliError> {
            HarmonyOsSessionSupport.selectDevice("DEVICE-A", listing("device-a\n"))
        }
    }

    @Test
    fun `rejects multiple identifiers before contacting HDC`() {
        val commands = mutableListOf<List<String>>()
        assertThrows<CliError> {
            HarmonyOsSessionSupport.selectDevice("device-a,device-b", listing("device-a\n", commands))
        }
        assertThat(commands).isEmpty()
    }

    @Test
    fun `HDC failure is reported without falling back to another platform`() {
        val hdc = HdcClient("test-hdc", HdcCommandRunner { _, _ ->
            HdcCommandResult(1, "", "transport unavailable")
        })
        val error = assertThrows<CliError> { HarmonyOsSessionSupport.selectDevice(null, hdc) }
        assertThat(error).hasMessageThat().contains("transport unavailable")
        assertThat(error.cause).isInstanceOf(HdcException::class.java)
    }

    @Test
    fun `single device execution rejects sharding multi device lists and web flows before discovery`() {
        val commands = mutableListOf<List<String>>()
        val hdc = listing("device-a\n", commands)
        assertThrows<CliError> { HarmonyOsSessionSupport.selectTestDeviceIds(emptyList(), shards = 2, hdc = hdc) }
        assertThrows<CliError> { HarmonyOsSessionSupport.selectTestDeviceIds(listOf("device-a", "device-b"), hdc = hdc) }
        assertThrows<CliError> { HarmonyOsSessionSupport.selectTestDeviceIds(emptyList(), includesWeb = true, hdc = hdc) }
        assertThat(commands).isEmpty()
        assertThat(HarmonyOsSessionSupport.selectTestDeviceIds(emptyList(), hdc = hdc)).containsExactly("device-a")
    }

    @Test
    fun `public session entry rejects remote hosts for either platform spelling before discovery`() {
        for (platform in listOf("harmonyos", "HarmonyOS")) {
            val error = assertThrows<CliError> {
                MaestroSessionManager.newSession(
                    host = "localhost", port = null, driverHostPort = null,
                    deviceId = "device-a", platform = platform,
                ) { error("The session block must not run") }
            }
            assertThat(error).hasMessageThat().contains("local HDC connection")
        }
    }

    @Test
    fun `generic mobile picker refuses HarmonyOS before looking up a matching Android identifier`() {
        assertThat(assertThrows<CliError> {
            PickDeviceInteractor.pickDevice(deviceId = "device-a", platform = Platform.HARMONYOS)
        }).hasMessageThat().contains("HDC session")
    }

    @Test
    fun `unsupported options cannot silently configure an Android or iOS session`() {
        val invalidOptions: List<() -> Unit> = listOf(
            { HarmonyOsSessionSupport.validateOptions(port = 7001) },
            { HarmonyOsSessionSupport.validateOptions(driverHostPort = 7001) },
            { HarmonyOsSessionSupport.validateOptions(teamId = "apple-team") },
            { HarmonyOsSessionSupport.validateOptions(deviceIndex = 0) },
            { HarmonyOsSessionSupport.validateOptions(isStudio = true) },
            { HarmonyOsSessionSupport.validateOptions(isHeadless = true) },
            { HarmonyOsSessionSupport.validateOptions(screenSize = "800x600") },
        )
        invalidOptions.forEach { options -> assertThrows<CliError> { options() } }
    }

    @Test
    fun `ability and module come from explicit environment configuration`() {
        assertThat(HarmonyOsSessionSupport.driverConfig(emptyMap()).abilityName).isNull()
        val config = HarmonyOsSessionSupport.driverConfig(mapOf(
            "MAESTRO_HARMONYOS_ABILITY" to " LaunchAbility ",
            "MAESTRO_HARMONYOS_MODULE" to " feature ",
        ))
        assertThat(config.abilityName).isEqualTo("LaunchAbility")
        assertThat(config.moduleName).isEqualTo("feature")
        assertThat(HarmonyOsSessionSupport.driverConfig(mapOf("MAESTRO_HARMONYOS_ABILITY" to "  ")).abilityName).isNull()
    }

    @Test
    fun `recording backend must be explicitly enabled and unknown values fail`() {
        assertThat(HarmonyOsSessionSupport.driverConfig(emptyMap()).enableScreenRecording).isFalse()
        assertThat(HarmonyOsSessionSupport.driverConfig(mapOf("MAESTRO_HARMONYOS_RECORDING" to "off")).enableScreenRecording).isFalse()
        assertThat(HarmonyOsSessionSupport.driverConfig(mapOf("MAESTRO_HARMONYOS_RECORDING" to "huawei-system")).enableScreenRecording).isTrue()
        assertThrows<CliError> {
            HarmonyOsSessionSupport.driverConfig(mapOf("MAESTRO_HARMONYOS_RECORDING" to "true"))
        }
    }

    @Test
    fun `same device cannot be used concurrently but other devices remain available`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("harmonyos-lock-test").toPath()
        HarmonyOsSessionSupport.withDeviceLock("device-a", directory) {
            assertThrows<CliError> { HarmonyOsSessionSupport.withDeviceLock("device-a", directory) {} }
            assertThat(HarmonyOsSessionSupport.withDeviceLock("device-b", directory) { "available" }).isEqualTo("available")
        }
        assertThat(HarmonyOsSessionSupport.withDeviceLock("device-a", directory) { "released" }).isEqualTo("released")
    }

    @Test
    fun `flow failure releases the device for another invocation`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("harmonyos-lock-test").toPath()
        val failure = IllegalStateException("flow failed")
        assertThat(assertThrows<IllegalStateException> {
            HarmonyOsSessionSupport.withDeviceLock("device-a", directory) { throw failure }
        }).isSameInstanceAs(failure)
        assertThat(HarmonyOsSessionSupport.withDeviceLock("device-a", directory) { "released" }).isEqualTo("released")
    }

    @Test
    fun `failed driver open releases the device before another attempt`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("harmonyos-open-test").toPath()
        var attempts = 0
        val failure = HdcException("transport unavailable")
        val hdc = HdcClient("test-hdc", HdcCommandRunner { _, _ ->
            attempts++
            throw failure
        })
        repeat(2) {
            assertThat(assertThrows<HdcException> {
                HarmonyOsSessionSupport.withSession(
                    "device-a", hdc = hdc, config = HarmonyOSDriverConfig(), lockDirectory = directory,
                ) { error("The session block must not run") }
            }).isSameInstanceAs(failure)
        }
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `start-device rejects HarmonyOS instead of creating an Android emulator`() {
        assertThat(assertThrows<CliError> {
            StartDeviceCommand().buildDeviceSpec(Platform.HARMONYOS)
        }).hasMessageThat().contains("does not create HarmonyOS devices")
    }

    private fun listing(output: String, commands: MutableList<List<String>> = mutableListOf()): HdcClient =
        HdcClient("test-hdc", HdcCommandRunner { command, _ ->
            commands += command
            HdcCommandResult(0, output, "")
        })
}
