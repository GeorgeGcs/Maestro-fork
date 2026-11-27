package maestro.harmonyos.driver

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

class HarmonyOSHap private constructor(
    val path: Path,
    val bundleId: String,
    val versionCode: Long,
    val sha256: String,
) {
    companion object {
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private val mapper = ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

        fun stage(source: Path, destination: Path, bundleId: String, sha256: String): HarmonyOSHap {
            requireIdentifier(bundleId, "Bundle name")
            require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "A SHA-256 digest is required." }
            require(Files.isRegularFile(source) && source.fileName.toString().endsWith(".hap", ignoreCase = true)) {
                "A local HAP file is required."
            }
            val checksum = MessageDigest.getInstance("SHA-256")
            DigestInputStream(Files.newInputStream(source), checksum).use { Files.copy(it, destination) }
            val actualHash = HexFormat.of().formatHex(checksum.digest())
            require(actualHash.equals(sha256, ignoreCase = true)) { "HAP SHA-256 does not match." }
            val manifest = ZipFile(destination.toFile()).use { archive ->
                val entry = archive.getEntry("module.json") ?: error("HAP is missing module.json.")
                require(archive.stream().filter { it.name == "module.json" }.count() == 1L) {
                    "HAP has multiple module.json entries."
                }
                archive.getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }.also {
                    require(it.size <= MAX_MANIFEST_BYTES) { "HAP module.json exceeds the size limit." }
                }
            }
            val application = mapper.readTree(manifest)?.path("app") ?: error("HAP application metadata is missing.")
            require(application.path("bundleName").textValue() == bundleId) { "HAP bundle name does not match." }
            val version = application.path("versionCode")
            require(version.isIntegralNumber && version.canConvertToLong() && version.longValue() > 0) {
                "HAP versionCode is missing or invalid."
            }
            return HarmonyOSHap(destination, bundleId, version.longValue(), actualHash)
        }
    }
}
