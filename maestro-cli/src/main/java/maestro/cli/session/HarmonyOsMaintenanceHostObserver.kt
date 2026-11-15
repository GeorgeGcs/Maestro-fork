package maestro.cli.session

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.ProcessHdcCommandRunner
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/** Runs the frozen read-only Windows provider. It never restarts the host. */
internal class HarmonyOsMaintenanceHostObserver(
    powershell: Path,
    script: Path,
    private val runner: HdcCommandRunner = ProcessHdcCommandRunner(64 * 1024),
) {
    val tools: Map<String, HarmonyOsMaintenanceHostBoot.Tool> = mapOf(
        "powershell" to fingerprint(powershell), "script" to fingerprint(script),
    )

    fun observe(output: Path): JsonNode {
        require(System.getProperty("os.name").startsWith("Windows")) { "Maintenance host observations require Windows." }
        val destination = output.toAbsolutePath().normalize()
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "Host observation must use a new file." }
        require(destination.parent.toRealPath() == destination.parent) { "Host observation paths cannot follow links." }
        verifyTools()
        val result = runner.run(listOf(tools.getValue("powershell").path,
            "-NoLogo", "-NoProfile", "-NonInteractive", "-File", tools.getValue("script").path,
            "-OutputPath", destination.toString()), Duration.ofSeconds(20))
        verifyTools()
        require(result.exitCode == 0 && result.stderr.isBlank()) { "Windows boot observation failed; maintenance remains blocked." }
        require(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && destination.toRealPath() == destination &&
            Files.size(destination) in 1..(64 * 1024)) { "Windows boot observation is missing or invalid." }
        val node = mapper.readTree(Files.readAllBytes(destination))
        HarmonyOsMaintenanceHostBoot.parse(node, tools.getValue("powershell"), tools.getValue("script"))
        return node
    }

    private fun verifyTools() {
        tools.values.forEach { require(fingerprint(Path.of(it.path)) == it) { "A frozen host observation tool changed." } }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        fun fingerprint(input: Path): HarmonyOsMaintenanceHostBoot.Tool {
            val path = input.toAbsolutePath().normalize()
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path) {
                "Maintenance tools must be regular files without linked paths."
            }
            val size = Files.size(path)
            require(size > 0) { "Maintenance tools cannot be empty." }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            require(Files.size(path) == size) { "Maintenance tool changed during fingerprinting." }
            return HarmonyOsMaintenanceHostBoot.Tool(path.toString(), digest.digest().joinToString("") { "%02x".format(it) }, size)
        }
    }
}
