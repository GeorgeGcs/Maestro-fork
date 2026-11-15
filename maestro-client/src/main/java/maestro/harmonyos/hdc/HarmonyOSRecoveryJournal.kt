package maestro.harmonyos.hdc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

data class RecoveryIssue(val code: String, val message: String) {
    fun toMap(): Map<String, String> = mapOf("code" to code, "message" to message)
}

data class RecoveryReport(
    val state: String,
    val operations: Int,
    val pendingOperations: Int,
    val temporaryFiles: Int,
    val issues: List<RecoveryIssue>,
) {
    fun toMap(): Map<String, Any> = mapOf("state" to state, "operations" to operations,
        "pendingOperations" to pendingOperations, "temporaryFiles" to temporaryFiles,
        "issues" to issues.map { it.toMap() })
}

/** Local write-ahead record plus command-specific device completion proofs. It never retries a command. */
class HarmonyOSRecoveryJournal private constructor(
    val path: Path,
    private val state: State,
    private val ownerChannel: FileChannel,
    private val ownerLock: FileLock,
    private val writeState: (Path, ByteArray) -> Unit = HarmonyOSJournalFileWriter()::write,
) : AutoCloseable {
    val deviceId: String get() = state.deviceId
    val ownerId: String get() = state.ownerId
    private var activeCalls = 0
    private var closed = false
    private var persistenceUnconfirmed = false
    private class DispatchBoundary(val parent: DispatchBoundary?, val operationId: String, var entered: Boolean = false)
    private class BeforeDispatchFailure(val boundary: DispatchBoundary, val original: Throwable) :
        RuntimeException(original.message, original)
    private val dispatchBoundary = ThreadLocal<DispatchBoundary>()
    internal fun diagnosticOperationId(): String? = dispatchBoundary.get()?.operationId
    var recordings: HarmonyOSRecordingResourceJournal? = null
        private set

    @Synchronized fun enableRecordingResources(): HarmonyOSRecordingResourceJournal {
        requireDispatch()
        check(recordings == null)
        return HarmonyOSRecordingResourceJournal.create(path.resolveSibling("recording-resources.json"),
            state.deviceId, state.ownerId, state.generation, state.remoteRoot).also { recordings = it }
    }

    @Synchronized fun allocateRecording(): HarmonyOSRecordingResourceJournal.Recording {
        requireDispatch()
        val resources = checkNotNull(recordings) { "Recording resources were not initialized by the session owner." }
        return resources.register(allocateTemporaryFile("recording", "mp4"))
    }

    data class Operation(
        val id: String,
        val token: String,
        val sha256: String,
        val recoverable: Boolean,
        var completed: Boolean = false,
        var markerRemoved: Boolean = false,
    )
    data class HostProcess(val pid: Long, val startedAt: String?)
    data class HostCall(
        val id: String,
        val processes: MutableList<HostProcess> = mutableListOf(),
        var returned: Boolean = false,
        var stopped: Boolean = false,
    )
    data class State(
        val version: Int = 1,
        val deviceId: String,
        val ownerId: String,
        val generation: String,
        val remoteRoot: String,
        var sealed: Boolean = false,
        var rootRemoved: Boolean = true,
        val operations: MutableList<Operation> = mutableListOf(),
        val temporaryFiles: MutableSet<String> = linkedSetOf(),
        val hostCalls: MutableList<HostCall> = mutableListOf(),
    )

    @Synchronized fun seal() {
        ensureOpen()
        state.sealed = true
        persist()
    }

    @Synchronized fun snapshot(): RecoveryReport = report()

    internal data class MaintenanceInventory(val remoteRoot: String, val operationIds: List<String>, val temporaryFiles: List<String>)

    /** Read-only scope check; a pending finite command is not treated as completed. */
    @Synchronized internal fun maintenanceInventory(): MaintenanceInventory {
        ensureOpen()
        check(state.sealed && activeCalls == 0 && !persistenceUnconfirmed) { "Maintenance requires a sealed, durable, idle journal." }
        check(state.hostCalls.all { it.stopped }) { "Maintenance requires confirmed native termination." }
        check(recordings == null &&
            Files.notExists(path.resolveSibling("recording-resources.json"), LinkOption.NOFOLLOW_LINKS)) {
            "Maintenance does not handle recording resources."
        }
        state.temporaryFiles.forEach { remote ->
            requireOwnedFile(remote)
            check(remote.substringAfterLast('/').matches(Regex("(?:layout-[0-9a-f-]{36}\\.json|screen-[0-9a-f-]{36}\\.png)"))) {
                "Maintenance only handles registered layout and screenshot files."
            }
        }
        check(state.operations.all { it.completed || it.recoverable }) { "Maintenance requires finite pending commands." }
        return MaintenanceInventory(state.remoteRoot, state.operations.map { it.id }, state.temporaryFiles.toList())
    }

    @Synchronized internal fun allocateTemporaryFile(kind: String, extension: String): String {
        require(kind.matches(Regex("[a-z]+")) && extension.matches(Regex("[a-z][a-z0-9]*")))
        requireDispatch()
        val remote = "${state.remoteRoot}/files/$kind-${UUID.randomUUID()}.$extension"
        state.temporaryFiles += remote
        persist()
        return remote
    }

    @Synchronized internal fun forgetTemporaryFile(remote: String) {
        ensureOpen()
        requireOwnedFile(remote)
        if (remote.substringAfterLast('/').matches(Regex("(?:layout-[0-9a-f-]{36}\\.json|screen-[0-9a-f-]{36}\\.png)"))) {
            // UITest outputs can remain after an acknowledged eager deletion. Keep
            // the existing durable reservation until sealed-session cleanup. Never
            // adopt unregistered files based on their names or directory contents.
            check(remote in state.temporaryFiles) { "Temporary file is not registered to this session." }
            return
        }
        // Recording resources have their own ownership and archive lifecycle.
        state.temporaryFiles.remove(remote)
        persist()
    }

    @Synchronized internal fun ownsTemporaryFile(remote: String): Boolean {
        ensureOpen()
        return remote in state.temporaryFiles
    }

    internal fun shell(arguments: List<String>, recoverable: Boolean, allowSealed: Boolean = false, execute: (List<String>) -> HdcCommandResult): HdcCommandResult {
        val (operation, previousRootRemoved) = synchronized(this) {
            if (allowSealed) { ensureOpen(); check(state.sealed && recoverable) } else requireDispatch()
            val previousRootRemoved = state.rootRemoved
            val operation = Operation(UUID.randomUUID().toString(), UUID.randomUUID().toString(), hash(mapper.writeValueAsBytes(arguments)), recoverable)
            state.rootRemoved = false
            state.operations += operation
            try { persist() } catch (failure: Throwable) {
                rollbackOperation(operation, previousRootRemoved, failure)
                throw failure
            }
            operation to previousRootRemoved
        }
        val proof = proof(operation)
        val directory = "${state.remoteRoot}/${operation.id}"
        val ack = "MAESTRO_RECOVERY_${operation.token.replace("-", "")}="
        val script = buildString {
            append("umask 077; ")
            append("[ ! -L ${quote(state.remoteRoot)} ] || exit 119; ")
            append("if [ ! -d ${quote(state.remoteRoot)} ]; then mkdir ${quote(state.remoteRoot)} || exit 119; fi; ")
            append("[ ! -L ${quote(state.remoteRoot + "/files")} ] || exit 119; ")
            append("if [ ! -d ${quote(state.remoteRoot + "/files")} ]; then mkdir ${quote(state.remoteRoot + "/files")} || exit 119; fi; ")
            append("mkdir ${quote(directory)} || exit 119; ")
            // A lost HDC client must not cause a trap to assert that the child finished. Only the
            // foreground command's actual return reaches the proof write below.
            append("trap '' HUP; ")
            append(arguments.joinToString(" ", transform = ::quote))
            append("; __maestro_recovery_status=\$?; ")
            append("printf '%s:%s\\n' ${quote(proof)} \"\$__maestro_recovery_status\" > ${quote(directory + "/done.pending")} || exit 120; ")
            append("mv ${quote(directory + "/done.pending")} ${quote(directory + "/done")} || exit 120; ")
            append("printf '\\n${ack}%s\\n' \"\$__maestro_recovery_status\"; exit \"\$__maestro_recovery_status\"")
        }
        var error: HdcCommandException? = null
        val previousBoundary = dispatchBoundary.get()
        val boundary = DispatchBoundary(previousBoundary, operation.id)
        dispatchBoundary.set(boundary)
        val result = try { execute(listOf("sh", "-c", script)) } catch (failure: BeforeDispatchFailure) {
            // Only hostCall's own failed registration can supply this private, scoped proof.
            // A callback entered earlier in this scope (including Process.start/observer) forbids rollback.
            if (failure.boundary === boundary && !boundary.entered) synchronized(this) {
                rollbackOperation(operation, previousRootRemoved, failure.original)
            }
            throw failure.original
        } catch (failure: HdcCommandException) {
            error = failure
            failure.result
        } finally {
            if (previousBoundary == null) dispatchBoundary.remove() else dispatchBoundary.set(previousBoundary)
        }
        val completion = Regex("\\r?\\n" + Regex.escape(ack) + "([0-9]{1,3})\\r?\\n?$").find(result.stdout)
        val completedStatus = completion?.groupValues?.get(1)?.toIntOrNull()
        if (completedStatus != null && completedStatus in 0..255) {
            synchronized(this) { operation.completed = true; persist() }
            val original = result.copy(exitCode = if (result.exitCode == 0) completedStatus else result.exitCode,
                stdout = result.stdout.substring(0, checkNotNull(completion).range.first))
            if (error != null || completedStatus != 0) throw HdcCommandException(original, confirmedRemoteExitCode = completedStatus)
            return original
        }
        if (error != null) throw error
        throw HdcException("The tracked command did not return its completion proof; recovery remains unknown.")
    }

    internal fun <T> hostCall(
        allowSealed: Boolean = false,
        beforeDispatch: (hostCallId: String) -> Unit = {},
        execute: ((HostProcess) -> Unit) -> T,
    ): T {
        val boundary = dispatchBoundary.get()
        val call = synchronized(this) {
            if (allowSealed) ensureOpen() else requireDispatch()
            val call = HostCall(UUID.randomUUID().toString())
            state.hostCalls += call
            activeCalls++
            try {
                persist()
                // The caller must durably bind this ID before any runner can be entered.
                beforeDispatch(call.id)
            } catch (failure: Throwable) {
                state.hostCalls.remove(call)
                activeCalls--
                try { persistRollback(failure) } finally {
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                }
                if (boundary != null && !boundary.entered) throw BeforeDispatchFailure(boundary, failure)
                throw failure
            }
            call
        }
        // Set before entering any runner, not after a PID observer: starting may already have effects.
        var enteredBoundary = boundary
        while (enteredBoundary != null) {
            enteredBoundary.entered = true
            enteredBoundary = enteredBoundary.parent
        }
        var primary: Throwable? = null
        try {
            return execute { identity ->
                synchronized(this) {
                    if (identity !in call.processes) { call.processes += identity; persist() }
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            synchronized(this) {
                call.returned = true
                // Production runner reports native processes. In-process test/custom runners
                // return only after their own finite work has unwound.
                call.stopped = if (call.processes.isEmpty()) true else call.processes.all(::processStopped)
                activeCalls--
                try { persist() } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) throw failure
                    if (failure !== original) original.addSuppressed(failure)
                }
            }
        }
    }

    /** Requires the caller's exclusive device lease. Does not issue input, launch, or retry commands. */
    @Synchronized fun recover(hdc: HdcClient, timeout: Duration = Duration.ofSeconds(10), cleanupResources: Boolean = true,
                              hostProcessGrace: Duration = Duration.ZERO): RecoveryReport {
        ensureOpen()
        require(timeout > Duration.ZERO && timeout <= Duration.ofMinutes(1))
        require(!hostProcessGrace.isNegative && hostProcessGrace <= Duration.ofSeconds(10))
        if (!state.sealed || activeCalls != 0) return report()
        if (state.operations.isEmpty() && state.rootRemoved && state.hostCalls.all { it.stopped }) {
            // Allocating a path is local bookkeeping; no remote directory is created before shell dispatch.
            if (cleanupResources) { state.temporaryFiles.clear(); persist() }
            return report()
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        val problems = mutableListOf<RecoveryIssue>()
        fun remaining(): Duration {
            val nanos = deadline - System.nanoTime()
            if (nanos <= 0) throw HdcTimeoutException(timeout)
            return Duration.ofNanos(nanos)
        }
        fun query(script: String): String {
            val result = hdc.shellRecovery(deviceId, listOf("sh", "-c", script), remaining(), this)
            if (result.stderr.isNotBlank()) throw HdcException("Recovery query returned diagnostics.")
            return result.stdout.trim()
        }
        fun refreshHostCalls() {
            var changed = false
            state.hostCalls.filterNot { it.stopped }.forEach { call ->
                val owned = ownedProcesses(call)
                if (owned.isNotEmpty() && owned.all(::processStopped)) {
                    call.stopped = true
                    changed = true
                }
            }
            if (changed) persist()
        }
        refreshHostCalls()
        // A finite HDC client may finish just before its console child exits. Recheck only
        // identities already in the durable journal, within the existing cleanup budget.
        // Missing identities and processes that remain alive still require explicit recovery.
        val hostDeadline = minOf(deadline, System.nanoTime() + hostProcessGrace.toNanos())
        while (state.hostCalls.any { !it.stopped } &&
            state.hostCalls.none { !it.stopped && it.processes.isEmpty() } && System.nanoTime() < hostDeadline) {
            val waitNanos = minOf(hostDeadline - System.nanoTime(), 50_000_000L)
            if (waitNanos <= 0) break
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNanos)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return report(listOf(RecoveryIssue("RECOVERY_INTERRUPTED", "Native process confirmation was interrupted.")))
            }
            refreshHostCalls()
        }
        if (state.hostCalls.any { !it.stopped }) return report()
        try {
            for (operation in state.operations.filterNot { it.completed }) {
                if (!operation.recoverable) {
                    problems += RecoveryIssue("UNSUPPORTED_RECOVERY_SCOPE", "The interrupted command has no declared finite recovery scope.")
                    continue
                }
                val directory = "${state.remoteRoot}/${operation.id}"
                val script = "[ ! -L ${quote(state.remoteRoot)} ] && [ -d ${quote(state.remoteRoot)} ] && " +
                    "[ ! -L ${quote(directory)} ] && [ -d ${quote(directory)} ] && " +
                    "[ ! -L ${quote(directory + "/done")} ] && [ -f ${quote(directory + "/done")} ] && cat ${quote(directory + "/done")}"
                val observed = query(script)
                if (Regex(Regex.escape(proof(operation)) + ":[0-9]{1,3}").matches(observed) &&
                    observed.substringAfterLast(':').toInt() in 0..255) {
                    operation.completed = true
                    persist()
                } else problems += RecoveryIssue("REMOTE_COMPLETION_UNCONFIRMED", "The command-specific completion proof is absent or invalid.")
            }
            if (state.operations.any { !it.completed }) return report(problems)
            if (!cleanupResources) return report(problems)
            // Completion evidence is durable before deleting any remote proof or owned file.
            // Bounded batches stay below native command-line limits. They remove only explicit
            // owned paths and share the caller's deadline. A partial cleanup remains retryable.
            if (state.temporaryFiles.isNotEmpty() || state.operations.any { !it.markerRemoved } || !state.rootRemoved) {
                val steps = mutableListOf<Pair<String, () -> Unit>>()
                for (resources in state.temporaryFiles.toList().chunked(80)) {
                    resources.forEach(::requireOwnedFile)
                    // The journal validates these basenames: no whitespace, slash or glob.
                    // Send the shared root once instead of repeating it for every check.
                    val names = resources.joinToString(" ") { it.substringAfterLast('/') }
                    val script = "set -f; IFS=' '; __maestro_cleanup_root=${quote(state.remoteRoot + "/files")}; " +
                        "__maestro_cleanup_names=${quote(names)}; " +
                        "for __maestro_cleanup_name in \$__maestro_cleanup_names; do " +
                        "__maestro_cleanup_file=\"\$__maestro_cleanup_root/\$__maestro_cleanup_name\"; " +
                        "[ ! -L \"\$__maestro_cleanup_file\" ] && rm -f \"\$__maestro_cleanup_file\" && " +
                        "[ ! -e \"\$__maestro_cleanup_file\" ] || exit 121; done; "
                    steps += script to { state.temporaryFiles.removeAll(resources.toSet()); Unit }
                }
                for (operations in state.operations.filterNot { it.markerRemoved }.chunked(100)) {
                    // UUIDs alone keep long suites within the same bounded HDC budget.
                    // Never enumerate or recursively remove unregistered directories.
                    val ids = operations.joinToString(" ") { it.id }
                    val script = "set -f; IFS=' '; __maestro_cleanup_root=${quote(state.remoteRoot)}; " +
                        "__maestro_cleanup_ids=${quote(ids)}; " +
                        "__maestro_cleanup_files=''; __maestro_cleanup_existing=''; " +
                        "for __maestro_cleanup_id in \$__maestro_cleanup_ids; do " +
                        "__maestro_cleanup_dir=\"\$__maestro_cleanup_root/\$__maestro_cleanup_id\"; " +
                        "if [ -e \"\$__maestro_cleanup_dir\" ] || [ -L \"\$__maestro_cleanup_dir\" ]; then " +
                        "[ -d \"\$__maestro_cleanup_dir\" ] && [ ! -L \"\$__maestro_cleanup_dir\" ] || exit 121; " +
                        "__maestro_cleanup_files=\"\$__maestro_cleanup_files \$__maestro_cleanup_dir/done \$__maestro_cleanup_dir/done.pending\"; " +
                        "__maestro_cleanup_existing=\"\$__maestro_cleanup_existing \$__maestro_cleanup_dir\"; fi; done; " +
                        "if [ -n \"\$__maestro_cleanup_existing\" ]; then " +
                        "rm -f \$__maestro_cleanup_files && rmdir \$__maestro_cleanup_existing || exit 121; fi; "
                    steps += script to { operations.forEach { it.markerRemoved = true } }
                }
                val files = state.remoteRoot + "/files"
                val rootCleanup = "if [ -d ${quote(files)} ]; then rmdir ${quote(files)} || exit 121; fi; " +
                    "if [ -d ${quote(state.remoteRoot)} ]; then rmdir ${quote(state.remoteRoot)} || exit 121; fi; " +
                    "[ ! -e ${quote(state.remoteRoot)} ] && [ ! -L ${quote(state.remoteRoot)} ] || exit 121; "
                steps += rootCleanup to { state.rootRemoved = true }
                val batch = mutableListOf<Pair<String, () -> Unit>>()
                var batchSize = 0
                fun flush() {
                    if (batch.isEmpty()) return
                    val nonce = UUID.randomUUID().toString()
                    val script = safeRootPrefix() + batch.joinToString("") { it.first } + "printf '%s' ${quote(nonce)}"
                    if (query(script) != nonce) throw HdcException("Owned session resource cleanup was not confirmed.")
                    batch.forEach { it.second() }
                    persist()
                    batch.clear()
                    batchSize = 0
                }
                for (step in steps) {
                    if (batchSize + step.first.length > 6 * 1024) flush()
                    batch += step
                    batchSize += step.first.length
                }
                flush()
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            problems += RecoveryIssue("RECOVERY_QUERY_FAILED", error.message ?: error.javaClass.simpleName)
        }
        return report(problems)
    }

    @Synchronized override fun close() {
        if (closed) return
        state.sealed = true
        try { persist() } finally { closed = true; ownerLock.release(); ownerChannel.close() }
    }

    private fun safeRootPrefix(): String = "[ ! -L ${quote(state.remoteRoot)} ] && [ ! -L ${quote(state.remoteRoot + "/files")} ] || exit 121; "
    private fun proof(operation: Operation): String = "${state.generation}:${state.ownerId}:${operation.id}:${operation.sha256}:${operation.token}"
    private fun requireOwnedFile(remote: String) {
        require(remote.matches(Regex(Regex.escape(state.remoteRoot) + "/files/[a-z]+-[0-9a-f-]{36}\\.[a-z][a-z0-9]*"))) { "Resource does not belong to this session." }
    }
    private fun report(extra: List<RecoveryIssue> = emptyList()): RecoveryReport {
        val issues = extra.toMutableList()
        if (persistenceUnconfirmed) issues += RecoveryIssue("JOURNAL_PERSISTENCE_UNCONFIRMED", "The latest journal state has not been durably confirmed.")
        if (!state.sealed) issues += RecoveryIssue("SESSION_ACTIVE", "The journal is not sealed against new dispatch.")
        if (activeCalls != 0) issues += RecoveryIssue("HOST_CALL_ACTIVE", "A local invocation is still active.")
        if (state.hostCalls.any { !it.stopped }) issues += RecoveryIssue("HOST_PROCESS_UNCONFIRMED", "A native invocation has no confirmed termination.")
        if (state.operations.any { !it.completed }) issues += RecoveryIssue("REMOTE_COMPLETION_UNCONFIRMED", "One or more remote commands have no completion proof.")
        if (state.temporaryFiles.isNotEmpty() || state.operations.any { !it.markerRemoved } || !state.rootRemoved) {
            issues += RecoveryIssue("OWNED_RESOURCES_REMAIN", "Owned remote resources have not all been confirmed removed.")
        }
        return RecoveryReport(if (issues.isEmpty()) "complete" else "unknown", state.operations.size,
            state.operations.count { !it.completed }, state.temporaryFiles.size, issues.distinct())
    }
    private fun ensureOpen() = check(!closed) { "Recovery journal is closed." }
    private fun requireDispatch() {
        ensureOpen()
        check(!state.sealed) { "Recovery journal is sealed; no new dispatch is allowed." }
        check(!persistenceUnconfirmed) { "Recovery journal persistence is unconfirmed; no new dispatch is allowed." }
    }
    private fun rollbackOperation(operation: Operation, previousRootRemoved: Boolean, failure: Throwable) {
        state.operations.remove(operation)
        // Another thread may have registered or dispatched work while this callback unwound.
        if (previousRootRemoved && state.operations.all { it.markerRemoved } &&
            (state.temporaryFiles.isEmpty() || state.operations.isEmpty())) state.rootRemoved = true
        persistRollback(failure)
    }
    private fun persistRollback(original: Throwable) {
        try { persist() } catch (failure: Throwable) {
            if (failure !== original) original.addSuppressed(failure)
        }
    }
    private fun persist() {
        try {
            writeState(path, mapper.writeValueAsBytes(state))
            persistenceUnconfirmed = false
        } catch (failure: Throwable) {
            persistenceUnconfirmed = true
            throw failure
        }
    }

    companion object {
        private val mapper = jacksonObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        private fun quote(text: String) = HdcClient.quoteShellArgument(text)
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun uuid(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun processStopped(identity: HostProcess): Boolean {
            return try {
                val process = ProcessHandle.of(identity.pid).orElse(null)
                when {
                    process == null || !process.isAlive -> true
                    identity.startedAt == null -> false
                    else -> {
                        val started = process.info().startInstant().orElse(null)?.toString()
                        started != null && identity.startedAt != started
                    }
                }
            } catch (_: Exception) { false }
        }
        private fun ownedProcesses(call: HostCall): List<HostProcess> {
            val root = call.processes.firstOrNull()?.startedAt?.let(java.time.Instant::parse) ?: return call.processes
            return call.processes.filterIndexed { index, process ->
                val started = process.startedAt?.let(java.time.Instant::parse)
                val external = index > 0 && started != null && started < root
                if (external) System.err.println("MAESTRO_RECOVERY_IGNORED_PREEXISTING_PROCESS call=${call.id} pid=${process.pid} startedAt=$started rootStartedAt=$root; original journal entry retained")
                !external
            }
        }
        fun create(path: Path, deviceId: String, ownerId: String): HarmonyOSRecoveryJournal =
            create(path, deviceId, ownerId, HarmonyOSJournalFileWriter()::write)

        internal fun create(path: Path, deviceId: String, ownerId: String, writeState: (Path, ByteArray) -> Unit): HarmonyOSRecoveryJournal {
            require(uuid(ownerId) && deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")))
            val generation = UUID.randomUUID().toString()
            return locked(path) { file, channel, lock ->
                require(!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { "Recovery journal already exists." }
                HarmonyOSRecoveryJournal(file, State(deviceId = deviceId, ownerId = ownerId, generation = generation,
                    remoteRoot = "/data/local/tmp/maestro-session-$generation"), channel, lock, writeState).also { it.persist() }
            }
        }
        fun open(path: Path): HarmonyOSRecoveryJournal = locked(path) { file, channel, lock ->
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 32 * 1024 * 1024) { "Invalid recovery journal." }
            val tree = mapper.readTree(file.toFile())
            validateFields(tree)
            // The maintenance chain compares the original journal, including array order.
            // Jackson's default MutableSet implementation must not reorder saved ownership.
            val temporaryPaths = tree["temporaryFiles"].map { it.asText() }
            require(temporaryPaths.distinct().size == temporaryPaths.size) { "Duplicate temporary resource." }
            val state = mapper.treeToValue(tree, State::class.java).copy(
                temporaryFiles = temporaryPaths.toCollection(linkedSetOf()))
            require(state.version == 1 && uuid(state.ownerId) && uuid(state.generation) &&
                state.remoteRoot == "/data/local/tmp/maestro-session-${state.generation}" &&
                state.deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*"))) { "Invalid recovery owner or device." }
            require(state.operations.map { it.id }.distinct().size == state.operations.size)
            require(state.operations.all { uuid(it.id) && uuid(it.token) && it.sha256.matches(Regex("[0-9a-f]{64}")) })
            require(state.hostCalls.all { uuid(it.id) && it.processes.all { process -> process.pid > 0 } })
            require(state.operations.all { !it.markerRemoved || it.completed } &&
                (!state.rootRemoved || state.operations.all { it.markerRemoved }) &&
                (!state.rootRemoved || state.temporaryFiles.isEmpty() || state.operations.isEmpty())) { "Inconsistent recovery evidence." }
            state.sealed = true
            HarmonyOSRecoveryJournal(file, state, channel, lock).also { journal ->
                state.temporaryFiles.forEach(journal::requireOwnedFile)
                val recordingsPath = file.resolveSibling("recording-resources.json")
                if (Files.exists(recordingsPath, LinkOption.NOFOLLOW_LINKS)) {
                    journal.recordings = HarmonyOSRecordingResourceJournal.open(recordingsPath, state.deviceId,
                        state.ownerId, state.generation, state.remoteRoot)
                }
                journal.persist()
            }
        }
        private fun validateFields(tree: JsonNode) {
            fun fields(node: JsonNode, expected: Set<String>) {
                require(node.isObject && node.fieldNames().asSequence().toSet() == expected) { "Incomplete or unknown recovery fields." }
            }
            fun texts(node: JsonNode, vararg names: String) { require(names.all { node[it].isTextual }) { "Invalid recovery text." } }
            fun booleans(node: JsonNode, vararg names: String) { require(names.all { node[it].isBoolean }) { "Invalid recovery flag." } }
            fields(tree, setOf("version", "deviceId", "ownerId", "generation", "remoteRoot", "sealed", "rootRemoved", "operations", "temporaryFiles", "hostCalls"))
            require(tree["version"].isIntegralNumber && tree["version"].canConvertToInt())
            texts(tree, "deviceId", "ownerId", "generation", "remoteRoot")
            booleans(tree, "sealed", "rootRemoved")
            require(listOf("operations", "temporaryFiles", "hostCalls").all { tree[it].isArray })
            tree["operations"].forEach { operation ->
                fields(operation, setOf("id", "token", "sha256", "recoverable", "completed", "markerRemoved"))
                texts(operation, "id", "token", "sha256")
                booleans(operation, "recoverable", "completed", "markerRemoved")
            }
            require(tree["temporaryFiles"].all { it.isTextual })
            tree["hostCalls"].forEach { call ->
                fields(call, setOf("id", "processes", "returned", "stopped"))
                texts(call, "id")
                booleans(call, "returned", "stopped")
                require(call["processes"].isArray)
                require(!call["stopped"].booleanValue() || call["returned"].booleanValue() || !call["processes"].isEmpty) { "Missing native termination evidence." }
                call["processes"].forEach { process ->
                    fields(process, setOf("pid", "startedAt"))
                    require(process["pid"].isIntegralNumber && process["pid"].canConvertToLong() && process["pid"].longValue() > 0)
                    require(process["startedAt"].isNull || process["startedAt"].isTextual)
                    if (!process["startedAt"].isNull) java.time.Instant.parse(process["startedAt"].textValue())
                }
            }
        }
        private fun <T> locked(path: Path, action: (Path, FileChannel, FileLock) -> T): T {
            val file = path.toAbsolutePath().normalize()
            Files.createDirectories(file.parent)
            require(file.parent.toRealPath() == file.parent && !Files.isSymbolicLink(file)) { "Recovery journal must not follow symbolic links." }
            val lockPath = file.resolveSibling("${file.fileName}.lock")
            require(!Files.isSymbolicLink(lockPath))
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) { channel.close(); throw HdcException("The recovery journal is owned by an active process.") }
            return try { action(file, channel, lock) } catch (error: Throwable) { lock.release(); channel.close(); throw error }
        }
    }
}
