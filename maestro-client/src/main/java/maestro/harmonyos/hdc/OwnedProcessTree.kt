package maestro.harmonyos.hdc

import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal.HostProcess
import java.time.Instant

internal data class NativeProcessSnapshot(val identity: HostProcess, val parent: HostProcess?)

internal interface NativeProcessAccess {
    fun snapshot(pid: Long): NativeProcessSnapshot?
    fun children(pid: Long): List<Long>
    /** Must recheck the immutable identity immediately before requesting termination. */
    fun destroyIfMatches(identity: HostProcess)
}

internal object SystemNativeProcessAccess : NativeProcessAccess {
    private fun readIdentity(process: ProcessHandle) = HostProcess(process.pid(), process.info().startInstant().orElse(null)?.toString())
    override fun snapshot(pid: Long): NativeProcessSnapshot? {
        val process = ProcessHandle.of(pid).orElse(null) ?: return null
        if (!process.isAlive) return null
        return NativeProcessSnapshot(readIdentity(process), process.parent().orElse(null)?.let(::readIdentity))
    }
    override fun children(pid: Long): List<Long> {
        val process = ProcessHandle.of(pid).orElse(null) ?: return emptyList()
        return process.children().use { stream -> stream.map { it.pid() }.toList() }
    }
    override fun destroyIfMatches(identity: HostProcess) {
        if (identity.startedAt == null) return
        val process = ProcessHandle.of(identity.pid).orElse(null) ?: return
        if (readIdentity(process) == identity) process.destroyForcibly()
    }
}

/** PPID values survive their original parent on Windows. Every edge needs both process identities. */
internal class OwnedProcessTree(
    private val root: HostProcess,
    private val access: NativeProcessAccess = SystemNativeProcessAccess,
) {
    private val observed = linkedSetOf(root)

    fun identities(): List<HostProcess> = observed.toList()

    fun refresh() {
        val queue = ArrayDeque(observed.filter { it.startedAt != null })
        val visited = mutableSetOf<HostProcess>()
        while (queue.isNotEmpty()) {
            val owner = queue.removeFirst()
            if (!visited.add(owner)) continue
            val currentOwner = try { access.snapshot(owner.pid) } catch (_: Exception) {
                observed += HostProcess(owner.pid, null)
                continue
            }
            if (currentOwner != null && currentOwner.identity.startedAt == null) observed += HostProcess(owner.pid, null)
            if (currentOwner?.identity != owner) continue
            val children = try { access.children(owner.pid) } catch (_: Exception) {
                observed += HostProcess(owner.pid, null)
                continue
            }
            for (pid in children) {
                val child = try { access.snapshot(pid) } catch (_: Exception) {
                    observed += HostProcess(pid, null)
                    continue
                } ?: continue
                val started = child.identity.startedAt
                val ownerStarted = checkNotNull(owner.startedAt)
                // An older process cannot have been created by this instance of its alleged parent.
                if (started != null && Instant.parse(started) < Instant.parse(ownerStarted)) continue
                if (child.parent?.pid != owner.pid) {
                    if (child.parent == null) observed += HostProcess(pid, null)
                    continue
                }
                if (started == null || child.parent.startedAt == null) {
                    observed += HostProcess(pid, null)
                    continue
                }
                if (child.parent != owner) continue
                val recheckedOwner = try { access.snapshot(owner.pid) } catch (_: Exception) {
                    observed += HostProcess(owner.pid, null)
                    continue
                }
                if (recheckedOwner?.identity != owner) {
                    if (recheckedOwner != null && recheckedOwner.identity.startedAt == null) observed += HostProcess(owner.pid, null)
                    continue
                }
                if (observed.add(child.identity)) queue.addLast(child.identity)
            }
        }
    }

    fun terminate() {
        observed.toList().asReversed().forEach { identity ->
            if (identity.startedAt != null) runCatching { access.destroyIfMatches(identity) }
        }
    }

    fun hasUnconfirmedProcesses(): Boolean = observed.any { identity ->
        runCatching {
            access.snapshot(identity.pid)?.identity?.let { current ->
                identity.startedAt == null || current.startedAt == null || current == identity
            } == true
        }.getOrDefault(true)
    }
}
