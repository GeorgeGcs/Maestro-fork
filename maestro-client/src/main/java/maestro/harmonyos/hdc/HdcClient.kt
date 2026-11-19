package maestro.harmonyos.hdc

import java.nio.file.Path
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration

class HdcCommandException(val result: HdcCommandResult, val confirmedRemoteExitCode: Int? = null) :
    HdcException("HDC command failed (exit ${result.exitCode}): ${diagnostic(result)}") {
    companion object {
        private fun diagnostic(result: HdcCommandResult): String =
            result.stderr.ifBlank { result.stdout }.trim().take(2048)
    }
}

class HdcDeviceSelectionException(message: String) : HdcException(message)

/** Host transport only; shell command availability must be checked on the target device. */
class HdcClient(
    private val executablePath: String = defaultExecutable(),
    private val runner: HdcCommandRunner = ProcessHdcCommandRunner(),
    private val commandTimeout: Duration = Duration.ofSeconds(10),
    private val recoveryJournal: HarmonyOSRecoveryJournal? = null,
    private val resourceRecovery: Boolean = false,
    private val diagnosticSink: (String) -> Unit = { HdcCallDiagnostics.log(it) },
) {
    init {
        require(executablePath.isNotBlank() && '\u0000' !in executablePath) { "A valid HDC executable is required" }
        require(!commandTimeout.isZero && !commandTimeout.isNegative) { "Timeout must be positive" }
    }

    fun withRecoveryJournal(journal: HarmonyOSRecoveryJournal): HdcClient =
        HdcClient(executablePath, runner, commandTimeout, journal, diagnosticSink = diagnosticSink)

    internal fun forResourceRecovery(journal: HarmonyOSRecoveryJournal): HdcClient =
        HdcClient(executablePath, runner, commandTimeout, journal, resourceRecovery = true, diagnosticSink = diagnosticSink)

    internal fun recordingResources(): HarmonyOSRecoveryJournal? = recoveryJournal

    internal fun allocateTemporaryFile(kind: String, extension: String): String =
        recoveryJournal?.allocateTemporaryFile(kind, extension)
            ?: "/data/local/tmp/maestro-$kind-${java.util.UUID.randomUUID()}.$extension"

    internal fun forgetTemporaryFile(path: String) { recoveryJournal?.forgetTemporaryFile(path) }

    fun listDevices(): List<String> {
        val result = execute(listOf("list", "targets"), commandTimeout, category = HdcCommandCategory.LIST_TARGETS)
        if (result.stderr.isNotBlank()) {
            throw HdcException("HDC device listing returned diagnostics: ${result.stderr.trim().take(2048)}")
        }
        return parseDeviceList(result.stdout)
    }

    fun selectDevice(requestedId: String? = null): String {
        if (requestedId != null) validateDeviceId(requestedId)
        val devices = listDevices()
        if (requestedId != null) {
            if (requestedId !in devices) throw HdcDeviceSelectionException("The requested HDC device is not available")
            return requestedId
        }
        return when (devices.size) {
            0 -> throw HdcDeviceSelectionException("No HDC device found. Connect and authorize a device first.")
            1 -> devices.single()
            else -> throw HdcDeviceSelectionException("Multiple HDC devices found. Select a device explicitly.")
        }
    }

    /** Verify the exact owner and endpoint before any loopback connection. */
    internal fun hasUitestLayoutForward(deviceId: String, port: Int, timeout: Duration): Boolean {
        validateDeviceId(deviceId)
        return matchesUitestLayoutForward(listForwards(timeout), deviceId, port)
    }

    private fun listForwards(timeout: Duration): String {
        val result = execute(listOf("fport", "ls"), timeout, category = HdcCommandCategory.LAYOUT_FORWARD_LIST)
        if (result.stderr.isNotBlank()) throw HdcException("HDC forward listing returned diagnostics")
        val rows = result.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (rows != listOf("[Empty]") && (rows.isEmpty() || rows.any { !forwardRow.matches(it) })) {
            throw HdcException("HDC forward listing has an unrecognized format.")
        }
        return result.stdout
    }

    /** Uses a running UITest service; creating a transport never starts or replaces a device daemon. */
    internal fun openAutomaticUitestLayoutForward(deviceId: String, timeout: Duration): Int? {
        validateDeviceId(deviceId)
        val journal = recoveryJournal ?: return null
        check(journal.deviceId == deviceId) { "UITest forward does not match the session target." }
        val deadline = System.nanoTime() + timeout.toNanos()
        fun remaining(): Duration {
            val nanos = deadline - System.nanoTime()
            if (nanos <= 0) throw HdcTimeoutException(timeout)
            return Duration.ofNanos(nanos)
        }
        val sockets = execute(listOf("-t", deviceId, "shell", "cat /proc/net/unix"), remaining(),
            category = HdcCommandCategory.SERVICE_QUERY)
        if (sockets.stderr.isNotBlank() || sockets.stdout.lineSequence().none { line ->
                val columns = line.trim().split(Regex("\\s+"))
                columns.size == 8 && columns[3].toIntOrNull(16)?.and(0x10000) == 0x10000 &&
                    columns[4].toIntOrNull(16) == 1 && columns[7] == "@uitest_socket"
            }) return null
        val port = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        } catch (error: java.io.IOException) {
            throw HdcException("No loopback port could be allocated for UITest.", error)
        }
        if (portRows(listForwards(remaining()), port).isNotEmpty()) return null
        journal.registerLayoutForward(port)
        val created = execute(listOf("-t", deviceId, "fport", "tcp:$port", UITEST_ENDPOINT), remaining(), trackHost = true,
            category = HdcCommandCategory.LAYOUT_FORWARD_CREATE)
        if (created.stderr.isNotBlank() || created.stdout.trim().removeSuffix(".") != "Forwardport result:OK") {
            throw HdcException("HDC did not confirm creation of the UITest forward.")
        }
        journal.confirmLayoutForward(port)
        return port.takeIf { hasUitestLayoutForward(deviceId, it, remaining()) }
    }

    /** Reconcile only the registered local port. An exact but unconfirmed rule needs operator review. */
    internal fun recoverUitestLayoutForward(journal: HarmonyOSRecoveryJournal, port: Int, confirmed: Boolean,
                                             remaining: () -> Duration) {
        val deviceId = journal.deviceId
        val before = listForwards(remaining())
        val expected = listOf(deviceId, "tcp:$port", UITEST_ENDPOINT, "[Forward]")
        val rows = portRows(before, port)
        if (expected !in rows) return
        if (!confirmed || rows != listOf(expected)) throw HdcException("UITest forward ownership could not be confirmed.")
        execute(listOf("-t", deviceId, "fport", "rm", "tcp:$port", UITEST_ENDPOINT), remaining(),
            journal = journal, trackHost = true, allowSealed = true, category = HdcCommandCategory.LAYOUT_FORWARD_REMOVE)
        if (expected in portRows(listForwards(remaining()), port)) throw HdcException("UITest forward removal could not be confirmed.")
    }

    internal fun hasArkWebForward(deviceId: String, port: Int, pid: String, timeout: Duration): Boolean {
        validateDeviceId(deviceId)
        val result = execute(listOf("fport", "ls"), timeout, category = HdcCommandCategory.LAYOUT_FORWARD_LIST)
        if (result.stderr.isNotBlank()) throw HdcException("HDC forward listing returned diagnostics")
        return matchesForward(result.stdout, deviceId, port, "localabstract:webview_devtools_remote_$pid")
    }

    /**
     * Arguments are quoted for the device shell as well as kept separate from host options.
     * The exit code belongs to the host HDC process; callers must also check device-command results.
     */
    fun shell(
        deviceId: String,
        arguments: List<String>,
        timeout: Duration = commandTimeout,
    ): HdcCommandResult {
        return shellTracked(deviceId, arguments, timeout, finiteCommand(arguments))
    }

    internal fun shellFinite(deviceId: String, arguments: List<String>, timeout: Duration, finite: Boolean,
                             category: HdcCommandCategory = HdcCommandCategory.shell(arguments)): HdcCommandResult =
        shellTracked(deviceId, arguments, timeout, finite, category)

    // The caller builds these commands from fixed read-only queries. Track the local
    // HDC process, but do not create a device operation directory or await a remote receipt.
    internal fun shellDiagnostic(deviceId: String, script: String, timeout: Duration,
                                 category: HdcCommandCategory): HdcCommandResult {
        validateDeviceId(deviceId)
        check(!resourceRecovery) { "Diagnostic queries are for active sessions." }
        return rawShell(deviceId, listOf("sh", "-c", script), timeout, true, category = category)
    }

    private fun shellTracked(deviceId: String, arguments: List<String>, timeout: Duration, finite: Boolean,
                             category: HdcCommandCategory = HdcCommandCategory.shell(arguments)): HdcCommandResult {
        validateDeviceId(deviceId)
        require(arguments.isNotEmpty() && arguments.first().isNotBlank()) { "A shell command is required" }
        require(arguments.none { '\u0000' in it }) { "Shell arguments cannot contain NUL" }
        val journal = recoveryJournal
        if (journal != null) {
            require(journal.deviceId == deviceId) { "Recovery journal belongs to another target." }
            if (resourceRecovery) {
                require(finite) { "Resource recovery accepts only declared finite commands." }
                checkResourceRecoveryReady(journal)
                val result = try {
                    journal.shell(arguments, finite, allowSealed = true) { wrapped -> rawShell(deviceId, wrapped, timeout, true, allowSealed = true, category = category) }
                } catch (error: HdcCommandException) {
                    // An acknowledged nonzero status can be a valid absence result. It is
                    // safe to interpret only after its native host invocation has ended.
                    try { checkResourceRecoveryReady(journal) } catch (notReady: Exception) {
                        notReady.addSuppressed(error)
                        throw notReady
                    }
                    throw error
                }
                checkResourceRecoveryReady(journal)
                return result
            }
            return journal.shell(arguments, finite) { wrapped -> rawShell(deviceId, wrapped, timeout, true, category = category) }
        }
        return rawShell(deviceId, arguments, timeout, false, category = category)
    }

    /** Recovery does not register new device work, but its native HDC processes remain tracked. */
    internal fun shellRecovery(deviceId: String, arguments: List<String>, timeout: Duration, journal: HarmonyOSRecoveryJournal,
                               beforeDispatch: ((String) -> Unit)? = null): HdcCommandResult {
        validateDeviceId(deviceId)
        require(journal.deviceId == deviceId)
        val command = arguments.joinToString(" ", transform = ::quoteShellArgument)
        return execute(listOf("-t", deviceId, "shell", command), timeout, true, journal, true, beforeDispatch, HdcCommandCategory.shell(arguments))
    }

    private fun rawShell(deviceId: String, arguments: List<String>, timeout: Duration, trackHost: Boolean,
                         allowSealed: Boolean = false, category: HdcCommandCategory): HdcCommandResult {
        val command = arguments.joinToString(" ", transform = ::quoteShellArgument)
        return execute(listOf("-t", deviceId, "shell", command), timeout, trackHost, allowSealed = allowSealed, category = category)
    }

    fun receiveFile(
        deviceId: String,
        remotePath: String,
        localPath: Path,
        timeout: Duration = commandTimeout,
    ): HdcCommandResult {
        validateDeviceId(deviceId)
        require(remotePath.startsWith('/') && '\u0000' !in remotePath && '\n' !in remotePath && '\r' !in remotePath) {
            "An absolute device file path is required"
        }
        recoveryJournal?.let { require(it.deviceId == deviceId) { "Recovery journal belongs to another target." } }
        if (resourceRecovery) checkResourceRecoveryReady(checkNotNull(recoveryJournal))
        return execute(listOf("-t", deviceId, "file", "recv", remotePath, localPath.toAbsolutePath().toString()), timeout, recoveryJournal != null,
            allowSealed = resourceRecovery, category = HdcCommandCategory.FILE_RECEIVE)
            .also { if (resourceRecovery) checkResourceRecoveryReady(checkNotNull(recoveryJournal)) }
    }

    internal fun <T> externalMutation(arguments: List<String>, dispatch: () -> T): T =
        if (recoveryJournal == null) dispatch() else recoveryJournal.externalMutation(arguments, dispatch)

    internal fun sendGalleryFile(deviceId: String, local: Path, remote: String, bytes: Long, timeout: Duration) {
        validateDeviceId(deviceId)
        require(remote.matches(Regex("/mnt/data/[0-9]+/media_fuse/Photo/[A-Za-z0-9][A-Za-z0-9_.-]{0,180}")))
        recoveryJournal?.let { require(it.deviceId == deviceId) { "Recovery journal belongs to another target." } }
        val arguments = listOf("-t", deviceId, "file", "send", local.toAbsolutePath().toString(), remote)
        externalMutation(arguments) {
            val result = execute(arguments, timeout, recoveryJournal != null, category = HdcCommandCategory.FILE_SEND)
            val completion = Regex("FileTransfer finish, Size:([0-9]+), File count = 1(?:,.*)?")
            if (result.stderr.isNotBlank() || result.stdout.lineSequence().none {
                    completion.matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() == bytes
                }) {
                throw HdcException("HDC did not confirm the gallery transfer byte count.")
            }
        }
    }

    private fun checkResourceRecoveryReady(journal: HarmonyOSRecoveryJournal) {
        val issues = journal.snapshot().issues.filter { it.code != "OWNED_RESOURCES_REMAIN" }
        check(issues.isEmpty()) { "Resource recovery is not quiescent: ${issues.joinToString { it.code }}" }
    }

    private fun execute(arguments: List<String>, timeout: Duration, trackHost: Boolean = false,
        journal: HarmonyOSRecoveryJournal? = recoveryJournal, allowSealed: Boolean = false,
        beforeDispatch: ((String) -> Unit)? = null, category: HdcCommandCategory): HdcCommandResult {
        val command = listOf(executablePath) + arguments
        // A maintenance binding may write to disk. Charge that work to the same command budget.
        val deadline = if (beforeDispatch == null) null else System.nanoTime() + timeout.toNanos()
        val diagnostics = HdcCallDiagnostics(category, timeout, journal?.diagnosticOperationId(), diagnosticSink)
        diagnostics.begin(HdcCallDiagnostics.Phase.DISPATCH)
        fun run(effectiveTimeout: Duration, observer: (HarmonyOSRecoveryJournal.HostProcess) -> Unit): HdcCommandResult {
            diagnostics.end(HdcCallDiagnostics.Phase.DISPATCH)
            val result = if (runner is ProcessHdcCommandRunner) runner.runObserved(command, effectiveTimeout, diagnostics, observer)
            else {
                diagnostics.processBudget(effectiveTimeout)
                diagnostics.begin(HdcCallDiagnostics.Phase.CUSTOM_RUNNER)
                runner.run(command, effectiveTimeout).also { diagnostics.end(HdcCallDiagnostics.Phase.CUSTOM_RUNNER, exit = it.exitCode) }
            }
            // Journal.hostCall can still persist return/ownership facts after the runner returns.
            diagnostics.begin(HdcCallDiagnostics.Phase.HOST_RETURN)
            return result
        }
        try {
            val result = if (trackHost && journal != null) journal.hostCall(allowSealed, { id ->
                diagnostics.bindHostCall(id)
                beforeDispatch?.invoke(id)
            }) { observer ->
                val effectiveTimeout = deadline?.let {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Maintenance dispatch was interrupted.")
                    val remaining = it - System.nanoTime()
                    if (remaining <= 0) throw HdcTimeoutException(timeout)
                    Duration.ofNanos(remaining)
                } ?: timeout
                run(effectiveTimeout, observer)
            } else run(timeout) {}
            diagnostics.end(HdcCallDiagnostics.Phase.HOST_RETURN)
            diagnostics.begin(HdcCallDiagnostics.Phase.CHECK_RESULT)
            if (result.exitCode != 0 || hasHdcFailure(result.stdout) || hasHdcFailure(result.stderr)) {
                throw HdcCommandException(result)
            }
            diagnostics.end(HdcCallDiagnostics.Phase.CHECK_RESULT, exit = result.exitCode)
            return result
        } catch (error: Throwable) {
            diagnostics.failure(error)
            throw error
        } finally {
            diagnostics.finish()
        }
    }

    companion object {
        internal fun finiteCommand(arguments: List<String>): Boolean =
            (arguments.size == 9 && arguments[0] == "snapshot_display" &&
                arguments[1] == "-f" && arguments[2].matches(Regex("/data/local/tmp/maestro-(?:session-[a-f0-9-]{36}/files/)?screen-[a-f0-9-]{36}\\.png")) &&
                arguments[3] == "-w" && arguments[4].toIntOrNull() in 2..960 &&
                arguments[5] == "-h" && arguments[6].toIntOrNull() in 2..960 &&
                arguments[7] == "-t" && arguments[8] == "png") ||
            (arguments.firstOrNull() == "uitest" && arguments.getOrNull(1) in setOf("dumpLayout", "screenCap", "uiInput")) ||
                (arguments.firstOrNull() == "aa" && arguments.getOrNull(1) in setOf("start", "force-stop")) ||
                (arguments.take(2) == listOf("rm", "-f") && arguments.size == 3) ||
                (arguments == listOf("ps", "-ef")) ||
                (arguments == listOf("hidumper", "-s", "3002", "-a", "-h")) ||
                (arguments == listOf("hidumper", "-s", "DisplayManagerService", "-a", "-a")) ||
                (arguments.size == 4 && arguments.take(3) == listOf("bm", "dump", "-n") &&
                    arguments[3].matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]*"))) ||
                (arguments.size == 5 && arguments.take(4) == listOf("bm", "clean", "-d", "-n") &&
                    arguments[4].matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]*"))) ||
                (arguments.take(3) == listOf("stat", "-c", "%s") && arguments.size == 4 && recordingScratch(arguments[3])) ||
                (arguments.size == 3 && arguments[0] == "test" && arguments[1] in setOf("-L", "-e", "-f") && recordingScratch(arguments[2])) ||
                (arguments.take(2) == listOf("mediatool", "query") && arguments.size == 4 &&
                    arguments[2].matches(Regex("maestro-recording-[0-9a-f]{32}\\.mp4")) && arguments[3] == "-u") ||
                (arguments.take(2) == listOf("mediatool", "delete") && arguments.size == 3 && recordingMedia(arguments[2])) ||
                (arguments.take(2) == listOf("mediatool", "recv") && arguments.size == 4 && recordingMedia(arguments[2]) && recordingScratch(arguments[3]))

        private fun recordingScratch(value: String) = value.matches(Regex("/data/local/tmp/maestro-session-[0-9a-f-]{36}/files/recording-[0-9a-f-]{36}\\.mp4"))
        private fun recordingMedia(value: String) = value.matches(Regex("file://media/Photo/[0-9]+/[A-Za-z0-9_.-]+/maestro-recording-[0-9a-f]{32}\\.mp4"))
        private val deviceIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        private val diagnosticTokens = setOf("error", "fail", "failed", "failure", "warning", "warn", "usage", "help", "hdc")
        private val hdcFailurePattern = Regex("(?im)^\\s*(?:\\[\\s*(?:fail|error)\\s*]|error:|failed:)")

        private fun defaultExecutable(): String = System.getenv("MAESTRO_HARMONYOS_HDC")
            ?.takeIf { it.isNotBlank() } ?: "hdc"

        private fun hasHdcFailure(output: String): Boolean = hdcFailurePattern.containsMatchIn(output)

        internal fun matchesUitestLayoutForward(output: String, deviceId: String, port: Int): Boolean {
            return matchesForward(output, deviceId, port, UITEST_ENDPOINT)
        }

        private fun matchesForward(output: String, deviceId: String, port: Int, remote: String): Boolean {
            return portRows(output, port) == listOf(listOf(deviceId, "tcp:$port", remote, "[Forward]"))
        }

        private fun portRows(output: String, port: Int): List<List<String>> = output.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }.filter { it.getOrNull(1) == "tcp:$port" }.toList()

        private const val UITEST_ENDPOINT = "localabstract:uitest_socket"
        private val forwardRow = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*\\s+\\S+:\\S+\\s+\\S+:\\S+\\s+\\[(Forward|Reverse)]")

        internal fun parseDeviceList(output: String): List<String> {
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.isEmpty() || lines == listOf("[Empty]")) return emptyList()
            if (lines.any { !deviceIdPattern.matches(it) || it.lowercase() in diagnosticTokens }) {
                throw HdcException("Unexpected HDC device-list output; no device was selected")
            }
            return lines.distinct()
        }

        private fun validateDeviceId(deviceId: String) {
            require(deviceIdPattern.matches(deviceId) && deviceId.lowercase() !in diagnosticTokens) {
                "Invalid HDC device identifier"
            }
        }

        internal fun quoteShellArgument(value: String): String {
            require('\u0000' !in value) { "Shell arguments cannot contain NUL" }
            return "'" + value.replace("'", "'\"'\"'") + "'"
        }
    }
}
