package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.harmonyos.HarmonyOsBridgeServer
import maestro.cli.harmonyos.HarmonyOsBridgeStdout
import maestro.cli.harmonyos.HarmonyOsRecorderBackend
import maestro.cli.session.HarmonyOsSessionSupport
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(name = "harmonyos-bridge", description = ["Local JSON Lines interface for the HarmonyOS recorder"])
class HarmonyOsBridgeCommand : Callable<Int> {
    @CommandLine.ParentCommand
    private var parent: App? = null

    override fun call(): Int {
        if (parent?.platform != "harmonyos") throw CliError("Use --platform harmonyos with harmonyos-bridge.")
        if (parent?.deviceId != null) throw CliError("Select the device in the bridge connect request.")
        HarmonyOsSessionSupport.validateOptions(host = parent?.host, port = parent?.port, driverHostPort = parent?.driverHostPort)
        return HarmonyOsBridgeServer(
            System.`in`, HarmonyOsBridgeStdout.output, System.err, HarmonyOsRecorderBackend(),
        ).run()
    }
}
