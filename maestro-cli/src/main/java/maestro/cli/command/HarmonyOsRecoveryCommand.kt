package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.App
import maestro.cli.session.HarmonyOsMaintenanceHostObserver
import maestro.cli.session.HarmonyOsMaintenanceRecovery
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.hdc.HdcClient
import picocli.CommandLine
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(name = "harmonyos-recovery", mixinStandardHelpOptions = true,
    description = ["Maintain a quarantined finite-command session across verified Windows and device restarts"])
class HarmonyOsRecoveryCommand : Callable<Int> {
    @CommandLine.ParentCommand private var parent: App? = null
    @CommandLine.Parameters(index = "0", description = ["prepare, resume, complete, plan-host-migration, or migrate-host"])
    private lateinit var action: String
    @CommandLine.Option(names = ["--session"], required = true, description = ["Original recovery session UUID"])
    private lateinit var ownerId: String
    @CommandLine.Option(names = ["--powershell"], required = true, description = ["Absolute path to the Windows PowerShell executable"])
    private lateinit var powershell: Path
    @CommandLine.Option(names = ["--host-script"], required = true, description = ["Path to scripts/recovery/observe-windows-boot.ps1"])
    private lateinit var hostScript: Path
    @CommandLine.Option(names = ["--hdc"], required = true, description = ["Absolute path to the HDC executable"])
    private lateinit var hdc: Path
    @CommandLine.Option(names = ["--tool-approval"], description = ["Reviewed, exact host migration plan JSON"])
    private var toolApproval: Path? = null
    @CommandLine.Option(names = ["--checkpoint-sha256"], description = ["Original checkpoint hash for plan-host-migration"])
    private var checkpointSha256: String? = null

    override fun call(): Int {
        val mapper = jacksonObjectMapper()
        return try {
            require(parent?.platform == "harmonyos") { "Use --platform harmonyos with harmonyos-recovery." }
            val device = requireNotNull(parent?.deviceId) { "Use --device with the original HDC target." }
            require(action in setOf("prepare", "resume", "complete", "plan-host-migration", "migrate-host")) { "Unknown maintenance action." }
            require(toolApproval == null || action == "migrate-host")
            require(checkpointSha256 == null || action == "plan-host-migration")
            HarmonyOsSessionSupport.validateOptions(host = parent?.host, port = parent?.port,
                driverHostPort = parent?.driverHostPort)
            val observer = HarmonyOsMaintenanceHostObserver(powershell, hostScript)
            val tools = observer.tools.toMutableMap()
            tools["hdc"] = HarmonyOsMaintenanceHostObserver.fingerprint(hdc)
            tools["java"] = HarmonyOsMaintenanceHostObserver.fingerprint(Path.of(System.getProperty("java.home"), "bin", "java.exe"))
            val entries = System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it).toAbsolutePath().normalize() }
            require(entries.isNotEmpty() && entries.all { it.fileName.toString().endsWith(".jar", true) }) {
                "Maintenance requires the installed CLI JAR distribution."
            }
            require(entries.map { it.fileName.toString() }.distinct().size == entries.size) {
                "The maintenance classpath contains duplicate JAR names."
            }
            for ((index, entry) in entries.withIndex()) {
                val key = "classpath:$index:${entry.fileName}"
                tools[key] = HarmonyOsMaintenanceHostObserver.fingerprint(entry)
            }
            if (action == "plan-host-migration") {
                require(checkpointSha256?.matches(Regex("[0-9a-f]{64}")) == true)
                println(mapper.writeValueAsString(mapOf("schemaVersion" to 1, "reason" to "windows_powershell_update",
                    "checkpointSha256" to checkpointSha256, "tools" to tools.toSortedMap())))
                System.out.flush()
                return if (System.out.checkError()) 2 else 0
            }
            val recovery = HarmonyOsMaintenanceRecovery(HarmonyOsSessionSupport.defaultLockDirectory(), tools,
                observer::observe, hdcFactory = { HdcClient(tools.getValue("hdc").path) })
            val outcome = when (action) {
                "prepare" -> recovery.prepare(device, ownerId)
                "resume" -> recovery.resume(device, ownerId)
                "migrate-host" -> recovery.migrateHost(device, ownerId, requireNotNull(toolApproval))
                else -> recovery.complete(device, ownerId)
            }
            println(mapper.writeValueAsString(outcome.toMap()))
            System.out.flush()
            if (System.out.checkError() || (action == "complete" && !outcome.complete)) 2 else 0
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            // Keep locations useful for diagnosis without printing journal contents or command tokens.
            generateSequence<Throwable>(error) { it.cause }.take(8).forEach { cause ->
                val location = cause.stackTrace.firstOrNull { it.className.startsWith("maestro.") }
                System.err.println("Maintenance failure: ${cause.javaClass.simpleName}" +
                    (location?.let { " at $it" } ?: ""))
            }
            println(mapper.writeValueAsString(mapOf("protocol" to "harmonyos.reboot-recovery", "schemaVersion" to 1,
                "action" to if (::action.isInitialized) action else null, "state" to "unknown", "handoffReady" to false,
                "error" to mapOf("code" to "MAINTENANCE_NOT_CONFIRMED", "message" to (error.message ?: error.javaClass.simpleName)))))
            2
        }
    }
}
