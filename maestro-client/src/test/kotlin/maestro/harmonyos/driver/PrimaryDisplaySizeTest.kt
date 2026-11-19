package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PrimaryDisplaySizeTest {
    @Test
    fun `reads the primary physical mode and applies its rotation only`() {
        val dump = """
            [SCREEN SESSION]
            DisplayId: 1
            activeModes<id, W, H, RS>: 4, 1920, 1080, 60
            Rotation: 0
            [SCREEN SESSION]
            DisplayId: 0
            activeModes<id, W, H, RS>: 512, 1260, 2720, 60
            Rotation: 1
            [SCREEN INFO]
            VirtualWidth: 387
            VirtualHeight: 836
            Rotation: 0
        """.trimIndent()
        assertThat(primaryDisplaySize(dump)).isEqualTo(2720 to 1260)
        assertThat(primaryDisplaySize(dump.replace("DisplayId: 0", "DisplayId: 2"))).isNull()
        assertThat(primaryDisplaySize(dump.replace("Rotation: 1", "Rotation: 4"))).isNull()
    }
}
