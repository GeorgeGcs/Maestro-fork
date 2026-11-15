package maestro.harmonyos

import com.fasterxml.jackson.databind.ObjectMapper
import maestro.harmonyos.hdc.HdcClient
import kotlin.system.exitProcess

/** Development entry point. This does not replace Maestro's Flow runner. */
object HarmonyOsHostCli {
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args.toList(), HdcClient(), ::println, System.err::println))
    }

    internal fun run(
        args: List<String>,
        client: HdcClient,
        output: (String) -> Unit,
        error: (String) -> Unit,
    ): Int {
        if (args != listOf("devices")) {
            error("Usage: HarmonyOsHostCli devices")
            return 2
        }
        return try {
            output(ObjectMapper().writeValueAsString(mapOf("devices" to client.listDevices())))
            0
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            error("Device discovery interrupted")
            130
        } catch (failure: Exception) {
            error("Device discovery failed: ${failure.message ?: failure.javaClass.simpleName}")
            1
        }
    }
}
