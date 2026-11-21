package maestro.cli.session

import maestro.harmonyos.hdc.CleanupFenceCall
import maestro.harmonyos.hdc.CleanupFenceScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

internal data class CleanupFenceGate(val recoveryId: String, val checkpoint: MaintenanceReference, val head: MaintenanceReference)
internal data class RetainedCleanupFence(val ownerId: String, val recoveryId: String,
    val checkpoint: MaintenanceReference, val receipt: MaintenanceReference)
internal data class CleanupFenceOrigin(val profile: String, val journalClassSha256: String, val clientJar: MaintenanceTool)
internal data class CleanupFenceCheckpoint(val schemaVersion: Int, val recoveryId: String, val deviceId: String,
    val ownerId: String, val createdAt: String, val scope: CleanupFenceScope,
    val originalGate: MaintenanceReference, val originalJournal: MaintenanceReference, val originalReceipt: MaintenanceReference,
    val origin: CleanupFenceOrigin, val tools: Map<String, MaintenanceTool>)
internal data class CleanupFenceCallEvidence(val call: CleanupFenceCall, val outcome: MaintenanceReference?)
internal data class CleanupFenceStep(val schemaVersion: Int, val checkpointSha256: String, val revision: Int,
    val previous: MaintenanceReference?, val phase: String, val observedAt: String, val journal: MaintenanceReference,
    val calls: List<CleanupFenceCallEvidence>, val receipt: MaintenanceReference?)
internal data class CleanupFenceReceipt(val schemaVersion: Int, val recoveryId: String, val deviceId: String,
    val sessionId: String, val observedAt: String, val state: String, val handoffReady: Boolean,
    val disposition: String, val originalResult: String, val pendingOperations: Int, val temporaryFiles: Int,
    val checkpoint: MaintenanceReference, val verifiedHead: MaintenanceReference, val retainedDirectories: List<String>)

internal object HarmonyOsCleanupFenceEvidence {
    val E = HarmonyOsMaintenanceEvidence
    const val PROFILE = "exclusive-operation-mkdir-v1"
    // Frozen journal implementation exercised with actual shell races. No heuristic bytecode matching.
    val supportedJournalClasses = setOf(
        "c1de9567295e53022f9a2422593c5b7516b30bef4a66de956ff8d510fc20b0ac",
        // 2026-11-05: original frozen CLI exercised with the layout-export wrapper and shell races.
        "f68fd1582450c8ee4276899d44c655554b602c2190d40bf0d5b002ebe6bcd3f9", "c671426296337ffa05e557969e6ae1900bc6f503ae0a4d972d1450ab22ff7f10",
    )
    val checkpointFields = setOf("schemaVersion", "recoveryId", "deviceId", "ownerId", "createdAt", "scope",
        "originalGate", "originalJournal", "originalReceipt", "origin", "tools")
    val stepFields = setOf("schemaVersion", "checkpointSha256", "revision", "previous", "phase", "observedAt", "journal", "calls", "receipt")
    val receiptFields = setOf("schemaVersion", "recoveryId", "deviceId", "sessionId", "observedAt", "state", "handoffReady",
        "disposition", "originalResult", "pendingOperations", "temporaryFiles", "checkpoint", "verifiedHead", "retainedDirectories")
    val phases = listOf("prepared", "claimed", "cleaned", "complete", "aborted")

    fun origin(cli: Path): CleanupFenceOrigin {
        val lib = cli.toAbsolutePath().normalize().resolve("lib")
        E.regularAncestors(lib)
        val jars = Files.list(lib).use { paths -> paths.filter { it.fileName.toString().endsWith(".jar") }.toList() }
        val matches = jars.mapNotNull { path ->
            E.regularAncestors(path)
            JarFile(path.toFile()).use { jar ->
                jar.getJarEntry("maestro/harmonyos/hdc/HarmonyOSRecoveryJournal.class")?.let { entry ->
                    require(entry.size in 1..(1024 * 1024))
                    val bytes = jar.getInputStream(entry).use { it.readNBytes(1024 * 1024 + 1) }
                    require(bytes.size <= 1024 * 1024)
                    CleanupFenceOrigin(PROFILE, E.digest(bytes), E.fingerprint(path))
                }
            }
        }
        return matches.single().also(::validateOrigin)
    }

    fun validateOrigin(origin: CleanupFenceOrigin) {
        require(origin.profile == PROFILE && origin.journalClassSha256 in supportedJournalClasses) {
            "The original journal implementation has no verified cleanup-fence compatibility profile."
        }
        require(origin.clientJar.bytes > 0 && origin.clientJar.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun root(directory: Path, device: String, owner: String, recovery: String): Path {
        E.uuid(owner); E.uuid(recovery)
        return ownerDirectory(directory, device, owner).resolve("cleanup-fence").resolve(recovery)
    }
}
