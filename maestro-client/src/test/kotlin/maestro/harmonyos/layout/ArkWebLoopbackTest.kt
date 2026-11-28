package maestro.harmonyos.layout

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.Point
import maestro.harmonyos.driver.HarmonyOSArkWebTarget
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import java.time.Duration

class ArkWebLoopbackTest {
    @Test
    fun `tap hit carries the original node and tap coordinates to the verified page`() {
        val json = ObjectMapper()
        val selector = """[data-name="first"]"""
        val nodeId = "doc-1"
        val operation = "window.maestro.arkWebTapHit(${json.writeValueAsString(selector)}, \"doc-1\", 79, 131)"
        val expressions = java.util.concurrent.CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody(
                """[{"type":"page","url":"https://example.test/two","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/two"}]"""
            ))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = json.readTree(text)
                    expressions += request.path("params").path("expression").asText()
                    val reply = if (expressions.size == 1) "blocked by div#overlay" else "hit"
                    webSocket.send(json.writeValueAsString(mapOf(
                        "id" to request.path("id").asLong(),
                        "result" to mapOf("result" to mapOf("value" to json.writeValueAsString(reply))),
                    )))
                }
            }))
            val target = HarmonyOSArkWebTarget("probe-secondary", "https://example.test/two")
            ArkWebLoopback(server.port, target).use { browser ->
                fun hit(forward: Boolean) = browser.tapHit(selector, nodeId, Point(79, 131),
                    listOf(5, 30, 105, 230), Duration.ofSeconds(2)) { forward }
                assertThat(hit(true)).isEqualTo("blocked by div#overlay")
                assertThat(hit(true)).isEqualTo("hit")
                assertThat(hit(false)).isNull()
                assertThat(expressions).hasSize(2)
                for (expression in expressions) {
                    assertThat(expression).contains("window.location.href === \"https://example.test/two\"")
                    assertThat(expression).contains("?.content === \"probe-secondary\"")
                    assertThat(expression).contains("window.maestro.arkWebTapIdentityEnabled = true")
                    assertThat(expression).contains("maestro.viewportX = 5; maestro.viewportY = 30;")
                    assertThat(expression).contains(operation)
                }
            }
        }
    }

    @Test
    fun `CSS separates matches no matches and an invalid selector from a lost target`() {
        val json = ObjectMapper()
        val selector = """[data-testid="submit"]"""
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody(
                """[{"type":"page","url":"https://example.test/two","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/two"}]"""
            ))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = json.readTree(text)
                    val expression = request.path("params").path("expression").asText()
                    val value = when {
                        !expression.contains("window.location.href === \"https://example.test/two\"") -> "null"
                        expression.contains("window.maestro.queryCss(${json.writeValueAsString(selector)})") ->
                            """[{"attributes":{"text":"Submit","bounds":"[5,5][30,30]"},"children":[]}]"""
                        expression.contains("window.maestro.queryCss(\"[\"") -> "\"invalid-css\""
                        else -> "[]"
                    }
                    webSocket.send(json.writeValueAsString(mapOf(
                        "id" to request.path("id").asLong(),
                        "result" to mapOf("result" to mapOf("value" to value)),
                    )))
                }
            }))
            val target = HarmonyOSArkWebTarget("probe-secondary", "https://example.test/two")
            val bounds = listOf(0, 0, 100, 100)
            val timeout = Duration.ofSeconds(2)
            ArkWebLoopback(server.port, target).use { browser ->
                assertThat(browser.queryCss(selector, bounds, timeout) { true }?.single()?.attributes?.get("text"))
                    .isEqualTo("Submit")
                assertThat(browser.queryCss(".absent", bounds, timeout) { true }).isEmpty()
                assertThrows(IllegalArgumentException::class.java) { browser.queryCss("[", bounds, timeout) { true } }
                assertThat(browser.queryCss(".absent", bounds, timeout) { false }).isEmpty()
                assertThat(browser.queryCss(".absent", bounds, timeout) { false }).isNull()
            }
        }
    }

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
    fun `an explicit page checks its marker on every read and rechecks the forward after the reuse window`() {
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
            var now = 0L
            val target = HarmonyOSArkWebTarget("probe-secondary", "https://example.test/two")
            ArkWebLoopback(server.port, target, nowNanos = { now }).use { browser ->
                val bounds = listOf(0, 0, 100, 100)
                val timeout = Duration.ofSeconds(2)
                assertThat(browser.snapshot(bounds, timeout) { true }?.attributes?.get("text")).isEqualTo("page two")
                assertThat(server.takeRequest().path).isEqualTo("/json/list")
                assertThat(server.takeRequest().path).isEqualTo("/devtools/page/two")
                now += Duration.ofMillis(10).toNanos()
                assertThat(browser.snapshot(bounds, timeout) { error("forward already verified") }
                    ?.attributes?.get("text")).isEqualTo("page two")
                var rechecked = false
                assertThat(browser.snapshot(bounds, timeout) {
                    rechecked = true
                    true
                }?.attributes?.get("text")).isEqualTo("page two")
                assertThat(rechecked).isTrue()
                now += Duration.ofSeconds(2).toNanos()
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
