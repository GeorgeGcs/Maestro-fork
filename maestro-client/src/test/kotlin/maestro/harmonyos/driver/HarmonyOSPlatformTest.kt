package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import maestro.device.DeviceService
import maestro.device.Platform
import maestro.device.locale.DeviceLocale
import maestro.device.locale.LocaleValidationException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HarmonyOSPlatformTest {
    @Test
    fun `platform names accept case variants without pretending to be Android`() {
        assertThat(Platform.fromString("harmonyos")).isEqualTo(Platform.HARMONYOS)
        assertThat(Platform.fromString("HarmonyOS")).isEqualTo(Platform.HARMONYOS)
        assertThat(Platform.fromString("HARMONYOS")).isEqualTo(Platform.HARMONYOS)
        assertThat(Platform.fromString("Android")).isEqualTo(Platform.ANDROID)
        assertThrows(IllegalArgumentException::class.java) { Platform.fromString("harmony") }
    }

    @Test
    fun `HarmonyOS does not reuse another platforms locale catalog`() {
        assertThat(DeviceLocale.all(Platform.HARMONYOS)).isEmpty()
        assertThat(DeviceLocale.allCodes(Platform.HARMONYOS)).isEmpty()
        assertThat(DeviceLocale.find("en", "US", Platform.HARMONYOS)).isNull()
        assertThat(DeviceLocale.isValid("en_US", Platform.HARMONYOS)).isFalse()
        assertThrows(LocaleValidationException::class.java) { DeviceLocale.getDefault(Platform.HARMONYOS) }
    }

    @Test
    fun `managed emulator lookup does not discover an Android device for HarmonyOS`() {
        assertThat(DeviceService.isDeviceConnected("test-device", Platform.HARMONYOS)).isNull()
        assertThat(DeviceService.isDeviceAvailableToLaunch("test-device", Platform.HARMONYOS)).isNull()
    }
}
