package maestro.cli.harmonyos

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.CompositeCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.SourceInfo
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.ExecutionContext
import maestro.orchestra.debug.ExecutionEvent
import maestro.orchestra.debug.ExecutionTraceArtifacts
import maestro.orchestra.debug.ExecutionTraceEnd
import maestro.orchestra.debug.RetryExecutionEvent
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID

/** Optional observer of the real CLI executor. It never runs commands itself. */
class HarmonyOsCliAgentReport private constructor(private val attempt: AgentAttempt, private val sourceHashes: Map<String, String>) {
    private data class RegisteredSource(val identity: String, val sha256: String)
    private data class ArchivedSource(val registered: RegisteredSource, val relativePath: String)
    private val registeredSources = sourceHashes.mapValues { (identity, hash) -> RegisteredSource(identity, hash) }
    val directory: Path get() = attempt.directory
    private var failure: Throwable? = null
    private var reportingFailure: Throwable? = null
    private var completed = false
    private var completedFlows = 0
    private var failedFlows = 0
    private var observedFlows = 0
    private var abortedFlows = 0
    private var activeSession: UUID? = null
    private var closedSession: UUID? = null
    internal var interactive: HarmonyOsInteractiveRun? = null

    internal fun checkStop() {
        if (interactive?.shouldStop() == true) attempt.requestCancel()
        attempt.checkCancelled()
    }
    private val shutdownHook = Thread({
        synchronized(this) {
            if (!completed) {
                attempt.setCleanup("unknown")
                attempt.finishWithError(AgentError(
                    "transport", "EXECUTOR_LOST", "CLI exited before execution and cleanup were confirmed.",
                ), "unknown")
            }
        }
    }, "maestro-harmonyos-agent-result")

    init { Runtime.getRuntime().addShutdownHook(shutdownHook) }

    fun selectedDevice(deviceId: String) = attempt.setDeviceId(deviceId)

    @Synchronized internal fun sessionStarted(deviceId: String): UUID {
        check(!completed) { "The CLI report is already complete." }
        selectedDevice(deviceId)
        closedSession = null
        return UUID.randomUUID().also { activeSession = it }
    }

    /** Called only after newSession returns, including durable HarmonyOS lease teardown. */
    @Synchronized internal fun sessionClosedNormally(session: UUID) {
        check(!completed && activeSession == session) { "The session does not belong to the current CLI execution." }
        closedSession = session
        interactive?.markReleased()
    }

    fun flow(flowFile: File, artifactsDir: Path, commands: List<MaestroCommand> = emptyList()): FlowObserver = FlowObserver(flowFile, artifactsDir, commands)

    fun failed(error: Throwable) { failure = error }

    @Synchronized
    fun finish(success: Boolean, error: Throwable? = null) {
        if (completed) return
        // A normal return must belong to this report's latest session. A generic
        // complete state or a previous session's return is not teardown evidence.
        val recoveryError = recoveryError(error ?: failure)
        if (error == null && recoveryError == null && activeSession != null && closedSession == activeSession) {
            attempt.confirmSessionCleanup()
        } else {
            attempt.setCleanup(if (error == null && recoveryError == null && activeSession == null) "complete" else "unknown",
                (error?.let { listOf(HarmonyOsAgentProtocol.error(it)) }.orEmpty() + listOfNotNull(recoveryError)).distinct())
        }
        // Only a top-level recovery blocker delegates the execution outcome to
        // prior Flow observations. A final error with a suppressed blocker wins
        // over failures from earlier, successfully recovered retry attempts.
        val recoveryBlocked = error != null && HarmonyOsAgentProtocol.error(error).code == "DEVICE_RECOVERY_REQUIRED"
        val actualError = if (recoveryBlocked) failure ?: reportingFailure ?: error
            else if (reportingFailure != null) failure ?: error ?: reportingFailure else error ?: failure
        if (recoveryBlocked && completedFlows > 0 && completedFlows == observedFlows &&
            failedFlows == 0 && abortedFlows == 0 && reportingFailure == null) attempt.finishSuccess()
        else if (success && actualError == null) attempt.finishSuccess()
        else if (success && reportingFailure == null && error == null) attempt.finishSuccess()
        else if (actualError != null && reportingFailure != null && actualError !== reportingFailure) {
            val classified = HarmonyOsAgentProtocol.error(actualError)
            attempt.finishWithError(classified.copy(details = classified.details.orEmpty() +
                ("evidenceError" to HarmonyOsAgentProtocol.error(reportingFailure!!).toMap())))
        }
        else if (actualError != null) attempt.finishFailure(actualError)
        else attempt.finishWithError(AgentError("transport", "EXECUTOR_LOST",
            "The CLI did not expose a confirmed terminal Flow outcome."), "unknown")
        completed = true
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
    }

    /** A body error can carry the session's independent cleanup blocker as suppressed. */
    private fun recoveryError(error: Throwable?): AgentError? {
        if (error == null) return null
        val pending = ArrayDeque<Throwable>()
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun enqueue(candidate: Throwable) {
            if (seen.size < 64 && seen.add(candidate)) pending.addLast(candidate)
        }
        enqueue(error)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val classified = HarmonyOsAgentProtocol.error(current)
            if (classified.code == "DEVICE_RECOVERY_REQUIRED") return classified
            current.cause?.let(::enqueue)
            current.suppressed.take(64).forEach(::enqueue)
        }
        return null
    }

    fun junit(path: Path) {
        if (!Files.isRegularFile(path)) return
        val archived = directory.resolve("junit.xml")
        if (path.toAbsolutePath().normalize() != archived) Files.copy(path, archived, StandardCopyOption.REPLACE_EXISTING)
        attempt.setJUnit(archived)
    }

    inner class FlowObserver(private val flowFile: File, private val artifactsDir: Path, commands: List<MaestroCommand>) : OrchestraListener {
        private val active = IdentityHashMap<MaestroCommand, ArrayDeque<String>>()
        private val steps = linkedMapOf<Int, String>()
        private val stepSources = linkedMapOf<String, Map<String, Any?>>()
        internal val retryTrace = HarmonyOsRetryTrace(attempt.runId, attempt.attemptId,
            flowFile.absolutePath, sourceHashes[flowFile.toPath().toAbsolutePath().normalize().toString()])
        private val failedSteps = linkedSetOf<String>()
        private var indexed = false
        private val sourceIndices = collectCommands(commands).mapNotNull { it.sourceInfo }.groupBy { it.path }.values
            .flatMap { infos -> infos.distinctBy { it.startOffset }.sortedBy { it.startOffset }.mapIndexed { index, info -> sourceKey(info) to index } }.toMap()
        private val archivedSources = mutableMapOf<String, ArchivedSource>()

        init {
            observedFlows++
            collectCommands(commands).mapNotNull { it.sourceInfo }.forEach { info ->
                val path = info.path ?: return@forEach
                if (path !in archivedSources) {
                    val registered = registeredSources[Path.of(path).toAbsolutePath().normalize().toString()]
                    val hash = sha256(info.source.toByteArray(Charsets.UTF_8))
                    if (registered == null || registered.sha256 != hash) throw AgentProtocolException(AgentError("invalid_input", "ATTEMPT_CONFLICT",
                        "A Flow source changed after registration or was not present in the registered input."))
                    val relative = "sources/$hash.yaml"
                    val target = directory.resolve(relative)
                    Files.createDirectories(target.parent)
                    if (!Files.exists(target)) Files.writeString(target, info.source)
                    archivedSources[path] = ArchivedSource(registered, relative)
                }
            }
        }

        private fun observe(block: () -> Unit) {
            try { block() } catch (error: Throwable) { reportingFailure = error; throw error }
        }

        /** Called by Orchestra's strict callback before device dispatch, outside listener isolation. */
        fun starting(cmd: MaestroCommand, commandIndex: Int = 0) {
            reportingFailure?.let { throw it }
            checkStop()
            val commandName = cmd.asCommand()?.javaClass?.simpleName ?: "UnknownCommand"
            val mutating = commandName !in READ_ONLY_COMMANDS
            val info = cmd.sourceInfo
            val source = info?.path?.let { archivedSources[it] }
            val sourcePath = source?.relativePath
            val index = info?.let { sourceIndices[sourceKey(it)] } ?: commandIndex
            val id = attempt.startStep(sourcePath, index, commandName, mutating)
            active.getOrPut(cmd) { ArrayDeque() }.addLast(id)
            stepSources[id] = mapOf("sourcePath" to sourcePath,
                "sourceIdentity" to source?.registered?.identity,
                "sourceSha256" to source?.registered?.sha256,
                "commandIndex" to index, "commandName" to commandName, "sourceOffset" to info?.startOffset,
                "sourceOrigin" to if (info == null) "generated" else "yaml",
                "commandOptional" to (cmd.asCommand()?.optional == true),
                "selectorOptional" to (cmd.elementSelector()?.optional == true))
        }

        private inline fun observeTrace(block: () -> Unit) {
            try { block() } catch (error: Throwable) { retryTrace.failed(error) }
        }

        override fun onExecutionTraceReady(artifacts: ExecutionTraceArtifacts) = retryTrace.ready(artifacts)

        override fun onCommandExecutionStarted(cmd: MaestroCommand, context: ExecutionContext, depth: Int, event: ExecutionEvent) {
            try { onCommandStart(cmd, context.executionSequence, depth) }
            catch (error: Throwable) { retryTrace.failed(error); throw error }
            observeTrace {
                val id = checkNotNull(steps[context.executionSequence])
                retryTrace.stepStarted(id, context, checkNotNull(stepSources[id]), event)
            }
        }

        override fun onCommandExecutionFinished(cmd: MaestroCommand, context: ExecutionContext, outcome: CommandOutcome,
            startedAt: Long, finishedAt: Long, event: ExecutionEvent) {
            // Capture the existing identity before the legacy callback removes its active entry.
            val id = steps[context.executionSequence]
            try { onCommandFinished(cmd, outcome, startedAt, finishedAt) }
            finally {
                observeTrace { retryTrace.stepFinished(checkNotNull(id), context, outcome.javaClass.simpleName,
                    (outcome as? CommandOutcome.Failed)?.error, event) }
            }
        }

        override fun onCommandExecutionInterrupted(cmd: MaestroCommand, context: ExecutionContext, error: Throwable,
            startedAt: Long, finishedAt: Long, event: ExecutionEvent) = observeTrace {
            retryTrace.stepFinished(checkNotNull(steps[context.executionSequence]), context, "Interrupted", error, event)
        }

        override fun onRetryExecution(event: RetryExecutionEvent) = retryTrace.retry(event)

        override fun onExecutionTraceEnd(end: ExecutionTraceEnd) = retryTrace.ended(end)

        override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) = observe {
            steps[sequenceNumber] = checkNotNull(active[cmd]?.lastOrNull()) { "Command was not durably registered before dispatch." }
        }

        override fun onCommandFinished(cmd: MaestroCommand, outcome: CommandOutcome, startedAt: Long, finishedAt: Long) = observe {
            val id = active[cmd]?.removeLastOrNull() ?: return@observe
            when (outcome) {
                CommandOutcome.Completed -> attempt.finishStep(id, "passed")
                CommandOutcome.Skipped, CommandOutcome.Warned -> attempt.finishStep(id, "skipped")
                is CommandOutcome.Failed -> {
                    failure = outcome.error
                    failedSteps += id
                    val error = HarmonyOsAgentProtocol.error(outcome.error)
                    attempt.finishStep(id, failureStatus(error), error)
                }
            }
        }

        fun finished(result: Orchestra.FlowResult): Orchestra.FlowResult {
            // Seal first: the v1 evidence hashes below must describe the final bytes.
            retryTrace.seal(result.success)
            val sealedResult = retryTrace.artifactManifest?.let { result.copy(artifactManifest = it) } ?: result
            indexFinished(sealedResult)
            return sealedResult
        }

        private fun indexFinished(result: Orchestra.FlowResult) = observe {
            completedFlows++
            if (!result.success) failedFlows++
            val captured = mutableMapOf<String, MutableSet<String>>()
            result.debugOutput.executedSteps.forEach { step ->
                val id = steps[step.sequenceNumber] ?: return@forEach
                step.artifacts.forEach { artifact ->
                    kind(artifact.type)?.let { kind ->
                        val path = confinedArtifact(artifact.path)
                        if (Files.isRegularFile(path)) {
                            val capturedAt = Files.getLastModifiedTime(path).toInstant()
                            val phase = when {
                                id in failedSteps && step.timestamp?.let { capturedAt.toEpochMilli() >= it + (step.duration ?: 0) } == true -> "failure"
                                artifact.type == ArtifactKind.SCREENSHOT -> "before"
                                else -> "after"
                            }
                            attempt.recordFile(id, kind, path, phase, capturedAt)
                            captured.getOrPut(id) { mutableSetOf() } += kind
                        }
                    }
                }
            }
            result.artifactManifest.entries.filter { it.count == null }.forEach { artifact ->
                val kind = kind(artifact.kind) ?: return@forEach
                if (kind != "log" && kind != "commands" && kind != "video") return@forEach
                val path = confinedArtifact(artifact.relativePath)
                if (Files.isRegularFile(path)) {
                    attempt.recordFile(null, kind, path, "after")
                    failedSteps.forEach { id ->
                        attempt.recordFile(id, kind, path, "failure")
                        captured.getOrPut(id) { mutableSetOf() } += kind
                    }
                }
            }
            failedSteps.forEach { id ->
                listOf("screenshot", "hierarchy", "log", "commands").filter { it !in captured[id].orEmpty() }.forEach {
                    attempt.recordUnavailable(id, it, "missing", "failure")
                }
            }
            val manifest = artifactsDir.resolve("manifest.json")
            if (Files.isRegularFile(manifest)) attempt.recordManifest(manifest)
            if (!result.success && failure == null) failure = result.debugOutput.exception
            indexed = true
        }

        fun aborted(error: Throwable) {
            retryTrace.seal(null, error)
            abortedFlows++
            if (error !== reportingFailure || failure == null) failure = error
            observe {
                val classified = HarmonyOsAgentProtocol.error(error)
                active.values.flatMap { it.toList() }.forEach { id ->
                    attempt.finishStep(id, failureStatus(classified), classified)
                    failedSteps += id
                }
                active.clear()
                if (indexed) return@observe
                val captured = mutableMapOf<String, MutableSet<String>>()
                val mapper = jacksonObjectMapper()
                val commandsFile = artifactsDir.resolve("commands.json")
                if (Files.isRegularFile(commandsFile)) {
                    mapper.readTree(commandsFile.toFile()).forEach { command ->
                        val metadata = command.path("metadata")
                        val id = steps[metadata.path("sequenceNumber").asInt(-1)] ?: return@forEach
                        metadata.path("artifacts").forEach artifactLoop@ { artifact ->
                            val type = runCatching { ArtifactKind.valueOf(artifact.path("type").asText()) }.getOrNull() ?: return@artifactLoop
                            val kind = kind(type) ?: return@artifactLoop
                            val path = confinedArtifact(artifact.path("path").asText())
                            if (Files.isRegularFile(path)) {
                                val time = Files.getLastModifiedTime(path).toInstant()
                                val phase = if (id in failedSteps && metadata.hasNonNull("timestamp") && time.toEpochMilli() >= metadata.path("timestamp").asLong() + metadata.path("duration").asLong()) "failure" else if (type == ArtifactKind.SCREENSHOT) "before" else "after"
                                attempt.recordFile(id, kind, path, phase, time)
                                captured.getOrPut(id) { mutableSetOf() } += kind
                            }
                        }
                    }
                }
                val manifest = artifactsDir.resolve("manifest.json")
                if (Files.isRegularFile(manifest)) {
                    mapper.readTree(manifest.toFile()).path("entries").forEach artifactLoop@ { artifact ->
                        val type = runCatching { ArtifactKind.valueOf(artifact.path("kind").asText()) }.getOrNull() ?: return@artifactLoop
                        val kind = kind(type) ?: return@artifactLoop
                        if (kind !in setOf("log", "commands", "video") || artifact.hasNonNull("count")) return@artifactLoop
                        val path = confinedArtifact(artifact.path("relativePath").asText())
                        if (Files.isRegularFile(path)) failedSteps.forEach { id ->
                            attempt.recordFile(id, kind, path, "failure", Files.getLastModifiedTime(path).toInstant())
                            captured.getOrPut(id) { mutableSetOf() } += kind
                        }
                    }
                    attempt.recordManifest(manifest)
                }
                failedSteps.forEach { id ->
                    listOf("screenshot", "hierarchy", "log", "commands").filter { it !in captured[id].orEmpty() }.forEach {
                        attempt.recordUnavailable(id, it, "missing", "failure")
                    }
                }
                indexed = true
            }
        }

        private fun confinedArtifact(relative: String): Path {
            require(!relative.contains('\\') && !relative.startsWith('/') && !relative.contains(':')) { "Invalid artifact path" }
            val base = artifactsDir.toRealPath()
            val path = base.resolve(relative).normalize()
            require(path.startsWith(base)) { "Artifact escapes its Flow bundle" }
            if (Files.exists(path)) require(path.toRealPath().startsWith(base)) { "Artifact link escapes its Flow bundle" }
            return path
        }

        private fun failureStatus(error: AgentError): String = when {
            error.kind == "cancelled" -> "cancelled"
            error.kind in setOf("transport", "timeout") && attempt.effects != "none" -> "unknown"
            else -> "failed"
        }
    }

    companion object {
        private val READ_ONLY_COMMANDS = setOf("ApplyConfigurationCommand", "DefineVariablesCommand", "AssertConditionCommand",
            "AssertVisibleCommand", "AssertNotVisibleCommand", "AssertScreenshotCommand", "TakeScreenshotCommand", "WaitForAnimationToEndCommand")

        fun create(root: Path, runId: String?, attemptId: String?, attemptNo: Int, deviceId: String?, flows: Set<File>, configuration: Map<String, Any?>): HarmonyOsCliAgentReport {
            val mapper = jacksonObjectMapper()
            val sourceHashes = linkedMapOf<String, String>()
            val sources = flows.sortedBy { it.absolutePath }.map { source ->
                val files = if (source.isDirectory) source.walkTopDown().filter { it.isFile && it.extension in setOf("yaml", "yml", "js") }.toList() else listOf(source)
                mapOf("path" to source.absolutePath, "files" to files.sortedBy { it.absolutePath }.map { file ->
                    val bytes = if (file.isFile) file.readBytes() else null
                    val resolved = if (file.isFile && file.extension in setOf("yaml", "yml")) runCatching { YamlCommandReader.readCommands(file.toPath()) }.getOrNull() else null
                    val dependencies = collectCommands(resolved.orEmpty()).mapNotNull { it.sourceInfo }.distinctBy { it.path }.map { info ->
                        val hash = sha256(info.source.toByteArray(Charsets.UTF_8))
                        info.path?.let { sourceHashes[Path.of(it).toAbsolutePath().normalize().toString()] = hash }
                        mapOf("path" to info.path, "sha256" to hash, "content" to info.source)
                    }
                    val hash = bytes?.let { sha256(it) }
                    if (hash != null) sourceHashes[file.toPath().toAbsolutePath().normalize().toString()] = hash
                    mapOf("path" to file.absolutePath, "sha256" to hash, "content" to bytes?.toString(Charsets.UTF_8),
                        "dependencies" to dependencies, "resolvedCommands" to resolved)
                })
            }
            val attempt = HarmonyOsAgentAttemptStore(root).register(
                runId ?: UUID.randomUUID().toString(), attemptId ?: UUID.randomUUID().toString(), attemptNo,
                null, deviceId ?: "unselected", "flow", mapper.valueToTree(mapOf("sources" to sources, "configuration" to configuration)),
            )
            val report = HarmonyOsCliAgentReport(attempt, sourceHashes)
            try {
                if (flows.size == 1 && flows.first().isFile) attempt.setFlow(flows.first().toPath())
            } catch (error: Throwable) {
                report.finish(false, error)
                throw error
            }
            return report
        }

        private fun sourceKey(info: SourceInfo) = "${info.path}:${info.startOffset}"
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun collectCommands(commands: List<MaestroCommand>): List<MaestroCommand> = commands.flatMap { command ->
            val actual = command.asCommand()
            val config = (actual as? ApplyConfigurationCommand)?.config ?: (actual as? CompositeCommand)?.config()
            listOf(command) + collectCommands((actual as? CompositeCommand)?.subCommands().orEmpty()) +
                collectCommands(config?.onFlowStart?.commands.orEmpty()) + collectCommands(config?.onFlowComplete?.commands.orEmpty())
        }

        private fun kind(kind: ArtifactKind): String? = when (kind) {
            ArtifactKind.SCREENSHOT, ArtifactKind.TAKE_SCREENSHOT, ArtifactKind.SCREENSHOT_DIFF -> "screenshot"
            ArtifactKind.SCREEN_HIERARCHY -> "hierarchy"
            ArtifactKind.COMMAND_METADATA -> "commands"
            ArtifactKind.MAESTRO_LOG, ArtifactKind.DEVICE_LOG, ArtifactKind.CRASH_REPORT, ArtifactKind.ANR_REPORT -> "log"
            ArtifactKind.SCREEN_RECORDING, ArtifactKind.START_SCREEN_RECORDING -> "video"
            else -> null
        }
    }
}
