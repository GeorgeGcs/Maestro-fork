package maestro.harmonyos.hdc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import maestro.harmonyos.hdc.HdcCallDiagnostics.Phase

data class HdcCommandResult(val exitCode: Int, val stdout: String, val stderr: String)

fun interface HdcCommandRunner {
    fun run(command: List<String>, timeout: Duration): HdcCommandResult
}

open class HdcException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class HdcTimeoutException @JvmOverloads constructor(
    val timeout: Duration,
    message: String = "HDC command exceeded ${timeout.toMillis()} ms",
) : HdcException(message)

class HdcOutputLimitException(stream: String, limit: Int) :
    HdcException("HDC $stream exceeded the $limit byte output limit")

/** Runs one finite command. Each output stream has its own memory limit. */
class ProcessHdcCommandRunner(
    private val maxOutputBytes: Int = 4 * 1024 * 1024,
    private val diagnosticSink: (String) -> Unit = { HdcCallDiagnostics.log(it) },
) : HdcCommandRunner {

    init {
        require(maxOutputBytes > 0) { "Output limit must be positive" }
    }

    override fun run(command: List<String>, timeout: Duration): HdcCommandResult = runObserved(command, timeout) {}

    internal fun runObserved(command: List<String>, timeout: Duration, observer: (HarmonyOSRecoveryJournal.HostProcess) -> Unit): HdcCommandResult {
        val diagnostics = HdcCallDiagnostics(HdcCommandCategory.UNKNOWN, timeout, emitLine = diagnosticSink)
        diagnostics.begin(Phase.DISPATCH)
        diagnostics.end(Phase.DISPATCH)
        try {
            return runObserved(command, timeout, diagnostics, observer)
        } catch (error: Throwable) {
            diagnostics.failure(error)
            throw error
        } finally { diagnostics.finish() }
    }

    internal fun runObserved(command: List<String>, timeout: Duration, diagnostics: HdcCallDiagnostics,
                             observer: (HarmonyOSRecoveryJournal.HostProcess) -> Unit): HdcCommandResult {
        require(command.isNotEmpty() && command.first().isNotBlank()) { "An executable is required" }
        require(command.none { '\u0000' in it }) { "Command arguments cannot contain NUL" }
        require(!timeout.isZero && !timeout.isNegative) { "Timeout must be positive" }
        val timeoutNanos = timeout.toNanos()
        val deadline = System.nanoTime() + timeoutNanos
        diagnostics.processBudget(timeout)
        diagnostics.begin(Phase.SPAWN)
        val process = try {
            ProcessBuilder(processArguments(command)).start()
        } catch (e: IOException) {
            throw HdcException("Unable to start HDC executable: ${command.first()}", e)
        }
        diagnostics.end(Phase.SPAWN)
        val rootIdentity = HarmonyOSRecoveryJournal.HostProcess(process.pid(), runCatching { process.info().startInstant().orElse(null)?.toString() }.getOrNull())
        val processTree = OwnedProcessTree(rootIdentity)
        val readers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "maestro-hdc-output").apply { isDaemon = true }
        }
        try {
            observer(rootIdentity)
            // Finite commands must not wait for keyboard input.
            process.outputStream.close()
            diagnostics.begin(Phase.STDOUT_READ, active = false)
            val stdout = readers.submit<String> { readOutput(process.inputStream, "stdout", diagnostics, Phase.STDOUT_READ) }
            diagnostics.begin(Phase.STDERR_READ, active = false)
            val stderr = readers.submit<String> { readOutput(process.errorStream, "stderr", diagnostics, Phase.STDERR_READ) }
            diagnostics.begin(Phase.WAIT_EXIT)
            while (true) {
                processTree.refresh()
                processTree.identities().forEach(observer)
                try { checkReader(stdout) } catch (error: Throwable) { diagnostics.failure(error, Phase.STDOUT_READ); throw error }
                try { checkReader(stderr) } catch (error: Throwable) { diagnostics.failure(error, Phase.STDERR_READ); throw error }
                val remaining = remainingNanos(deadline, timeout)
                if (process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)) {
                    break
                }
            }
            val exit = process.exitValue()
            diagnostics.end(Phase.WAIT_EXIT, exit = exit)
            diagnostics.begin(Phase.DRAIN_STDOUT)
            val output = awaitOutput(stdout, deadline, timeout)
            diagnostics.end(Phase.DRAIN_STDOUT)
            diagnostics.begin(Phase.DRAIN_STDERR)
            val errors = awaitOutput(stderr, deadline, timeout)
            diagnostics.end(Phase.DRAIN_STDERR)
            return HdcCommandResult(exit, output, errors)
        } catch (e: InterruptedException) {
            diagnostics.failure(e)
            terminateObserved(processTree, observer, diagnostics)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            diagnostics.failure(e)
            terminateObserved(processTree, observer, diagnostics)
            throw e
        } finally {
            readers.shutdownNow()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private fun readOutput(stream: InputStream, name: String, diagnostics: HdcCallDiagnostics, phase: Phase): String {
        val output = ByteArrayOutputStream(minOf(maxOutputBytes, 8192))
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > maxOutputBytes - output.size()) throw HdcOutputLimitException(name, maxOutputBytes)
                output.write(buffer, 0, count)
            }
            diagnostics.end(phase, bytes = output.size().toLong())
            return output.toString(Charsets.UTF_8)
        } catch (error: Throwable) {
            diagnostics.phaseFailed(phase, error)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun processArguments(command: List<String>): List<String> {
        if (!System.getProperty("os.name").startsWith("Windows")) return command
        val executable = command.first()
        require('"' !in executable) { "Pass the Windows executable path without surrounding quotes" }
        require(!executable.endsWith(".cmd", true) && !executable.endsWith(".bat", true)) {
            "HDC must be a native executable, not a Windows command script"
        }
        val securityManager = System.getSecurityManager()
        val ambiguousCommands = System.getProperty(
            "jdk.lang.Process.allowAmbiguousCommands", if (securityManager == null) "true" else "false",
        )
        if (ambiguousCommands.equals("false", ignoreCase = true)) {
            // The JDK escapes embedded quotes in strict mode, but interprets a leading/trailing
            // quote pair as caller-supplied quoting and would silently strip literal quotes.
            require(command.drop(1).none { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }) {
                "This JDK Windows strict quoting mode cannot preserve an argument enclosed in literal double quotes"
            }
            return command
        }
        require(securityManager == null) { "Legacy Windows argument quoting with a SecurityManager is not supported" }

        // JDK 17's default Windows mode does not escape embedded quotes. Encode complete CRT
        // arguments here; ProcessImpl preserves these outer-quoted strings without re-encoding.
        return listOf(executable) + command.drop(1).map(::quoteWindowsArgument)
    }

    private fun quoteWindowsArgument(value: String): String = buildString {
        append('"')
        var backslashes = 0
        value.forEach { character ->
            if (character == '\\') {
                backslashes++
            } else {
                repeat(if (character == '"') backslashes * 2 + 1 else backslashes) { append('\\') }
                append(character)
                backslashes = 0
            }
        }
        // Backslashes immediately before the closing quote must be doubled.
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }

    private fun checkReader(reader: Future<String>) {
        if (reader.isDone) unwrap { reader.get() }
    }

    private fun awaitOutput(reader: Future<String>, deadline: Long, timeout: Duration): String = try {
        unwrap { reader.get(remainingNanos(deadline, timeout), TimeUnit.NANOSECONDS) }
    } catch (_: TimeoutException) {
        throw HdcTimeoutException(timeout)
    }

    private fun <T> unwrap(action: () -> T): T = try {
        action()
    } catch (e: ExecutionException) {
        val cause = e.cause
        if (cause is HdcException) throw cause
        throw HdcException("Unable to read HDC command output", cause ?: e)
    }

    private fun remainingNanos(deadline: Long, timeout: Duration): Long {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw HdcTimeoutException(timeout)
        return remaining
    }

    private fun terminateObserved(processTree: OwnedProcessTree, observer: (HarmonyOSRecoveryJournal.HostProcess) -> Unit,
                                  diagnostics: HdcCallDiagnostics) {
        diagnostics.begin(Phase.TERMINATE)
        try {
            terminate(processTree, observer)
            diagnostics.end(Phase.TERMINATE)
        } catch (error: Throwable) {
            diagnostics.phaseFailed(Phase.TERMINATE, error)
            throw error
        }
    }

    private fun terminate(processTree: OwnedProcessTree, observer: (HarmonyOSRecoveryJournal.HostProcess) -> Unit) {
        runCatching { processTree.refresh() }
        processTree.identities().forEach { identity -> runCatching { observer(identity) } }
        processTree.terminate()
        // destroyForcibly requests termination; it is not an exit acknowledgement. Recovery
        // also retains native identities when a process does not finish within this allowance.
        var interrupted = Thread.interrupted()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (processTree.hasUnconfirmedProcesses() && System.nanoTime() < deadline) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
