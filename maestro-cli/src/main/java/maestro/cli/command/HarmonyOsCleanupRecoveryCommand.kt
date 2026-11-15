package maestro.cli.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.cli.App
import maestro.cli.session.HarmonyOsCleanupFenceEvidence
import maestro.cli.session.HarmonyOsCleanupFenceRecovery
import maestro.cli.session.HarmonyOsMaintenanceEvidence
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.hdc.HdcClient
import picocli.CommandLine
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable

@CommandLine.Command(name = "harmonyos-cleanup-recovery", mixinStandardHelpOptions = true,
    description = ["Isolate one unconfirmed layout cleanup without rebooting; retain the old dispatch barrier"])
class HarmonyOsCleanupRecoveryCommand : Callable<Int> {
    @CommandLine.ParentCommand private var parent: App? = null
    @CommandLine.Option(names = ["--session"], required = true) private lateinit var owner: String
    @CommandLine.Option(names = ["--hdc"], required = true) private lateinit var hdc: Path
    @CommandLine.Option(names = ["--original-cli"], required = true,
        description = ["Unmodified CLI distribution that produced the original session"])
    private lateinit var originalCli: Path

    override fun call(): Int {
        val mapper = jacksonObjectMapper()
        return try {
            require(parent?.platform == "harmonyos") { "Use --platform harmonyos." }
            val device = requireNotNull(parent?.deviceId) { "Use the explicit original --device." }
            HarmonyOsSessionSupport.validateOptions(host = parent?.host, port = parent?.port, driverHostPort = parent?.driverHostPort)
            val evidence = HarmonyOsMaintenanceEvidence
            val origin = HarmonyOsCleanupFenceEvidence.origin(originalCli)
            val executable = hdc.toAbsolutePath().normalize()
            val toolSet = linkedMapOf("hdc" to evidence.fingerprint(executable),
                "java" to evidence.fingerprint(Path.of(System.getProperty("java.home"), "bin",
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")))
            val jars = System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it).toAbsolutePath().normalize() }
            require(jars.isNotEmpty() && jars.all { it.fileName.toString().endsWith(".jar") } && jars.distinct().size == jars.size) {
                "Use a complete installed CLI JAR distribution."
            }
            jars.forEachIndexed { index, jar -> toolSet["classpath:$index:${jar.fileName}"] = evidence.fingerprint(jar) }
            val result = HarmonyOsCleanupFenceRecovery(HarmonyOsSessionSupport.defaultLockDirectory(), origin,
                toolSet, HdcClient(executable.toString())).recover(device, owner)
            println(mapper.writeValueAsString(result)); 0
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            System.err.println("Cleanup fence recovery blocked: ${error.message}")
            error.stackTrace.take(4).forEach { System.err.println("  at $it") }
            println(mapper.writeValueAsString(mapOf("protocol" to "maestro.harmonyos.cleanup-fence-recovery",
                "schemaVersion" to 1, "state" to "unknown", "handoffReady" to false, "error" to "CLEANUP_FENCE_BLOCKED")))
            1
        }
    }
}
