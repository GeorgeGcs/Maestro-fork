package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmonyOSFlowFaultsTest {
    @Test
    fun `uses only matching app events within the requested window and separates freezes`() {
        val output = """
            {"name_":"CPP_CRASH","MODULE":"demo.target","time_":90,"SUMMARY":"before"}
            {"name_":"CPP_CRASH","MODULE":"demo.target","time_":140,"SUMMARY":"older crash"}
            {"name_":"APP_FREEZE","PNAME":"demo.target","HAPPEN_TIME":155,"time_":180,"REASON":"main thread"}
            {"name_":"JS_ERROR","PACKAGE_NAME":"demo.target","time_":160,"MSG":"latest crash"}
            {"name_":"JS_ERROR","PACKAGE_NAME":"demo.target.other","time_":165,"MSG":"another app"}
            {"name_":"CPP_CRASH","MODULE":"demo.target","time_":210,"SUMMARY":"after"}
            {"name_":"USER_ACTION","MODULE":"demo.target","time_":170}
        """.trimIndent()
        val report = parseFlowFaults(output, "demo.target", 100, 200)
        assertThat(report.crash).isEqualTo(HarmonyOSFlowFault("JS_ERROR", 160, "latest crash"))
        assertThat(report.freeze).isEqualTo(HarmonyOSFlowFault("APP_FREEZE", 155, "main thread", occurredAt = true))
        assertThat(report.events.map { it.timeMs }).containsExactly(140L, 155L, 160L).inOrder()
    }

    @Test
    fun `keeps bounded inline details for matching events inside the adjusted flow window`() {
        val output = """
            {"name_":"CPP_CRASH","PACKAGE_NAME":"another.app","MODULE":"demo.target","HAPPEN_TIME":160}
            {"name_":"JS_ERROR","PACKAGE_NAME":"demo.target","HAPPEN_TIME":141,"REASON":"uncertain boundary"}
            {"name_":"JS_ERROR","PACKAGE_NAME":"demo.target","HAPPEN_TIME":160,"PID":1234,"REASON":"threw","STACK":"details","LOG_PATH":"/an/untrusted/path"}
        """.trimIndent()
        val report = parseFlowFaults(output, "demo.target", 150, 200, HarmonyOSFaultClock(3, 10))
        assertThat(report.events).containsExactly(
            HarmonyOSFlowFault("JS_ERROR", 160, "threw", mapOf("PID" to "1234", "STACK" to "details"), true),
        )
    }
}
