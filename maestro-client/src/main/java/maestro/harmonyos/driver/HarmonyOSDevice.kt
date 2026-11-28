package maestro.harmonyos.driver

import maestro.TreeNode
import maestro.Point
import maestro.ScreenRecording
import okio.Sink
import java.nio.file.Path
import java.time.Duration

/** Device boundary; tests replace the transport while keeping the real Maestro driver. */
interface HarmonyOSDevice {
    fun connect()
    fun disconnect() {}
    fun isAvailable(): Boolean
    fun hasAppFocus(appId: String, timeout: Duration): Boolean = true
    fun keyboardVisible(timeout: Duration): Boolean =
        throw UnsupportedOperationException("Keyboard visibility is not available for this backend.")
    fun previewFrame(timeout: Duration): ByteArray = screenshot(timeout)
    fun displaySize(timeout: Duration): Pair<Int, Int>? = null
    fun setDisplayRotation(rotation: Int, timeout: Duration): Unit =
        throw UnsupportedOperationException("This HarmonyOS device backend does not support rotation control.")
    fun addMedia(files: List<Path>, timeout: Duration): Unit =
        throw UnsupportedOperationException("This HarmonyOS device backend does not support media import.")
    fun flowFaults(appId: String, startMs: Long, endMs: Long, timeout: Duration): HarmonyOSFlowFaults? = null
    fun hierarchy(timeout: Duration): TreeNode
    fun queryCss(selector: String, timeout: Duration, hierarchyNodes: List<TreeNode> = emptyList()): List<TreeNode> =
        throw UnsupportedOperationException("This HarmonyOS device backend does not support CSS selectors.")
    fun awaitCssTapHit(selector: String, nodeId: String, point: Point, timeout: Duration): Unit =
        throw UnsupportedOperationException("This HarmonyOS device backend cannot verify the ArkWeb CSS tap on '$selector'.")
    fun installHap(hap: HarmonyOSHap, replace: Boolean, timeout: Duration): Unit =
        throw UnsupportedOperationException("This HarmonyOS device backend does not support HAP installation.")
    fun setAppPermission(appId: String, permission: String, allow: Boolean, timeout: Duration): Unit =
        throw UnsupportedOperationException("This HarmonyOS device backend does not support app permissions.")
    fun screenshot(timeout: Duration): ByteArray
    fun execute(arguments: List<String>, timeout: Duration): String
    fun startScreenRecording(out: Sink, config: HarmonyOSDriverConfig): ScreenRecording =
        throw UnsupportedOperationException("This HarmonyOS device backend does not support screen recording")
}
