package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import maestro.Bounds
import maestro.Filters
import maestro.Maestro
import maestro.TreeNode
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.cli.session.HarmonyOsRecoverySupport
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.hdc.HdcClient
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.FileAccessScope
import okio.Buffer
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.IdentityHashMap
import java.util.regex.Pattern
import javax.imageio.ImageIO

internal class HarmonyOsRecorderBackend(private val hdc: HdcClient = HdcClient()) : RecorderBackend {
    override fun devices(): List<String> = hdc.listDevices()

    override fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit) {
        val selected = HarmonyOsSessionSupport.selectDevice(config.deviceId, hdc)
        Files.createDirectories(config.artifactsDir)
        HarmonyOsSessionSupport.withSession(
            selected, hdc, HarmonyOSDriverConfig(abilityName = config.abilityName, moduleName = config.moduleName),
            ownerId = config.sessionId ?: UUID.randomUUID().toString(),
        ) { session -> block(HarmonyOsRecorder(session.maestro, config)) }
    }

    override fun noteUncertainCleanup(sessionId: String) {
        HarmonyOsRecoverySupport.noteUncertainCleanup(sessionId, "The bridge could not confirm cleanup after an interrupted or failed device operation.")
    }

    override fun recover(deviceId: String, sessionId: String): Map<String, Any?> {
        val outcome = HarmonyOsRecoverySupport.recover(deviceId, sessionId, hdc)
        val errors = if (outcome.complete) emptyList() else (outcome.report["issues"] as? List<*>)?.map { raw ->
            val issue = raw as Map<*, *>
            AgentError("environment", issue["code"] as String, issue["message"] as String).toMap()
        }.orEmpty().ifEmpty { listOf(AgentError("environment", "PRECONDITION_UNKNOWN", "Resource recovery remains unconfirmed.").toMap()) }
        return mapOf(
            "deviceId" to deviceId, "sessionId" to sessionId,
            "state" to if (outcome.complete) "complete" else "unknown",
            "cleanup" to mapOf("state" to if (outcome.complete) "complete" else "unknown", "errors" to errors),
            "handoffReady" to outcome.complete, "reportPath" to outcome.reportPath.toString(), "reportSha256" to outcome.sha256,
            "operations" to (outcome.report["operations"] as Number).toInt(),
            "pendingOperations" to (outcome.report["pendingOperations"] as Number).toInt(),
            "temporaryFiles" to (outcome.report["temporaryFiles"] as Number).toInt(),
        )
    }
}

internal class HarmonyOsRecorder(
    private val maestro: Maestro,
    private val config: RecorderConfig,
    private val snapshotTimeoutMs: Long = 20_000,
    private val snapshotPollMs: Long = 100,
    private val agentLookupTimeoutMs: Long = 5000,
) : AgentRecorderConnection {
    init {
        require(snapshotTimeoutMs > 0 && snapshotPollMs > 0) { "Snapshot timing must be positive" }
    }
    private val mapper = jacksonObjectMapper()
    private data class Snapshot(val id: String, val root: TreeNode, val comparison: TreeNode,
        val width: Int, val height: Int, val nodes: Map<String, TreeNode>, val excludedBounds: List<Bounds>)
    private data class ScopedHierarchy(val comparison: TreeNode, val excludedIds: Set<String>, val excludedBounds: List<Bounds>)
    private var current: Snapshot? = null

    override fun snapshot(): Any = runBlocking {
        current = null
        try {
            withTimeout(snapshotTimeoutMs) { captureSnapshot() }
        } catch (error: TimeoutCancellationException) {
            current = null
            throw RecorderError("UNSTABLE_PAGE", "The page did not settle and capture within the ${snapshotTimeoutMs} ms budget. Refresh after it stops changing.")
                .also { it.initCause(error) }
        }
    }

    private suspend fun captureSnapshot(): Any {
        val (before, scoped) = stableHierarchy()
        val buffer = Buffer()
        maestro.takeScreenshot(buffer, compressed = false)
        val bytes = buffer.readByteArray()
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw RecorderError("INVALID_SCREENSHOT", "The device did not return a readable image.")
        val after = maestro.viewHierarchy().root
        if (scoped.comparison != scope(after).comparison) throw RecorderError("UNSTABLE_PAGE", "The page changed while capturing. Refresh before selecting a control.")
        val deviceInfo = maestro.cachedDeviceInfo
        if (image.width != deviceInfo.widthPixels || image.height != deviceInfo.heightPixels) {
            throw RecorderError("ORIENTATION_CHANGED", "The screen dimensions changed. Disconnect and reconnect before recording more actions.")
        }
        val nodes = linkedMapOf<String, TreeNode>()
        val output = mutableListOf<Map<String, Any?>>()
        fun visit(node: TreeNode, id: String, parentId: String?) {
            if (id in scoped.excludedIds) return
            val element = node.toUiElementOrNull()
            var parent = parentId
            if (element != null && element.bounds.width > 0 && element.bounds.height > 0) {
                nodes[id] = node
                val bounds = element.bounds
                output += mapOf(
                    "nodeId" to id, "parentId" to parentId,
                    "text" to node.attributes["text"].orEmpty(), "hintText" to node.attributes["hintText"].orEmpty(),
                    "resourceId" to node.attributes["resource-id"].orEmpty(), "className" to node.attributes["class"].orEmpty(),
                    "bounds" to mapOf("left" to bounds.x, "top" to bounds.y, "right" to bounds.x + bounds.width, "bottom" to bounds.y + bounds.height),
                    "enabled" to node.enabled, "clickable" to node.clickable,
                )
                parent = id
            }
            node.children.forEachIndexed { index, child -> visit(child, "$id.$index", parent) }
        }
        visit(before, "0", null)
        val id = UUID.randomUUID().toString()
        val screenshot = Base64.getEncoder().encodeToString(bytes)
        currentCoroutineContext().ensureActive()
        current = Snapshot(id, before, scoped.comparison, image.width, image.height, nodes, scoped.excludedBounds)
        return mapOf(
            "snapshotId" to id, "width" to image.width, "height" to image.height,
            "screenshot" to mapOf("mimeType" to "image/png", "base64" to screenshot),
            "nodes" to output,
        )
    }

    private suspend fun stableHierarchy(): Pair<TreeNode, ScopedHierarchy> {
        var previous: TreeNode? = null
        while (true) {
            val root = maestro.viewHierarchy().root
            val scoped = scope(root)
            if (previous == scoped.comparison) return root to scoped
            previous = scoped.comparison
            delay(snapshotPollMs)
        }
    }

    override fun action(parameters: JsonNode): Any = runBlocking {
        // Build and validate all user parameters before a device query or a side effect.
        val action = prepare(parameters)
        val command = try {
            action()
        } catch (error: Exception) {
            current = null
            throw error
        }
        current = null
        val directory = config.artifactsDir.resolve("action-${UUID.randomUUID()}")
        Files.createDirectory(directory)
        val commands = YamlCommandReader.readSingleCommand(directory.resolve("action.yaml"), config.appId, command)
        val orchestra = Orchestra(
            maestro, artifactsDir = directory, scope = FileAccessScope.under(directory),
            lookupTimeoutMs = 5000, captureFullArtifacts = true,
        )
        val result = orchestra.runFlow(commands)
        if (!result.success) throw RecorderError("ACTION_FAILED", "The action failed; no YAML step was recorded. See $directory")
        mapOf("yaml" to command.lineSequence().mapIndexed { index, line -> if (index == 0) "- $line" else "  $line" }.joinToString("\n"),
            "artifactsDir" to directory.toString())
    }

    /** One registered attempt executes once. A caller retries by registering a new attempt. */
    override fun executeAgentAction(parameters: JsonNode, attempt: AgentAttempt): Unit = runBlocking {
        val steps = linkedMapOf<Int, String>()
        val active = IdentityHashMap<MaestroCommand, ArrayDeque<String>>()
        val failed = linkedSetOf<String>()
        var executionFailure: Throwable? = null
        var reportingFailure: Throwable? = null
        var bundle: Path? = null
        var startingStep: String? = null
        val executionJob = currentCoroutineContext().job
        val cancellationBinding = attempt.bindCancellation {
            executionJob.cancel(CancellationException("Agent cancellation requested."))
        }
        try {
            attempt.checkCancelled()
            val action = prepare(parameters)
            val command = action()
            attempt.checkCancelled()
            current = null
            val yaml = command.lineSequence().mapIndexed { index, line -> if (index == 0) "- $line" else "  $line" }.joinToString("\n")
            val flow = attempt.directory.resolve("flow.yaml")
            Files.writeString(flow, "appId: ${literal(config.appId)}\n---\n$yaml\n")
            attempt.setFlow(flow)
            val commands = YamlCommandReader.readSingleCommand(flow, config.appId, command)
            val artifacts = Files.createDirectory(attempt.directory.resolve("execution"))
            bundle = artifacts
            val listener = object : OrchestraListener {
                override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) {
                    val id = startingStep ?: return
                    startingStep = null
                    steps[sequenceNumber] = id
                    active.getOrPut(cmd) { ArrayDeque() }.addLast(id)
                }

                override fun onCommandFinished(cmd: MaestroCommand, outcome: CommandOutcome, startedAt: Long, finishedAt: Long) {
                    val id = active[cmd]?.removeLastOrNull() ?: return
                    try {
                        when (outcome) {
                            CommandOutcome.Completed -> attempt.finishStep(id, "passed")
                            CommandOutcome.Skipped, CommandOutcome.Warned -> attempt.finishStep(id, "skipped")
                            is CommandOutcome.Failed -> {
                                val cancelledAt = attempt.cancellationRequestedAtMillis
                                val observedFailure = if (cancelledAt != null && finishedAt >= cancelledAt)
                                    cancellationFailure(outcome.error)
                                else outcome.error
                                executionFailure = observedFailure
                                failed += id
                                val error = HarmonyOsAgentProtocol.error(observedFailure)
                                attempt.finishStep(id, if (error.kind == "cancelled") "cancelled" else "failed", error)
                            }
                        }
                    } catch (error: Throwable) {
                        reportingFailure = error
                        throw error
                    }
                }
            }
            val result = Orchestra(maestro, artifactsDir = artifacts, scope = FileAccessScope.under(attempt.directory),
                lookupTimeoutMs = agentLookupTimeoutMs, captureFullArtifacts = true, listeners = listOf(listener),
                onCommandStart = { _, cmd ->
                    reportingFailure?.let { throw it }
                    val name = cmd.asCommand()?.javaClass?.simpleName ?: "UnknownCommand"
                    val mutating = name !in setOf("ApplyConfigurationCommand", "DefineVariablesCommand", "AssertConditionCommand",
                        "AssertVisibleCommand", "AssertNotVisibleCommand", "TakeScreenshotCommand", "WaitForAnimationToEndCommand")
                    // This callback propagates errors. Listener exceptions are isolated by Orchestra,
                    // so cancellation and the durable dispatch record must happen here.
                    startingStep = attempt.startStep(if (cmd.sourceInfo == null) null else "flow.yaml", 0, name, mutating)
                },
                onCommandFailed = { _, _, error ->
                    if (executionFailure == null && !attempt.isCancellationRequested) executionFailure = error
                    Orchestra.ErrorResolution.FAIL
                },
            ).runFlow(commands)
            if (attempt.isCancellationRequested) {
                attempt.setCleanup("unknown")
                if (executionFailure == null) attempt.checkCancelled()
            }
            try { indexAgentEvidence(attempt, artifacts, steps, failed) }
            catch (captureError: Throwable) {
                (failed.map { it as String? }.ifEmpty { listOf(null) }).forEach { id ->
                    attempt.recordUnavailable(id, "commands", "capture_failed", "failure", HarmonyOsAgentProtocol.error(captureError))
                }
            }
            if (result.success && reportingFailure == null) attempt.finishSuccess()
            else attempt.finishFailure(executionFailure ?: result.debugOutput.exception
                ?: reportingFailure
                ?: AgentProtocolException(AgentError("transport", "EXECUTOR_LOST", "Orchestra returned no successful outcome or failure cause.")))
        } catch (error: Throwable) {
            val primaryError = executionFailure ?: if (attempt.isCancellationRequested)
                cancellationFailure(error) else error
            val classified = HarmonyOsAgentProtocol.error(primaryError)
            active.values.flatMap { it.toList() }.forEach { id ->
                failed += id
                attempt.finishStep(id, if (classified.kind == "cancelled") "cancelled" else "failed", classified)
            }
            bundle?.let { artifacts ->
                try { indexAgentEvidence(attempt, artifacts, steps, failed) }
                catch (captureError: Throwable) {
                    failed.forEach { id -> attempt.recordUnavailable(id, "commands", "capture_failed", "failure", HarmonyOsAgentProtocol.error(captureError)) }
                }
            }
            if (attempt.isCancellationRequested && attempt.hasStarted) attempt.setCleanup("unknown")
            attempt.finishFailure(primaryError)
        } finally {
            cancellationBinding.close()
            current = null
        }
    }

    private fun cancellationFailure(observed: Throwable) = AgentProtocolException(AgentError(
        "cancelled", "CANCELLED", "Agent cancellation requested.",
        mapOf("observedError" to HarmonyOsAgentProtocol.error(observed).toMap()),
    )).apply { initCause(observed) }

    /** Reads the executor's emitted artifact references; never repeats a device operation. */
    private fun indexAgentEvidence(attempt: AgentAttempt, bundle: Path, steps: Map<Int, String>, failed: Set<String>) {
        val captured = mutableMapOf<String, MutableSet<String>>()
        val commands = bundle.resolve("commands.json")
        if (Files.isRegularFile(commands)) {
            mapper.readTree(commands.toFile()).forEach { item ->
                val metadata = item.path("metadata")
                val id = steps[metadata.path("sequenceNumber").asInt()] ?: return@forEach
                val finishedAt = metadata.path("timestamp").asLong() + metadata.path("duration").asLong()
                metadata.path("artifacts").forEach artifact@ { artifact ->
                    val kind = evidenceKind(artifact.path("type").asText()) ?: return@artifact
                    val path = bundle.resolve(artifact.path("path").asText()).normalize()
                    val capturedAt = if (Files.isRegularFile(path)) Files.getLastModifiedTime(path).toInstant() else Instant.now()
                    val phase = if (kind == "screenshot" && capturedAt.toEpochMilli() < finishedAt) "before"
                        else if (id in failed) "failure" else "after"
                    attempt.recordFile(id, kind, path, phase, capturedAt)
                    if (Files.isRegularFile(path)) captured.getOrPut(id) { mutableSetOf() } += kind
                }
            }
        }
        val manifest = bundle.resolve("manifest.json")
        if (Files.isRegularFile(manifest)) {
            attempt.recordManifest(manifest)
            mapper.readTree(manifest.toFile()).path("entries").filter { it.path("count").isMissingNode || it.path("count").isNull }
                .forEach { artifact ->
                    val kind = evidenceKind(artifact.path("kind").asText()) ?: return@forEach
                    if (kind !in setOf("log", "commands", "video")) return@forEach
                    val path = bundle.resolve(artifact.path("relativePath").asText()).normalize()
                    val time = if (Files.isRegularFile(path)) Files.getLastModifiedTime(path).toInstant() else Instant.now()
                    attempt.recordFile(null, kind, path, "after", time)
                    failed.forEach { id ->
                        attempt.recordFile(id, kind, path, "failure", time)
                        if (Files.isRegularFile(path)) captured.getOrPut(id) { mutableSetOf() } += kind
                    }
                }
        }
        failed.forEach { id -> listOf("screenshot", "hierarchy", "log", "commands").filter { it !in captured[id].orEmpty() }
            .forEach { attempt.recordUnavailable(id, it, "missing", "failure") } }
    }

    private fun evidenceKind(kind: String): String? = when (kind) {
        "SCREENSHOT", "TAKE_SCREENSHOT", "SCREENSHOT_DIFF" -> "screenshot"
        "SCREEN_HIERARCHY" -> "hierarchy"
        "COMMAND_METADATA" -> "commands"
        "MAESTRO_LOG", "DEVICE_LOG", "CRASH_REPORT", "ANR_REPORT" -> "log"
        "SCREEN_RECORDING", "START_SCREEN_RECORDING" -> "video"
        else -> null
    }

    private fun prepare(p: JsonNode): suspend () -> String {
        return when (val type = p.requiredText("type")) {
            "launchApp", "back" -> {
                p.onlyFields("type")
                fixed(type)
            }
            "inputText" -> {
                p.onlyFields("type", "text")
                val text = p.requiredText("text", allowEmpty = true)
                fixed("inputText: ${literal(text)}")
            }
            "swipe" -> {
                p.onlyFields("type", "direction", "duration")
                val direction = p.requiredText("direction")
                if (direction !in setOf("UP", "DOWN", "LEFT", "RIGHT")) throw RecorderError("INVALID_PARAMS", "Unknown swipe direction.")
                val duration = if (p.has("duration")) p.integer("duration", 1..10000) else 600
                fixed("swipe:\n  direction: $direction\n  duration: $duration")
            }
            "takeScreenshot" -> {
                p.onlyFields("type", "name")
                val name = p.requiredText("name")
                if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) {
                    throw RecorderError("INVALID_PARAMS", "Screenshot name must use 1-80 letters, digits, underscore or hyphen and start with a letter or digit.")
                }
                fixed("takeScreenshot: ${literal(name)}")
            }
            "tapOn", "assertVisible" -> prepareSelection(type, p)
            else -> throw RecorderError("INVALID_PARAMS", "Unsupported action: $type")
        }
    }

    private fun prepareSelection(type: String, p: JsonNode): suspend () -> String {
        if (type == "assertVisible" && p.has("text")) {
            p.onlyFields("type", "text")
            val text = p.requiredText("text")
            return fixed("assertVisible:\n  text: ${literal(Pattern.quote(text))}")
        }
        val snapshotId = p.requiredText("snapshotId")
        if (p.has("point")) {
            if (type != "tapOn") throw RecorderError("INVALID_PARAMS", "Coordinates are only supported for tapOn.")
            p.onlyFields("type", "snapshotId", "point")
            val point = p.get("point")
            point.onlyFields("x", "y")
            val x = point.integer("x", 0..Int.MAX_VALUE)
            val y = point.integer("y", 0..Int.MAX_VALUE)
            val snapshot = requireSnapshot(snapshotId)
            if (x >= snapshot.width || y >= snapshot.height) throw RecorderError("INVALID_PARAMS", "Point is outside the screenshot.")
            if (snapshot.excludedBounds.any { it.contains(x, y) }) {
                throw RecorderError("INVALID_PARAMS", "The system status bar is outside this app recording. Select a point inside the app.")
            }
            return {
                checkFresh(snapshot)
                "tapOn:\n  point: ${literal("$x,$y")}\n  retryTapIfNoChange: false"
            }
        }
        p.onlyFields("type", "snapshotId", "nodeId")
        val nodeId = p.requiredText("nodeId")
        val snapshot = requireSnapshot(snapshotId)
        val node = snapshot.nodes[nodeId] ?: throw RecorderError("INVALID_PARAMS", "Unknown node in this snapshot.")
        if (type == "tapOn" && node.enabled == false) throw RecorderError("INVALID_PARAMS", "The selected node is disabled.")
        val selector = selector(node, snapshot.root.aggregate())
        return {
            val refreshed = checkFresh(snapshot)
            // Use the same Maestro filter as the generated YAML, including hint matching.
            val matches = matching(selector.first, selector.second, refreshed.aggregate())
            if (matches.size != 1 || matches.single() != node) {
                throw RecorderError("STALE_SNAPSHOT", "The selected control changed. Refresh and select it again.")
            }
            "$type:\n  ${selector.first}: ${literal(selector.second)}" +
                if (type == "tapOn") "\n  retryTapIfNoChange: false" else ""
        }
    }

    private fun requireSnapshot(id: String): Snapshot = current?.takeIf { it.id == id }
        ?: throw RecorderError("STALE_SNAPSHOT", "Refresh the screenshot before selecting a control.")

    private suspend fun checkFresh(snapshot: Snapshot): TreeNode {
        requireSnapshot(snapshot.id)
        val root = maestro.viewHierarchy().root
        if (scope(root).comparison != snapshot.comparison) throw RecorderError("STALE_SNAPSHOT", "The page changed. Refresh before performing this action.")
        return root
    }

    /**
     * UITest merges the app with the system status bar. Its clock/network text can
     * change during capture. Exclude only a recognized, non-overlapping status
     * bar's descendants, retaining the window's identity, geometry and state.
     * Dialogs, keyboards, overlapping windows and unknown system content remain.
     */
    private fun scope(root: TreeNode): ScopedHierarchy {
        val appBounds = root.aggregate().filter { it.attributes["bundleName"] == config.appId }
            .mapNotNull { it.toUiElementOrNull()?.bounds }
            .filter { it.width > 0 && it.height > 0 }
        val excludedIds = linkedSetOf<String>()
        val excludedBounds = mutableListOf<Bounds>()
        fun overlaps(a: Bounds, b: Bounds): Boolean =
            a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
        fun visit(node: TreeNode, id: String): TreeNode {
            val bounds = node.toUiElementOrNull()?.bounds
            if (appBounds.isNotEmpty() && bounds != null && bounds.width > 0 && bounds.height > 0 &&
                node.attributes["bundleName"] == "com.ohos.sceneboard" &&
                node.attributes["class"] == "WindowScene" && appBounds.none { overlaps(it, bounds) } &&
                node.aggregate().any { it.attributes["resource-id"] == "StatusBarBox" }
            ) {
                excludedIds += id
                excludedBounds += bounds
                return node.copy(children = emptyList())
            }
            return node.copy(children = node.children.mapIndexed { index, child -> visit(child, "$id.$index") })
        }
        return ScopedHierarchy(visit(root, "0"), excludedIds, excludedBounds)
    }

    private fun selector(node: TreeNode, nodes: List<TreeNode>): Pair<String, String> {
        val candidates = listOf("id" to node.attributes["resource-id"], "text" to node.attributes["text"], "text" to node.attributes["hintText"])
        for ((field, value) in candidates) {
            if (value.isNullOrBlank()) continue
            val pattern = Pattern.quote(value)
            val matches = matching(field, pattern, nodes)
            if (matches.size == 1 && matches.single() == node) return field to pattern
        }
        throw RecorderError("AMBIGUOUS_SELECTOR", "No unique ID or text identifies this control. Select another node or explicitly choose a coordinate tap.")
    }

    private fun matching(field: String, pattern: String, nodes: List<TreeNode>): List<TreeNode> =
        (if (field == "id") Filters.idMatches(Regex(pattern)) else Filters.textMatches(Regex(pattern)))(nodes)

    private fun fixed(command: String): suspend () -> String = { command }

    /** Check the pinned Env.evaluateScripts escape rule without evaluating any user JavaScript. */
    private fun literal(value: String): String {
        val escaped = value.replace("\${", "\\\${")
        val unescaped = escaped.replace(Regex("""\\\$\{([^$]*)}""")) { it.value.substringAfter('\\') }
        if (unescaped != value) {
            throw RecorderError("INVALID_PARAMS", "This text contains an incomplete or nested expression that Maestro cannot preserve literally.")
        }
        return mapper.writeValueAsString(escaped)
    }
}

private fun JsonNode.integer(name: String, range: IntRange): Int {
    val value = get(name)
    if (value == null || !value.isIntegralNumber || !value.canConvertToInt() || value.intValue() !in range) {
        throw RecorderError("INVALID_PARAMS", "$name must be an integer between ${range.first} and ${range.last}.")
    }
    return value.intValue()
}
