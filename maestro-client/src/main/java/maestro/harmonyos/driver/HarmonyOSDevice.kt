package maestro.harmonyos.driver

import maestro.TreeNode
import maestro.ScreenRecording
import okio.Sink
import java.time.Duration

/** Device boundary; tests replace the transport while keeping the real Maestro driver. */
interface HarmonyOSDevice {
    fun connect()
    fun disconnect() {}
    fun isAvailable(): Boolean
    fun hierarchy(timeout: Duration): TreeNode
    fun screenshot(timeout: Duration): ByteArray
    fun execute(arguments: List<String>, timeout: Duration): String
    fun startScreenRecording(out: Sink, config: HarmonyOSDriverConfig): ScreenRecording =
        throw UnsupportedOperationException("This HarmonyOS device backend does not support screen recording")
}
