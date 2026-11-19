package maestro.harmonyos.layout

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.driver.HarmonyOSArkWebTarget
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import java.time.Duration

class ArkWebLoopbackTest {
    @Test
    fun `an established page connection reads new snapshots without repeating forward discovery`() {
        val json = ObjectMapper()
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody(
                """[{"type":"page","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/test"}]"""
            ))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val id = json.readTree(text).path("id").asLong()
                    val tree = """{"attributes":{"text":"snapshot $id"},"children":[]}"""
                    webSocket.send(json.writeValueAsString(mapOf(
                        "id" to id, "result" to mapOf("result" to mapOf("value" to tree))
                    )))
                }
            }))
            ArkWebLoopback(server.port).use { browser ->
                var verifications = 0
                fun snapshot() = browser.snapshot(listOf(0, 0, 100, 100), Duration.ofSeconds(2)) {
                    verifications++
                    true
                }
                assertThat(snapshot()?.attributes?.get("text")).isEqualTo("snapshot 1")
                assertThat(snapshot()?.attributes?.get("text")).isEqualTo("snapshot 2")
                assertThat(verifications).isEqualTo(1)
                assertThat(server.requestCount).isEqualTo(2)
            }
        }
    }

    @Test
    fun `an explicit page matches only its URL and rechecks the device forward`() {
        val json = ObjectMapper()
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("""[
                {"type":"page","url":"https://example.test/one","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/one"},
                {"type":"page","url":"https://example.test/two","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/two"}
            ]"""))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = json.readTree(text)
                    val expression = request.path("params").path("expression").asText()
                    val value = if (expression.startsWith("if (window.location.href === \"https://example.test/two\" &&") &&
                        expression.contains("meta[name=maestro-webview-id]')?.content === \"probe-secondary\"")) {
                        """{"attributes":{"text":"page two"},"children":[]}"""
                    } else "null"
                    webSocket.send(json.writeValueAsString(mapOf(
                        "id" to request.path("id").asLong(), "result" to mapOf("result" to mapOf("value" to value)),
                    )))
                }
            }))
            ArkWebLoopback(server.port, HarmonyOSArkWebTarget("probe-secondary", "https://example.test/two")).use { browser ->
                val bounds = listOf(0, 0, 100, 100)
                val timeout = Duration.ofSeconds(2)
                assertThat(browser.snapshot(bounds, timeout) { true }?.attributes?.get("text")).isEqualTo("page two")
                assertThat(server.takeRequest().path).isEqualTo("/json/list")
                assertThat(server.takeRequest().path).isEqualTo("/devtools/page/two")
                assertThat(browser.snapshot(bounds, timeout) { false }).isNull()
                assertThat(server.requestCount).isEqualTo(2)
            }
        }
    }

    @Test
    fun `two pages with the same URL require a different way to disambiguate`() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("""[
                {"type":"page","url":"https://example.test/shared","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/one"},
                {"type":"page","url":"https://example.test/shared","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/two"}
            ]"""))
            ArkWebLoopback(server.port, HarmonyOSArkWebTarget("sample", "https://example.test/shared")).use { browser ->
                val snapshot = browser.snapshot(listOf(0, 0, 100, 100), Duration.ofSeconds(2)) { true }
                assertThat(snapshot).isNull()
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }
}
