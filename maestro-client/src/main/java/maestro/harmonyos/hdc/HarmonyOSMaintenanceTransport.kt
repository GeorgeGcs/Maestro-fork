package maestro.harmonyos.hdc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

data class MaintenanceCall(val hostCallId: String, val purpose: String, val argumentsSha256: String)
data class MaintenanceCleanupBatch(val index: Int, val paths: List<String>, val hostCallId: String)

/**
 * Fixed maintenance operations only. The caller must hold the same device lease and this
 * journal's owner lock, and durably bind every call to its checkpoint/phase before dispatch.
 * Cleanup additionally requires an already verified, durable reboot termination proof.
 * This class cannot release a gate or turn an original pending command into completion.
 */
class HarmonyOSMaintenanceTransport(private val journal: HarmonyOSRecoveryJournal, private val hdc: HdcClient) {
    fun observeUptime(timeout: Duration, beforeDispatch: (MaintenanceCall) -> Unit): HarmonyOSUptimeObservation = synchronized(journal) {
        val deadline = Deadline(timeout)
        val inventory = journal.maintenanceInventory()
        val result = query(listOf("uptime"), "observe_uptime", deadline, beforeDispatch).first
        check(journal.maintenanceInventory() == inventory) { "Maintenance scope changed." }
        if (result.stderr.isNotEmpty()) throw HdcException("The device uptime query returned diagnostics.")
        val observation = try { HarmonyOSUptimeObservation.parse(result.stdout) } catch (failure: IllegalArgumentException) {
            throw HdcException("The device did not return a valid uptime observation.", failure)
        }
        deadline.remaining()
        observation
    }

    fun observeBoot(timeout: Duration, beforeDispatch: (MaintenanceCall) -> Unit): String = synchronized(journal) {
        val deadline = Deadline(timeout)
        val inventory = journal.maintenanceInventory()
        val result = query(listOf("cat", "/proc/sys/kernel/random/boot_id"), "observe_boot", deadline, beforeDispatch).first
        check(journal.maintenanceInventory() == inventory) { "Maintenance scope changed." }
        val value = result.stdout.removeSuffix("\n").removeSuffix("\r")
        if (result.stderr.isNotEmpty() || result.stdout.length > 38 ||
            !UUID_PATTERN.matches(value) || value == NIL_UUID || UUID.fromString(value).toString() != value ||
            result.stdout !in listOf(value, "$value\n", "$value\r\n")) {
            throw HdcException("The device did not return one canonical non-nil boot UUID.")
        }
        deadline.remaining()
        value
    }

    /** Each callback confirms only its listed paths; the original v1 journal flags stay unchanged. */
    fun cleanupOwnedPaths(timeout: Duration, beforeDispatch: (MaintenanceCall) -> Unit,
                          onConfirmedBatch: (MaintenanceCleanupBatch) -> Unit): Unit = synchronized(journal) {
        val deadline = Deadline(timeout)
        val inventory = journal.maintenanceInventory()
        val root = inventory.remoteRoot
        val files = "$root/files"
        val prefix = "[ ! -L ${quote(root)} ] && [ ! -L ${quote(files)} ] || exit 121; "
        val steps = inventory.temporaryFiles.map { remote ->
            CleanupStep("[ ! -L ${quote(remote)} ] && rm -f ${quote(remote)} && [ ! -e ${quote(remote)} ] || exit 121; ", listOf(remote))
        } + inventory.operationIds.chunked(20).map { ids ->
            val directories = ids.map { "$root/$it" }
            val script = "for __maestro_cleanup_dir in ${directories.joinToString(" ", transform = ::quote)}; do " +
                "if [ -e \"\$__maestro_cleanup_dir\" ] || [ -L \"\$__maestro_cleanup_dir\" ]; then " +
                "[ -d \"\$__maestro_cleanup_dir\" ] && [ ! -L \"\$__maestro_cleanup_dir\" ] && " +
                "[ ! -L \"\$__maestro_cleanup_dir/done\" ] && [ ! -L \"\$__maestro_cleanup_dir/done.pending\" ] && " +
                "rm -f \"\$__maestro_cleanup_dir/done\" \"\$__maestro_cleanup_dir/done.pending\" && " +
                "rmdir \"\$__maestro_cleanup_dir\" || exit 121; fi; " +
                "[ ! -e \"\$__maestro_cleanup_dir\" ] && [ ! -L \"\$__maestro_cleanup_dir\" ] || exit 121; done; "
            CleanupStep(script, directories.flatMap { listOf("$it/done", "$it/done.pending", it) })
        } + CleanupStep(
            "if [ -e ${quote(files)} ] || [ -L ${quote(files)} ]; then " +
                "[ -d ${quote(files)} ] && [ ! -L ${quote(files)} ] && rmdir ${quote(files)} || exit 121; fi; " +
                "if [ -e ${quote(root)} ] || [ -L ${quote(root)} ]; then " +
                "[ -d ${quote(root)} ] && [ ! -L ${quote(root)} ] && rmdir ${quote(root)} || exit 121; fi; " +
                "[ ! -e ${quote(root)} ] && [ ! -L ${quote(root)} ] || exit 121; ", listOf(files, root))
        val batch = mutableListOf<CleanupStep>()
        var index = 0
        fun arguments(steps: List<CleanupStep>, nonce: String) = listOf("sh", "-c",
            prefix + steps.joinToString("") { it.script } + "printf '%s' ${quote(nonce)}")
        fun bytes(steps: List<CleanupStep>) = arguments(steps, NIL_UUID)
            .joinToString(" ", transform = ::quote).toByteArray(Charsets.UTF_8).size
        fun flush() {
            if (batch.isEmpty()) return
            check(journal.maintenanceInventory() == inventory) { "Maintenance scope changed." }
            val nonce = UUID.randomUUID().toString()
            val (result, callId) = query(arguments(batch, nonce), "cleanup_owned_paths", deadline, beforeDispatch)
            check(journal.maintenanceInventory() == inventory) { "Maintenance scope changed." }
            if (result.stderr.isNotEmpty() || result.stdout != nonce) {
                throw HdcException("Owned maintenance cleanup was not confirmed.")
            }
            deadline.remaining()
            try {
                onConfirmedBatch(MaintenanceCleanupBatch(index, batch.flatMap { it.paths }, callId))
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
            deadline.remaining()
            index++
            batch.clear()
        }
        for (step in steps) {
            deadline.remaining()
            if (bytes(batch + step) > MAX_SHELL_BYTES) flush()
            check(bytes(listOf(step)) <= MAX_SHELL_BYTES) { "A maintenance cleanup step exceeds the shell limit." }
            batch += step
        }
        flush()
        check(journal.maintenanceInventory() == inventory) { "Maintenance scope changed." }
    }

    private fun query(arguments: List<String>, purpose: String, deadline: Deadline,
                      beforeDispatch: (MaintenanceCall) -> Unit): Pair<HdcCommandResult, String> {
        val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(arguments))
            .joinToString("") { "%02x".format(it) }
        var callId: String? = null
        val result = try {
            hdc.shellRecovery(journal.deviceId, arguments, deadline.remaining(), journal) { id ->
                deadline.remaining()
                beforeDispatch(MaintenanceCall(id, purpose, digest))
                deadline.remaining()
                callId = id
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        deadline.remaining()
        return result to checkNotNull(callId)
    }

    private data class CleanupStep(val script: String, val paths: List<String>)
    private class Deadline(val timeout: Duration) {
        private val end: Long
        init {
            require(timeout > Duration.ZERO && timeout <= Duration.ofMinutes(1)) { "Maintenance timeout must be within one minute." }
            end = System.nanoTime() + timeout.toNanos()
        }
        fun remaining(): Duration {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Maintenance was interrupted.")
            val nanos = end - System.nanoTime()
            if (nanos <= 0) throw HdcTimeoutException(timeout)
            return Duration.ofNanos(nanos)
        }
    }

    private companion object {
        val mapper = jacksonObjectMapper()
        const val MAX_SHELL_BYTES = 6 * 1024
        const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun quote(value: String) = HdcClient.quoteShellArgument(value)
    }
}
