package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmonyOsRejectedStartPermissionTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val fingerprint = "a".repeat(64)
    private val rejected = "Device command reported a failure\nerror: failed to start ability.\n" +
        "Error Code:10106102  Error Message:The device screen is locked during the application launch, unlock screen failed."

    @Test fun `only the bound locked ability rejection grants an absence check`() {
        val failure = mapOf<String, Any?>("code" to "RECORDING_RESOURCE_CLEANUP_FAILED", "message" to rejected,
            "details" to mapOf("recordingId" to id, "failureFingerprint" to fingerprint))
        val permission = rejectedStartPermission(failure)
        assertThat(permission).isNotNull()
        assertThat(permission!!.recordingId).isEqualTo(id)
        assertThat(permission.failureFingerprint).isEqualTo(fingerprint)
        assertThat(permission.errorCode).isEqualTo(10106102)
        assertThat(rejectedStartPermission(failure + ("code" to "HDC_ERROR"))).isNull()
        assertThat(rejectedStartPermission(failure + ("message" to rejected.replace("10106102", "10106103")))).isNull()
        assertThat(rejectedStartPermission(failure + ("details" to mapOf("recordingId" to id)))).isNull()
        assertThat(rejectedStartPermission(failure + ("details" to mapOf("recordingId" to id,
            "failureFingerprint" to "bad")))).isNull()
    }
}
