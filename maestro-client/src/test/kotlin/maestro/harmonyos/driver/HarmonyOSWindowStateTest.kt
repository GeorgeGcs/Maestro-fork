package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HarmonyOSWindowStateTest {
    private val header = "WindowName DisplayId Pid WinId Type Mode Flag ZOrd Orientation [ x y w h ]"
    private val app = "App With Spaces 0 785 12 1 1 0 102 0 [ 0 0 2800 1840 ]"

    @Test fun `column reordering preserves window ownership`() {
        val reordered = "WindowName WinId Pid DisplayId Type Mode Flag Orientation ZOrd [ x y w h ]\n" +
            "Rotated App 12 785 0 1 1 0 1 102 [ 0 0 1840 2800 ]\nFocus window: 12"
        assertThat(HarmonyOSWindowState(reordered).focusedPid()).isEqualTo("785")
        assertThat(HarmonyOSWindowState("$header\n$app\nFocus window: 12").focusedPid()).isEqualTo("785")
    }

    @Test fun `a retained hidden keyboard is not visible`() {
        val keyboard = "Keyboard 0 1300 22 2105 1 0 -1 0 [ 0 0 2800 1840 ]"
        assertThat(HarmonyOSWindowState("$header\n$app\n$keyboard\nFocus window: 12").keyboardVisible()).isFalse()
        assertThat(HarmonyOSWindowState("$header\n$app\n" + keyboard.replace(" -1 ", " 1900 ") +
            "\nFocus window: 12").keyboardVisible()).isTrue()
    }

    @Test fun `unknown columns and ambiguous focus are explicit format failures`() {
        assertThrows(HarmonyOSWindowFormatException::class.java) { HarmonyOSWindowState("permission denied") }
        assertThrows(HarmonyOSWindowFormatException::class.java) {
            HarmonyOSWindowState("$header\n$app\nFocus window: 12\nFocus window: 20").focusedPid()
        }
        assertThrows(HarmonyOSWindowFormatException::class.java) {
            HarmonyOSWindowState("$header\n$app\nFocus window: 999").focusedPid()
        }
    }
}
