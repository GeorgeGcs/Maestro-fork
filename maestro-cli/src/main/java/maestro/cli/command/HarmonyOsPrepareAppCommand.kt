package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.harmonyos.HarmonyOsBridgeStdout
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.driver.HarmonyOSHap
import maestro.harmonyos.hdc.HdcClient
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "harmonyos-prepare-app",
    mixinStandardHelpOptions = true,
    description = ["Install and verify a pinned HAP before running a HarmonyOS Flow"],
)
class HarmonyOsPrepareAppCommand : Callable<Int> {
    @CommandLine.ParentCommand private var parent: App? = null
    @CommandLine.Option(names = ["--hap"], required = true) private lateinit var file: Path
    @CommandLine.Option(names = ["--bundle"], required = true) private lateinit var bundle: String
    @CommandLine.Option(names = ["--sha256"], required = true) private lateinit var sha256: String
    @CommandLine.Option(names = ["--replace"]) private var replace: Boolean = false

    override fun call(): Int {
        val receipt = mutableMapOf<String, Any>(
            "protocol" to "maestro.harmonyos.app-preparation", "schemaVersion" to 1,
            "status" to "failed", "cleanup" to "notStarted",
        )
        var code = "HAP_INPUT_INVALID"
        var installationError: Exception? = null
        try {
            if (parent?.platform != "harmonyos") throw CliError("Use --platform harmonyos with harmonyos-prepare-app.")
            val requestedDevice = parent?.deviceId ?: throw CliError("Select an explicit --device before installing a HAP.")
            HarmonyOsSessionSupport.validateOptions(host = parent?.host, port = parent?.port, driverHostPort = parent?.driverHostPort)
            val staging = Files.createTempDirectory("maestro-harmonyos-hap-")
            val stagedFile = staging.resolve("application.hap")
            try {
                val hap = HarmonyOSHap.stage(file, stagedFile, bundle, sha256)
                receipt.putAll(mapOf("bundleId" to hap.bundleId, "versionCode" to hap.versionCode, "sha256" to hap.sha256))
                code = "DEVICE_SELECTION_FAILED"
                val hdc = HdcClient()
                val device = HarmonyOsSessionSupport.selectDevice(requestedDevice, hdc)
                code = "SESSION_OPEN_FAILED"
                receipt["cleanup"] = "unknown"
                HarmonyOsSessionSupport.withSession(device, hdc = hdc) { session ->
                    try {
                        (session.maestro.driver as HarmonyOSDriver).installHap(hap, replace)
                    } catch (error: Exception) {
                        // Report the install outcome separately from the lease's cleanup outcome.
                        installationError = error
                    }
                    code = "SESSION_CLEANUP_FAILED"
                }
                receipt["cleanup"] = "complete"
                installationError?.let {
                    code = "HAP_INSTALL_FAILED"
                    throw it
                }
                code = "HAP_STAGING_CLEANUP_FAILED"
            } finally {
                Files.deleteIfExists(stagedFile)
                Files.deleteIfExists(staging)
            }
            receipt["status"] = "installed"
        } catch (error: Exception) {
            if (code == "SESSION_CLEANUP_FAILED") {
                installationError?.let {
                    receipt["installationError"] = mapOf("code" to "HAP_INSTALL_FAILED", "message" to it.message.orEmpty())
                }
            }
            receipt["error"] = mapOf("code" to code, "message" to (error.message ?: error.javaClass.simpleName))
        }
        val output = HarmonyOsBridgeStdout.output
        output.write(jacksonObjectMapper().writeValueAsBytes(receipt))
        output.write('\n'.code)
        output.flush()
        return if (receipt["status"] == "installed") 0 else 1
    }
}
