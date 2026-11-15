package maestro.harmonyos.driver

import java.time.Duration

data class HarmonyOSDriverConfig(
    val abilityName: String? = null,
    val moduleName: String? = null,
    val commandTimeout: Duration = Duration.ofSeconds(15),
    val settleTimeout: Duration = Duration.ofSeconds(5),
    val pollInterval: Duration = Duration.ofMillis(100),
    val enableScreenRecording: Boolean = false,
    val recordingMaxDuration: Duration = Duration.ofMinutes(3),
    val recordingStopTimeout: Duration = Duration.ofSeconds(30),
    val recordingMaxBytes: Long = 512L * 1024 * 1024,
    val recordingRetainMedia: Boolean = false,
    val layoutRpcPort: Int? = null,
    val layoutRpcDevice: String? = null,
) {
    init {
        require((layoutRpcPort == null) == (layoutRpcDevice == null)) { "Set both layout RPC port and target device" }
        layoutRpcPort?.let { require(it in 1024..65535) { "Layout RPC port must be 1024..65535" } }
        for (timeout in listOf(commandTimeout, settleTimeout, pollInterval)) {
            require(timeout >= Duration.ofMillis(1) && timeout <= Duration.ofMinutes(5)) {
                "HarmonyOS timeouts must be between 1 ms and 5 minutes"
            }
        }
        require(commandTimeout >= Duration.ofMillis(3)) { "Command timeout must allow capture, transfer and cleanup" }
        require(recordingMaxDuration in Duration.ofSeconds(1)..Duration.ofMinutes(10)) {
            "Recording duration must be between 1 second and 10 minutes"
        }
        require(recordingStopTimeout in Duration.ofMillis(100)..Duration.ofMinutes(2)) {
            "Recording stop timeout must be between 100 ms and 2 minutes"
        }
        require(recordingMaxBytes in 1024..(1024L * 1024 * 1024)) { "Recording size limit must be between 1 KiB and 1 GiB" }
        require(!recordingRetainMedia || enableScreenRecording) { "Media retention requires explicitly enabled recording." }
        abilityName?.let { requireIdentifier(it, "Ability name") }
        moduleName?.let { requireIdentifier(it, "Module name") }
    }
}

internal fun requireIdentifier(value: String, name: String) {
    require(value.isNotBlank() && !value.startsWith('-') && value.none { it.isWhitespace() || it == '\u0000' }) {
        "$name must be non-empty and cannot contain whitespace, NUL, or start with '-'"
    }
}
