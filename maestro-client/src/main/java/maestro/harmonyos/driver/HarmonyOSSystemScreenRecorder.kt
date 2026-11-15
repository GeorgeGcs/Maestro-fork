package maestro.harmonyos.driver

import maestro.ScreenRecording
import maestro.debuglog.RecordingDiagnosticContext
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.harmonyos.driver.HarmonyOSRecordingDiagnostics.Fields
import maestro.harmonyos.driver.HarmonyOSRecordingDiagnostics.Result
import maestro.harmonyos.driver.HarmonyOSRecordingDiagnostics.Stage
import maestro.utils.TempFileHandler
import okio.Buffer
import okio.Sink
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Optional adapter for Huawei's system recorder. This is not an OpenHarmony shell API.
 * Commands match DevEco Studio 6.1.1's OpenHarmonyDevice.screenRecord/recvScreenRecord.
 * The recorder's toggle command requires exclusive use of screen recording on the device.
 */
class HarmonyOSSystemScreenRecorder internal constructor(
    private val deviceId: String,
    private val device: HarmonyOSDevice,
    private val receiveFile: (String, Path, Duration) -> Unit,
    private val config: HarmonyOSDriverConfig,
    private val recoveryJournal: HarmonyOSRecoveryJournal?,
    private val diagnosticFactory: (String, String?, String?) -> HarmonyOSRecordingDiagnostics,
) {
    constructor(
        deviceId: String,
        device: HarmonyOSDevice,
        receiveFile: (String, Path, Duration) -> Unit,
        config: HarmonyOSDriverConfig,
        recoveryJournal: HarmonyOSRecoveryJournal? = null,
    ) : this(deviceId, device, receiveFile, config, recoveryJournal, { fileName, resourceId, contextId ->
        HarmonyOSRecordingDiagnostics(fileName, resourceId, contextId)
    })

    fun start(out: Sink): ScreenRecording {
        check(config.enableScreenRecording) { "System screen recording must be explicitly enabled" }
        check(!config.recordingRetainMedia || recoveryJournal?.recordings != null) { "Recording retention requires a persistent recording session." }
        if (!leases.add(deviceId)) throw HdcException("Another Maestro recording owns this device")
        val recording = try { Recording(out) } catch (error: Throwable) { leases.remove(deviceId); throw error }
        try {
            recording.start()
            return recording
        } catch (error: Throwable) {
            recording.abortStart(error)
            throw error
        }
    }

    private inner class Recording(private val out: Sink) : ScreenRecording {
        private val resource = recoveryJournal?.allocateRecording()
        private val fileName = resource?.fileName ?: "maestro-recording-${UUID.randomUUID().toString().replace("-", "")}.mp4"
        private val remotePath = resource?.remotePath ?: "/data/local/tmp/$fileName"
        private val diagnosticContext = try { (out as? RecordingDiagnosticContext)?.recordingDiagnosticContext() } catch (_: Throwable) { null }
        private val diagnostics = diagnosticFactory(fileName, resource?.id, diagnosticContext)
        private val files = TempFileHandler()
        private var localDirectory: Path? = null
        private var localPath: Path? = null
        private var ownerPid: Int? = null
        private var startSent = false
        private var startConfirmed = false
        private var stopSent = false
        private var closed = false
        private var failure: Throwable? = null
        private var timer: ScheduledExecutorService? = null
        private var deadlineTask: ScheduledFuture<*>? = null
        private var lastIncomplete: HdcException? = null
        private var validatedSize: Long? = null
        private var exportAttempt = 0
        private var deferDiagnosticTerminal = false
        override lateinit var startedAt: Instant
            private set

        fun start() {
            diagnostics.event(Stage.CREATED, Result.OBSERVED, Fields(persistent = resource != null, retentionRequested = config.recordingRetainMedia))
            val budget = Budget(config.commandTimeout)
            val bundle = diagnostics.step(Stage.SERVICE) { command(listOf("bm", "dump", "-n", BUNDLE), budget) }
            if (!bundle.contains(ABILITY)) throw UnsupportedOperationException("Huawei system screen recorder is not available")
            if (processes(budget).isNotEmpty() || captureInstances(budget).isNotEmpty()) {
                throw HdcException("A screen recorder process or capture session already exists; leave it under its owner's control")
            }
            if (query(budget) != null) throw HdcException("The generated recording name already exists")
            localDirectory = files.createTempDirectory("maestro-harmonyos-recording-").toPath()
            localPath = localDirectory!!.resolve(fileName)
            resource?.starting()
            startSent = true
            diagnostics.step(Stage.START, success = Result.ACKNOWLEDGED) { aa(listOf("--ps", "CustomizedFileName", fileName), budget) }
            startConfirmed = true
            while (true) {
                val pids = processes(budget)
                val captures = captureInstances(budget)
                if (pids.size > 1 || captures.size > 1) throw HdcException("Recording ownership is ambiguous")
                val pid = pids.singleOrNull()
                if (pid != null && captures == setOf(pid)) {
                    ownerPid = pid
                    resource?.started(pid)
                    startedAt = Instant.now()
                    diagnostics.event(Stage.READY, Result.OBSERVED, Fields(pids = setOf(pid)))
                    break
                }
                pause(budget)
            }
            val executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "maestro-harmonyos-recording-limit").apply { isDaemon = true }
            }
            timer = executor
            deadlineTask = executor.schedule({ expire() }, config.recordingMaxDuration.toMillis(), TimeUnit.MILLISECONDS)
        }

        private fun expire() {
            synchronized(this) {
                if (closed) return
                val limit = HdcException("Recording reached its ${config.recordingMaxDuration.toSeconds()} second limit")
                val expiryBegan = diagnostics.mark()
                var diagnosticFailure: Throwable = limit
                deferDiagnosticTerminal = true
                diagnostics.event(Stage.LIMIT_REACHED, Result.OBSERVED)
                try {
                    close()
                    failure = limit
                    noteResourceFailure(limit)
                } catch (error: Throwable) {
                    diagnosticFailure = error
                    error.addSuppressed(limit)
                    noteResourceFailure(error)
                } finally {
                    deferDiagnosticTerminal = false
                    diagnostics.finish(Stage.CLOSE, diagnosticFailure, expiryBegan)
                }
            }
        }

        @Synchronized
        override fun close() {
            if (closed) {
                failure?.let { throw it }
                return
            }
            closed = true
            val closeBegan = diagnostics.mark()
            diagnostics.event(Stage.CLOSE, Result.BEGIN)
            deadlineTask?.cancel(false)
            timer?.shutdown()
            timer = null
            val interrupted = Thread.interrupted()
            var primary: Throwable? = null
            try {
                finish(Budget(config.recordingStopTimeout))
            } catch (error: Throwable) {
                primary = error
                if (error is HdcTimeoutException) lastIncomplete?.let(error::addSuppressed)
                stopAfterFailure(error)
            } finally {
                primary = cleanup(primary)
                leases.remove(deviceId)
                if (interrupted || primary is InterruptedException) Thread.currentThread().interrupt()
            }
            failure = primary
            primary?.let(::addRecoveryHint)
            primary?.let(::noteResourceFailure)
            if (!deferDiagnosticTerminal) diagnostics.finish(Stage.CLOSE, primary, closeBegan)
            primary?.let { throw it }
        }

        fun abortStart(original: Throwable) {
            val abortBegan = diagnostics.mark()
            diagnostics.event(Stage.ABORT, Result.BEGIN)
            val interrupted = Thread.interrupted()
            closed = true
            deadlineTask?.cancel(false)
            timer?.shutdown()
            try {
                if (startConfirmed) {
                    val budget = Budget(config.recordingStopTimeout)
                    val pids = processes(budget)
                    val captures = captureInstances(budget)
                    val pid = pids.singleOrNull()
                    if (pid != null && captures == setOf(pid)) {
                        ownerPid = pid
                        resource?.started(pid)
                        finish(budget)
                    } else {
                        original.addSuppressed(HdcException("Could not confirm ownership after recording start; check the device for $fileName"))
                    }
                } else if (startSent) {
                    original.addSuppressed(HdcException("Recording start was not acknowledged; no stop toggle was sent. Check the device for $fileName"))
                }
            } catch (error: Throwable) {
                if (error !== original) original.addSuppressed(error)
                stopAfterFailure(original)
            } finally {
                cleanup(original)
                if (startSent) addRecoveryHint(original)
                noteResourceFailure(original)
                diagnostics.finish(Stage.ABORT, original, abortBegan)
                leases.remove(deviceId)
                if (interrupted || original is InterruptedException) Thread.currentThread().interrupt()
            }
        }

        private fun finish(budget: Budget) {
            // A readable MP4 can be published while capture is still active.
            stopIfOwned(budget)
            while (captureInstances(budget).isNotEmpty()) pause(budget)
            resource?.ended()
            diagnostics.event(Stage.CAPTURE_ENDED, Result.OBSERVED)
            var uri = query(budget)
            var complete = uri?.let { exportIfComplete(it, budget) } ?: false
            if (!complete) {
                while (!complete) {
                    pause(budget)
                    uri = query(budget)
                    complete = uri?.let { exportIfComplete(it, budget) } ?: false
                }
            }
            resource?.media(checkNotNull(uri))
            if (resource != null) {
                diagnostics.step(Stage.ARCHIVE, Fields(attempt = exportAttempt)) { resource.archive(checkNotNull(localPath), budget.remaining()) }
                diagnostics.archived(exportAttempt, resource::latestArchive)
            }
            copyToSink(budget)
            if (config.recordingRetainMedia) {
                diagnostics.event(Stage.MEDIA_RETAINED, Result.OBSERVED)
                return
            }
            val ownedUri = checkNotNull(uri)
            diagnostics.step(Stage.DELETE, success = Result.ACKNOWLEDGED) {
                val deletion = command(listOf("mediatool", "delete", ownedUri), budget)
                if (deletion.trim() != "[SUCCESS] delete success.") {
                    throw HdcException("The recording was exported but mediatool did not confirm deletion: $deletion")
                }
            }
            if (query(budget) != null) throw HdcException("The recording was exported but its media asset was not deleted: $ownedUri")
            resource?.removed()
            diagnostics.event(Stage.MEDIA_REMOVED, Result.OBSERVED)
        }

        private fun stopIfOwned(budget: Budget) {
            val pid = ownerPid ?: throw HdcException("This session has no confirmed recording process")
            val pids = processes(budget)
            val captures = captureInstances(budget)
            if ((pids.isNotEmpty() && pids != setOf(pid)) || (captures.isNotEmpty() && captures != setOf(pid))) {
                throw HdcException("The recording process or capture session changed; refusing to toggle another recording")
            }
            if (pid in pids && captures == setOf(pid)) {
                // Set this before sending: a lost reply cannot cause a second toggle.
                check(!stopSent) { "A recording stop request has already been sent" }
                resource?.stopping()
                stopSent = true
                diagnostics.step(Stage.STOP, success = Result.ACKNOWLEDGED) { aa(emptyList(), budget) }
            } else {
                diagnostics.event(Stage.STOP, Result.SKIPPED)
            }
        }

        private fun stopAfterFailure(original: Throwable) {
            if (stopSent || ownerPid == null || validatedSize != null) return
            val interrupted = Thread.interrupted()
            try {
                val budget = Budget(Duration.ofSeconds(2))
                query(budget) // Reject malformed or ambiguous asset responses before a toggle.
                stopIfOwned(budget)
            } catch (error: Throwable) {
                if (error !== original) original.addSuppressed(error)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun exportIfComplete(uri: String, budget: Budget): Boolean {
            val attempt = ++exportAttempt
            return diagnostics.step(Stage.EXPORT, Fields(attempt = attempt)) {
                exportAttempt(uri, budget, attempt).also { complete ->
                    if (!complete) diagnostics.event(Stage.EXPORT, Result.INCOMPLETE, Fields(attempt = attempt))
                }
            }
        }

        private fun exportAttempt(uri: String, budget: Budget, attempt: Int): Boolean {
            val path = checkNotNull(localPath)
            diagnostics.step(Stage.EXPORT_SCRATCH, Fields(attempt = attempt)) { command(listOf("rm", "-f", remotePath), budget) }
            val output = try {
                diagnostics.step(Stage.EXPORT_MEDIA, Fields(attempt = attempt)) { command(listOf("mediatool", "recv", uri, remotePath), budget) }
            } catch (error: HarmonyOSCommandException) {
                if (error.remoteExitCode == 0 && error.stderr.isEmpty() &&
                    error.stdout.trim().lines() == listOf("Table Name: Photos", "[FAIL] open source media file failed.")
                ) {
                    lastIncomplete = error
                    return false
                }
                throw error
            }
            if (output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList() != listOf("Table Name: Photos", remotePath)) {
                throw HdcException("mediatool did not confirm the requested recording export: $output")
            }
            val size = diagnostics.step(Stage.STAT, Fields(attempt = attempt)) {
                val sizeOutput = command(listOf("stat", "-c", "%s", remotePath), budget).trim()
                sizeOutput.toLongOrNull() ?: throw HdcException("Invalid recording size returned by stat: $sizeOutput")
            }
            diagnostics.event(Stage.STAT, Result.OBSERVED, Fields(attempt = attempt, expectedBytes = size))
            if (size > config.recordingMaxBytes || size < 0) throw HdcException("Recording exceeds the configured ${config.recordingMaxBytes} byte limit")
            if (size == 0L) return false
            Files.deleteIfExists(path)
            diagnostics.step(Stage.RECEIVE, Fields(attempt = attempt, expectedBytes = size)) { receiveFile(remotePath, path, budget.remaining()) }
            val received = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.size(path) else null
            diagnostics.event(Stage.RECEIVE, Result.OBSERVED, Fields(attempt = attempt, expectedBytes = size, receivedBytes = received))
            val transferFailure = if (received != size) HdcException("Recording transfer did not produce the expected file size") else null
            val remoteAfter = try {
                diagnostics.step(Stage.STAT_AFTER_RECEIVE, Fields(attempt = attempt, expectedBytes = size)) {
                    val afterOutput = command(listOf("stat", "-c", "%s", remotePath), budget).trim()
                    afterOutput.toLongOrNull()?.takeIf { it >= 0 }
                        ?: throw HdcException("Invalid recording size returned by post-transfer stat: $afterOutput")
                }
            } catch (error: Throwable) {
                // A failed follow-up observation must not replace an already known short transfer.
                if (transferFailure != null) {
                    transferFailure.addSuppressed(error)
                    throw transferFailure
                }
                throw error
            }
            diagnostics.event(Stage.STAT_AFTER_RECEIVE, Result.OBSERVED,
                Fields(attempt = attempt, expectedBytes = size, remoteAfterBytes = remoteAfter))
            transferFailure?.let { throw it }
            if (remoteAfter != size) throw HdcException("Recording export changed size during transfer")
            return try {
                diagnostics.step(Stage.VALIDATE, Fields(attempt = attempt, receivedBytes = size)) { Mp4RecordingValidator.validate(path, config.recordingMaxBytes) }
                validatedSize = size
                true
            } catch (error: InvalidMp4RecordingException) {
                lastIncomplete = error
                diagnostics.event(Stage.VALIDATE, Result.INVALID, Fields(attempt = attempt))
                // A confirmed capture can end with an invalid container. Preserve its
                // bytes before the next export or scratch cleanup, without accepting it.
                if (resource != null) {
                    try {
                        resource.media(uri)
                        diagnostics.step(Stage.ARCHIVE, Fields(attempt = attempt, receivedBytes = size)) { resource.archive(path, budget.remaining()) }
                        diagnostics.archived(attempt, resource::latestArchive)
                    } catch (archiveError: Throwable) {
                        archiveError.addSuppressed(error)
                        throw archiveError
                    }
                }
                false
            }
        }

        private fun addRecoveryHint(error: Throwable) {
            error.addSuppressed(HdcException("Recording may remain on the device. Recover only this session's file with: mediatool query $fileName -u"))
        }

        private fun noteResourceFailure(error: Throwable) {
            try {
                if (resource != null) diagnostics.step(Stage.RESOURCE_FAILURE) { resource.failed(error) }
            } catch (journalError: Throwable) {
                if (journalError !== error) error.addSuppressed(journalError)
            }
        }

        private fun copyToSink(budget: Budget) {
            val expected = checkNotNull(validatedSize)
            diagnostics.step(Stage.COPY, Fields(expectedBytes = expected)) { copyToSink(budget, expected) }
        }

        private fun copyToSink(budget: Budget, expected: Long) {
            var copied = 0L
            Files.newInputStream(checkNotNull(localPath), LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    budget.remaining()
                    val count = input.read(bytes)
                    if (count < 0) break
                    copied += count
                    if (copied > expected || copied > config.recordingMaxBytes) throw HdcException("Recording changed after validation")
                    val buffer = Buffer().write(bytes, 0, count)
                    out.write(buffer, count.toLong())
                }
            }
            if (copied != expected || Files.size(checkNotNull(localPath)) != expected) throw HdcException("Recording changed after validation")
            diagnostics.event(Stage.COPY, Result.OBSERVED, Fields(expectedBytes = expected, copiedBytes = copied))
            diagnostics.step(Stage.FLUSH) { out.flush() }
        }

        private fun query(budget: Budget): String? {
            val began = diagnostics.mark()
            diagnostics.queryStarted()
            return try { queryUri(budget).also { diagnostics.query(it, began) } } catch (error: Throwable) {
                diagnostics.event(Stage.QUERY, Result.FAILED, since = began, error = error)
                throw error
            }
        }

        private fun queryUri(budget: Budget): String? {
            val output = try {
                command(listOf("mediatool", "query", fileName, "-u"), budget)
            } catch (error: HarmonyOSCommandException) {
                // mediatool uses 255 for an exact no-match result on the supported device.
                if (error.remoteExitCode == 255 && error.stdout.trim() == "find 0 result" && error.stderr.isEmpty()) {
                    return null
                }
                throw error
            }
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines == listOf("find 0 result")) return null
            if (lines.size != 3 || lines[0] != "find 1 result" || lines[1] != "uri") {
                throw HdcException("Unexpected result while querying the recording's unique name: $output")
            }
            val value = lines[2]
            if (!value.startsWith('"') || !value.endsWith('"')) throw HdcException("Recording URI was not quoted")
            val uri = value.substring(1, value.length - 1)
            val expected = Regex("file://media/Photo/[0-9]+/[A-Za-z0-9_.-]+/${Regex.escape(fileName)}")
            if (!expected.matches(uri)) throw HdcException("Media query returned an unexpected recording URI")
            return uri
        }

        private fun aa(extra: List<String>, budget: Budget) {
            val output = command(listOf("aa", "start", "-b", BUNDLE, "-a", ABILITY) + extra, budget)
            if (output.lineSequence().none { it.trim() == "start ability successfully." }) {
                throw HdcException("System recorder ability did not confirm success: $output")
            }
        }

        private fun cleanup(original: Throwable?): Throwable? {
            var result = original
            var cleanupErrors = 0
            fun record(error: Throwable) {
                cleanupErrors++
                if (result == null) result = error else if (result !== error) result!!.addSuppressed(error)
            }
            if (localPath != null) {
                if (Thread.currentThread().isInterrupted) {
                    diagnostics.event(Stage.CLEANUP_REMOTE, Result.SKIPPED)
                    record(HdcException("Recording scratch cleanup interrupted; temporary file may remain at $remotePath"))
                } else {
                    try {
                        diagnostics.step(Stage.CLEANUP_REMOTE) { device.execute(listOf("rm", "-f", remotePath), Duration.ofSeconds(2)) }
                    } catch (error: Throwable) {
                        record(error)
                    }
                }
            }
            for (path in listOfNotNull(localPath, localDirectory)) {
                try { diagnostics.step(Stage.CLEANUP_LOCAL) { Files.deleteIfExists(path) } } catch (error: Exception) { record(error) }
            }
            try { diagnostics.step(Stage.CLEANUP_TEMP) { files.close() } } catch (error: Exception) { record(error) }
            if (result == null && !config.recordingRetainMedia) {
                try {
                    if (resource != null) diagnostics.step(Stage.CLEANUP_RESOURCE) { resource.cleaned() }
                } catch (error: Exception) { record(error) }
            } else if (resource != null) {
                diagnostics.event(Stage.CLEANUP_RESOURCE, Result.SKIPPED)
            }
            diagnostics.event(Stage.CLEANUP, if (cleanupErrors == 0) Result.RETURNED else Result.FAILED,
                Fields(retentionRequested = config.recordingRetainMedia, priorFailure = original != null, cleanupErrors = cleanupErrors))
            return result
        }

        private fun processes(budget: Budget): Set<Int> = observeStatus(Stage.PROCESSES) { this@HarmonyOSSystemScreenRecorder.processes(budget) }

        private fun captureInstances(budget: Budget): Set<Int> = observeStatus(Stage.CAPTURES) { this@HarmonyOSSystemScreenRecorder.captureInstances(budget) }

        private fun observeStatus(stage: Stage, read: () -> Set<Int>): Set<Int> = try {
            read().also { diagnostics.state(stage, it) }
        } catch (error: Throwable) {
            diagnostics.event(stage, Result.FAILED, error = error)
            throw error
        }
    }

    private fun command(arguments: List<String>, budget: Budget): String = device.execute(arguments, budget.remaining())

    private fun processes(budget: Budget): Set<Int> {
        val output = command(listOf("ps", "-ef"), budget)
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val header = lines.firstOrNull()?.split(Regex("\\s+"))
        if (header != listOf("UID", "PID", "PPID", "C", "STIME", "TTY", "TIME", "CMD")) {
            throw HdcException("Cannot recognize the device process listing; recording ownership cannot be checked")
        }
        return lines.drop(1).mapNotNull { line ->
            val columns = line.split(Regex("\\s+"), limit = 8)
            if (columns.size != 8) throw HdcException("Invalid device process row")
            if (columns[7] == BUNDLE || columns[7].startsWith("$BUNDLE:")) {
                columns[1].toIntOrNull()?.takeIf { it > 0 } ?: throw HdcException("Invalid system recorder PID")
            } else null
        }.toSet()
    }

    private fun captureInstances(budget: Budget): Set<Int> {
        val output = command(listOf("hidumper", "-s", "3002", "-a", "-h"), budget)
        val marker = "--ScreenCaptureServer--:"
        if (!output.contains("PlayerDistributedService") || output.split(marker).size != 2) {
            throw HdcException("Cannot recognize system capture status; recording ownership cannot be checked")
        }
        val tail = output.substringAfter(marker)
        val nextSection = Regex("(?m)^-{2,}[A-Za-z]").find(tail)
            ?: throw HdcException("System capture status is incomplete")
        val entries = tail.substring(0, nextSection.range.first)
        val pattern = Regex("-----#[0-9]+: pid = ([0-9]+)-----")
        if (pattern.replace(entries, "").isNotBlank()) throw HdcException("Unexpected system capture instance format")
        return pattern.findAll(entries).map { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: throw HdcException("Invalid capture instance PID")
        }.toSet()
    }

    private fun pause(budget: Budget) {
        val nanos = minOf(config.pollInterval, budget.remaining()).toNanos()
        try { Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt()) } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }

    private inner class Budget(private val duration: Duration) {
        private val deadline = System.nanoTime() + duration.toNanos()
        fun remaining(): Duration {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Screen recording interrupted")
            val left = deadline - System.nanoTime()
            if (left <= 0) throw HdcTimeoutException(duration)
            return minOf(config.commandTimeout, Duration.ofNanos(left))
        }
    }

    private companion object {
        const val BUNDLE = "com.huawei.hmos.screenrecorder"
        const val ABILITY = "$BUNDLE.ServiceExtAbility"
        val leases = ConcurrentHashMap.newKeySet<String>()
    }
}
