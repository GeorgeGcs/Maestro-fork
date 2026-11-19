package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.App
import maestro.cli.harmonyos.AgentProtocolException
import maestro.cli.harmonyos.HarmonyOsBridgeStdout
import maestro.cli.session.HarmonyOsDevicePool
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcException
import picocli.CommandLine
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable

@CommandLine.Command(name = "harmonyos-device-pool", mixinStandardHelpOptions = true,
    description = ["Select an available registered HDC device for the next local run"])
class HarmonyOsDevicePoolCommand : Callable<Int> {
    @CommandLine.ParentCommand private var parent: App? = null
    @CommandLine.Option(names = ["--inventory"], required = true) private lateinit var inventory: Path
    @CommandLine.Option(names = ["--state-directory"], required = true) private lateinit var stateDirectory: Path
    @CommandLine.Option(names = ["--kind"]) private var kind: String? = null
    @CommandLine.Option(names = ["--system"]) private var system: String? = null
    @CommandLine.Option(names = ["--min-api"]) private var minApi: Int? = null
    @CommandLine.Option(names = ["--max-api"]) private var maxApi: Int? = null
    @CommandLine.Option(names = ["--wait-seconds"]) private var waitSeconds: Long = 0

    override fun call(): Int {
        val result: Map<String, Any?>
        val exit: Int
        try {
            require(parent?.platform == "harmonyos" && parent?.deviceId == null) {
                "Use --platform harmonyos; --device bypasses the pool."
            }
            HarmonyOsSessionSupport.validateOptions(host = parent?.host, port = parent?.port, driverHostPort = parent?.driverHostPort)
            val devices = HarmonyOsDevicePool.readInventory(inventory)
            val picked = HarmonyOsDevicePool.select(devices, HarmonyOsDevicePool.Filter(kind, system, minApi, maxApi),
                stateDirectory, Duration.ofSeconds(waitSeconds), HdcClient(commandTimeout = Duration.ofSeconds(3)))
            return respond(mapOf("status" to "selected", "device" to picked), 0)
        } catch (error: AgentProtocolException) {
            result = error.error.toMap()
            exit = 2
        } catch (_: HdcException) {
            result = mapOf("code" to "HDC_DISCOVERY_FAILED", "message" to "HDC discovery could not confirm connected devices.")
            exit = 2
        } catch (_: Exception) {
            result = mapOf("code" to "DEVICE_POOL_CONFIGURATION_INVALID", "message" to "Check the local inventory, state directory and filters.")
            exit = 1
        }
        return respond(mapOf("status" to "blocked", "error" to result), exit)
    }

    private fun respond(fields: Map<String, Any?>, exit: Int): Int {
        HarmonyOsBridgeStdout.output.write(jacksonObjectMapper().writeValueAsBytes(
            mapOf("protocol" to "maestro.harmonyos.device-pool", "schemaVersion" to 1) + fields))
        HarmonyOsBridgeStdout.output.write('\n'.code)
        HarmonyOsBridgeStdout.output.flush()
        return exit
    }
}
