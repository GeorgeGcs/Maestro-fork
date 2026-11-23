package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import maestro.cli.session.HarmonyOsSessionSupport
import maestro.harmonyos.driver.HarmonyOSBundleMetadata
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.harmonyos.hdc.HdcClient
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path

/** Read-only observations; an implementation must never launch, unlock or change application state. */
internal interface AgentEnvironmentProbe {
    fun devices(): List<String>
    fun layoutRpcListener(deviceId: String): Boolean? = null
    fun lockStatus(deviceId: String): HarmonyOsSessionSupport.DeviceLockStatus
    fun execute(deviceId: String, arguments: List<String>): String
}

internal class HdcAgentEnvironmentProbe(private val hdc: HdcClient = HdcClient()) : AgentEnvironmentProbe {
    override fun devices(): List<String> = hdc.listDevices()
    override fun layoutRpcListener(deviceId: String) = hdc.probeUitestLayoutListener(deviceId, Duration.ofSeconds(2))
    override fun lockStatus(deviceId: String) = HarmonyOsSessionSupport.probeDeviceLock(deviceId)
    override fun execute(deviceId: String, arguments: List<String>): String =
        HdcHarmonyOSDevice(deviceId, hdc).execute(arguments, Duration.ofSeconds(5))
}

internal class HarmonyOsAgentCapabilities(
    private val probe: AgentEnvironmentProbe = HdcAgentEnvironmentProbe(),
    private val recordingEnabled: Boolean = System.getenv("MAESTRO_HARMONYOS_RECORDING") == "huawei-system",
    private val hostRecordingSelected: Boolean = System.getenv("MAESTRO_HARMONYOS_RECORDING") == "host-frames",
) {
    private data class Command(val name: String, val implementation: String, val recorder: Boolean = false,
        val screen: Boolean = true, val application: Boolean = false, val recording: Boolean = false)

    fun describe(): Map<String, Any?> = mapOf(
        "methods" to METHODS,
        "commands" to commands.map { command ->
            mapOf("name" to command.name, "implementation" to command.implementation,
                "interfaces" to if (command.recorder) listOf("cli", "recorder") else listOf("cli"),
                "requirements" to if (command.implementation == "unsupported") emptyList<String>() else buildList<String> {
                    add("device_connected"); add("session_owned"); add("uitest_available")
                    if (command.screen) add("screen_unlocked")
                    if (command.application) add("app_installed")
                    if (command.name == "launchApp") add("ability_configured")
                    if (command.name == "setOrientation") add("uitest_rpc_available")
                    if (command.name == "addMedia") add("gallery_import_available")
                    if (command.recording) {
                        add("recording_enabled")
                        if (hostRecordingSelected) add("host_encoder_available") else {
                            add("recording_service_available"); add("recording_exclusive")
                        }
                    }
                },
                "verification" to mapOf("state" to "unverified", "scope" to null, "evidenceRefs" to emptyList<String>()))
        },
    )

    fun preflight(params: JsonNode): Map<String, Any?> {
        params.onlyFields("deviceId", "commands", "appId", "abilityName", "moduleName")
        val deviceId = params.requiredText("deviceId")
        if (!deviceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")) || deviceId.length > 256) {
            throw RecorderError("INVALID_PARAMS", "Invalid explicit device identifier.")
        }
        val requestedNode = params.get("commands")
        if (requestedNode == null || !requestedNode.isArray || requestedNode.size() !in 1..64 ||
            requestedNode.any { !it.isTextual || !it.asText().matches(Regex("[A-Za-z][A-Za-z0-9]*")) }) {
            throw RecorderError("INVALID_PARAMS", "commands must contain 1-64 command names.")
        }
        val requested = requestedNode.map { it.asText() }
        if (requested.distinct().size != requested.size) throw RecorderError("INVALID_PARAMS", "commands cannot contain duplicates.")
        val appId = identifier(params, "appId")
        val ability = identifier(params, "abilityName")
        val module = identifier(params, "moduleName")
        val checks = mutableListOf<Map<String, Any?>>()
        fun check(name: String, status: String, code: String, message: String, required: Boolean = true) {
            checks += mapOf("name" to name, "required" to required, "status" to status, "code" to code, "message" to message)
        }
        val selected = requested.map { name -> commands.find { it.name == name } }
        requested.zip(selected).forEach { (name, command) ->
            if (command == null || command.implementation == "unsupported") {
                check("command.$name", "failed", "UNSUPPORTED_COMMAND", "This implementation does not declare support for $name.")
            } else check("command.$name", "passed", "COMMAND_IMPLEMENTED", "Implemented; device behavior is a separate observation.")
        }
        if (selected.any { it?.application == true } && appId == null) {
            check("application", "failed", "APP_ID_REQUIRED", "An application identifier is required for the requested command.")
        }
        if ("launchApp" in requested && ability == null && appId == null) {
            check("ability", "failed", "ABILITY_REQUIRED", "Provide an application identifier to discover its launch ability.")
        }

        val devices = try { probe.devices() } catch (error: Exception) {
            propagateInterrupt(error)
            check("device", "unknown", "HDC_DISCOVERY_FAILED", "Device discovery did not produce a confirmed result.")
            null
        }
        val connected = devices?.contains(deviceId) == true
        if (devices != null) {
            if (connected) check("device", "passed", "DEVICE_CONNECTED", "The explicitly selected target is connected.")
            else check("device", "failed", "DEVICE_NOT_FOUND", "The explicitly selected target is not connected; no substitute was selected.")
        }
        val lock = if (connected) try { probe.lockStatus(deviceId) } catch (error: Exception) {
            propagateInterrupt(error); HarmonyOsSessionSupport.DeviceLockStatus.UNKNOWN
        } else HarmonyOsSessionSupport.DeviceLockStatus.UNKNOWN
        when (lock) {
            HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE -> check("deviceLock", "passed", "DEVICE_LOCK_AVAILABLE", "The lock was available at observation time; this is not a reservation.")
            HarmonyOsSessionSupport.DeviceLockStatus.BUSY -> check("deviceLock", "failed", "DEVICE_BUSY", "Another local Maestro session owns this target.")
            HarmonyOsSessionSupport.DeviceLockStatus.RECOVERY_REQUIRED -> check("deviceLock", "failed", "DEVICE_RECOVERY_REQUIRED", "A prior session has not confirmed cleanup. Recover that session before device operations.")
            HarmonyOsSessionSupport.DeviceLockStatus.UNKNOWN -> check("deviceLock", "unknown", "PRECONDITION_UNKNOWN", "Device lock availability was not confirmed.")
        }
        // Do not compete with a running session, or issue shell probes for an offline target.
        val mayProbe = connected && lock == HarmonyOsSessionSupport.DeviceLockStatus.AVAILABLE
        fun read(arguments: List<String>): String? = if (!mayProbe) null else try {
            probe.execute(deviceId, arguments).trim()
        } catch (error: Exception) { propagateInterrupt(error); null }

        val uitest = read(listOf("which", "uitest"))
        if (uitest != null && uitest.matches(Regex("/[^\\r\\n]+/uitest"))) {
            check("uitest", "passed", "UITEST_DISCOVERED", "UITest is discoverable; this does not verify every command.")
        } else check("uitest", "unknown", "PRECONDITION_UNKNOWN", "UITest availability was not confirmed by a read-only query.")

        if (requested.any { it in LAYOUT_COMMANDS }) {
            val listening = if (mayProbe) try { probe.layoutRpcListener(deviceId) }
                catch (error: Exception) { propagateInterrupt(error); null } else null
            when (listening) {
                true -> check("layoutRpcListener", "passed", "UITEST_RPC_LISTENER_PRESENT",
                    "An existing UITest listener was observed. The session will verify the forward and RPC response.", required = false)
                false -> check("layoutRpcListener", "failed", "UITEST_RPC_LISTENER_MISSING",
                    "Start a compatible UITest service before using RPC. HDC file layout is available as the fallback.", required = false)
                null -> check("layoutRpcListener", "unknown", "UITEST_RPC_LISTENER_UNKNOWN",
                    "The UITest listener state was not confirmed by a read-only observation.", required = false)
            }
        }

        if ("setOrientation" in requested) check("rotationBackend", "unknown", "PRECONDITION_UNKNOWN",
            "Rotation requires an active UITest RPC endpoint and a rotatable foreground application.")
        if ("addMedia" in requested) {
            val media = read(listOf("which", "mediatool"))
            val available = media?.matches(Regex("/[^\\r\\n]+/mediatool")) == true
            check("galleryTool", if (available) "passed" else "unknown",
                if (available) "GALLERY_TOOL_DISCOVERED" else "PRECONDITION_UNKNOWN",
                "Media tool discovery does not establish Gallery write access.")
        }

        if (selected.any { it?.screen == true && it.implementation != "unsupported" }) {
            check("screenUnlocked", "unknown", "PRECONDITION_UNKNOWN",
                "This implementation has no verified read-only lock-screen probe. Keep the device unlocked and inspect its current screen.")
        }
        if (appId != null) {
            val output = read(listOf("bm", "dump", "-n", appId))
            val app = output?.let { HarmonyOSBundleMetadata.parse(it, appId) }
            val confirmed = app != null
            if (confirmed) check("application", "passed", "APP_DISCOVERED", "The bundle query identifies the requested application.")
            else check("application", "unknown", "PRECONDITION_UNKNOWN", "The application query did not confirm the requested bundle; absence was not inferred.")

            if ("clearState" in requested) {
                when (HarmonyOSBundleMetadata.dataClearSupport(app)) {
                    HarmonyOSBundleMetadata.DataClearSupport.SUPPORTED -> check("clearStateTarget", "passed", "DATA_CLEAR_TARGET_CONFIRMED",
                        "Bundle metadata declares a regular application with clearable user data.")
                    HarmonyOSBundleMetadata.DataClearSupport.UNSUPPORTED -> check("clearStateTarget", "failed", "DATA_CLEAR_TARGET_UNSUPPORTED",
                        "clearState requires a regular application with clearable user data.")
                    HarmonyOSBundleMetadata.DataClearSupport.UNKNOWN -> check("clearStateTarget", "unknown", "PRECONDITION_UNKNOWN",
                        "Bundle metadata did not confirm application type and data-clear support.")
                }
            }

            val modules = app?.get("hapModuleInfos")?.takeIf { it.isArray }?.toList().orEmpty()
            val matchingModules = if (module == null) modules else modules.filter {
                it.path("moduleName").asText() == module || it.path("name").asText() == module
            }
            if (module != null) {
                val state = if (!confirmed || modules.isEmpty()) "unknown" else if (matchingModules.isNotEmpty()) "passed" else "failed"
                check("module", state, when (state) { "passed" -> "MODULE_DISCOVERED"; "failed" -> "MODULE_NOT_FOUND"; else -> "PRECONDITION_UNKNOWN" },
                    "Module matching is limited to the confirmed bundle metadata.")
            }
            if (ability != null) {
                val containers = if (module == null) listOfNotNull(app) + matchingModules else matchingModules
                val abilities = containers.flatMap { node -> listOf("abilityInfos", "abilities").flatMap { key ->
                    node.get(key)?.takeIf { it.isArray }?.toList().orEmpty()
                } }.mapNotNull { node -> node.get("name")?.takeIf { it.isTextual }?.asText() }
                val present = abilities.any { it == ability || it == "$appId.$ability" }
                val state = if (!confirmed || abilities.isEmpty()) "unknown" else if (present) "passed" else "failed"
                check("ability", state, when (state) { "passed" -> "ABILITY_DISCOVERED"; "failed" -> "ABILITY_NOT_FOUND"; else -> "PRECONDITION_UNKNOWN" },
                    "Ability matching is limited to the confirmed bundle and requested module.")
            } else if ("launchApp" in requested) {
                val target = app?.let { HarmonyOSBundleMetadata.launchTarget(it, module) }
                if (target != null) {
                    check("ability", "passed", "ABILITY_AUTO_DISCOVERED", "The bundle declares ${target.abilityName} in ${target.moduleName}.")
                } else if (app != null) {
                    check("ability", "failed", "ABILITY_REQUIRED", "No unique launch ability was confirmed. Pass abilityName explicitly.")
                } else {
                    check("ability", "unknown", "PRECONDITION_UNKNOWN", "Launch ability discovery requires confirmed bundle metadata.")
                }
            }
        }
        if (selected.any { it?.recording == true } && hostRecordingSelected) {
            val encoder = runCatching {
                System.getenv("MAESTRO_HARMONYOS_FFMPEG")?.takeIf(String::isNotBlank)?.let(Path::of)
            }.getOrNull()
            val available = encoder != null && encoder.isAbsolute && Files.isRegularFile(encoder) && Files.isExecutable(encoder)
            check("recordingEnabled", "passed", "RECORDING_ENABLED", "Host recording is configured.")
            check("recordingTool", if (available) "passed" else "failed",
                if (available) "HOST_ENCODER_CONFIGURED" else "HOST_ENCODER_REQUIRED",
                "The host recording backend requires an absolute executable FFmpeg path.")
        } else if (selected.any { it?.recording == true }) {
            check("recordingEnabled", if (recordingEnabled) "passed" else "failed",
                if (recordingEnabled) "RECORDING_ENABLED" else "RECORDING_DISABLED", "The experimental recording backend requires explicit configuration.")
            val media = read(listOf("which", "mediatool"))
            check("recordingTool", if (media?.matches(Regex("/[^\\r\\n]+/mediatool")) == true) "passed" else "unknown",
                if (media?.matches(Regex("/[^\\r\\n]+/mediatool")) == true) "MEDIA_TOOL_DISCOVERED" else "PRECONDITION_UNKNOWN",
                "Tool discovery does not establish recording ownership or successful video decoding.")
            check("recordingOwnership", "unknown", "PRECONDITION_UNKNOWN", "Recording ownership and current recording state must be established by the recording session.")
        }
        val readiness = when {
            checks.any { it["required"] == true && it["status"] == "failed" } -> "blocked"
            checks.any { it["required"] == true && it["status"] == "unknown" } -> "unknown"
            else -> "ready"
        }
        return mapOf("deviceId" to deviceId, "readiness" to readiness, "checks" to checks)
    }

    private fun identifier(params: JsonNode, name: String): String? = params.optionalText(name)?.also {
        if (it.length > 512 || it.startsWith('-') || it.any(Char::isWhitespace) || it.contains("\${")) {
            throw RecorderError("INVALID_PARAMS", "Invalid $name.")
        }
    }

    private fun propagateInterrupt(error: Exception) {
        if (error is InterruptedException || Thread.currentThread().isInterrupted) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Environment query interrupted").also { it.initCause(error) }
        }
    }

    companion object {
        private val LAYOUT_COMMANDS = setOf("tapOn", "longPressOn", "assertVisible", "assertNotVisible",
            "scrollUntilVisible", "extendedWaitUntil", "setOrientation")
        val METHODS = listOf("agent.describe", "agent.preflight", "agent.connect", "agent.session", "agent.snapshot",
            "agent.action", "agent.cancel", "agent.release", "agent.result", "agent.evidence")
        private val commands = listOf(
            Command("launchApp", "supported", recorder = true, application = true),
            Command("stopApp", "supported", screen = false, application = true),
            Command("killApp", "supported", screen = false, application = true),
            Command("tapOn", "supported", recorder = true), Command("longPressOn", "supported"),
            Command("inputText", "supported", recorder = true), Command("eraseText", "supported"),
            Command("back", "supported", recorder = true), Command("swipe", "supported", recorder = true),
            Command("scroll", "supported"), Command("scrollUntilVisible", "supported"),
            Command("pressKey", "supported"), Command("assertVisible", "supported", recorder = true),
            Command("assertNotVisible", "supported"), Command("extendedWaitUntil", "supported"),
            Command("takeScreenshot", "supported", recorder = true, screen = false),
            Command("startRecording", "experimental", recording = true), Command("stopRecording", "experimental", recording = true),
            Command("clearState", "experimental", screen = false, application = true),
            Command("hideKeyboard", "supported", application = true),
            Command("openLink", "supported"), Command("addMedia", "experimental", screen = false),
            Command("setLocation", "unsupported"), Command("setOrientation", "experimental"),
            Command("setPermissions", "unsupported"), Command("installApp", "unsupported"),
        )
    }
}
