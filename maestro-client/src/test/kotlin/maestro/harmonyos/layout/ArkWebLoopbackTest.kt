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
                fun hit(pid: String?) = browser.tapHit(selector, nodeId, Point(79, 131),
                    listOf(5, 30, 105, 230), Duration.ofSeconds(2), forceVerification = true) { pid }
                assertThat(hit("16")).isEqualTo("blocked by div#overlay")
                assertThat(hit("16")).isEqualTo("hit")
                assertThat(hit(null)).isNull()
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
                assertThat(browser.queryCss(selector, bounds, timeout) { "16" }?.single()?.attributes?.get("text"))
                    .isEqualTo("Submit")
                assertThat(browser.queryCss(".absent", bounds, timeout) { "16" }).isEmpty()
                assertThrows(IllegalArgumentException::class.java) { browser.queryCss("[", bounds, timeout) { "16" } }
                assertThat(browser.queryCss(".absent", bounds, timeout) { null }).isEmpty()
                assertThat(browser.queryCss(".absent", bounds, timeout) { null }).isNull()
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
                    val request = json.readTree(text)
                    val id = request.path("id").asLong()
                    val expression = request.path("params").path("expression").asText()
                    val value = if (expression.contains("JSON.stringify(window.maestro.arkWebTapHit(")) "\"hit\"" else
                        """{"attributes":{"text":"snapshot $id"},"children":[]}"""
                    webSocket.send(json.writeValueAsString(mapOf(
                        "id" to id, "result" to mapOf("result" to mapOf("value" to value))
                    )))
                }
            }))
            ArkWebLoopback(server.port).use { browser ->
                var verifications = 0
                fun snapshot() = browser.snapshot(listOf(0, 0, 100, 100), Duration.ofSeconds(2)) {
                    verifications++
                    "16"
                }
                assertThat(snapshot()?.attributes?.get("text")).isEqualTo("snapshot 1")
                assertThat(snapshot()?.attributes?.get("text")).isEqualTo("snapshot 2")
                assertThat(browser.tapHit("button", "original", Point(1, 1), listOf(0, 0, 100, 100),
                    Duration.ofSeconds(2), forceVerification = true) { error("Unexpected extra forward verification") })
                    .isEqualTo("hit")
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
                assertThat(browser.snapshot(bounds, timeout) { "16" }?.attributes?.get("text")).isEqualTo("page two")
                assertThat(server.takeRequest().path).isEqualTo("/json/list")
                assertThat(server.takeRequest().path).isEqualTo("/devtools/page/two")
                now += Duration.ofMillis(10).toNanos()
                assertThat(browser.snapshot(bounds, timeout) { error("forward already verified") }
                    ?.attributes?.get("text")).isEqualTo("page two")
                var rechecked = false
                assertThat(browser.snapshot(bounds, timeout) {
                    rechecked = true
                    "16"
                }?.attributes?.get("text")).isEqualTo("page two")
                assertThat(rechecked).isTrue()
                now += Duration.ofSeconds(2).toNanos()
                assertThat(browser.snapshot(bounds, timeout) { null }).isNull()
                assertThat(server.requestCount).isEqualTo(2)
            }
        }
    }

    @Test
    fun `CSS tap scope reuses the binding but reads fresh DOM and verifies every actual dispatch`() {
        val expressions = java.util.concurrent.CopyOnWriteArrayList<String>()
        val target = HarmonyOSArkWebTarget("probe-secondary", "https://example.test/two")
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            enqueuePage(server) { expression ->
                expressions += expression
                if (expression.contains("JSON.stringify(window.maestro.arkWebTapHit(")) "\"hit\"" else
                    """{"attributes":{"text":"read ${expressions.size}"},"children":[]}"""
            }
            var now = 0L
            var checks = 0
            var pid = "16"
            val bounds = listOf(0, 0, 100, 100)
            val timeout = Duration.ofSeconds(2)
            ArkWebLoopback(server.port, target) { now }.use { browser ->
                fun proof(remaining: Duration): String {
                    assertThat(remaining.isZero).isFalse()
                    checks++
                    return pid
                }
                fun snapshot() = browser.snapshot(bounds, timeout, ::proof)?.attributes?.get("text")
                fun hit() = browser.tapHit("button", "node", Point(1, 1), bounds, timeout, true, ::proof)
                try {
                    ArkWebLoopback.cssTapScope.set(Any())
                    assertThat(snapshot()).isEqualTo("read 1")
                    now = Duration.ofSeconds(1).toNanos()
                    assertThat(snapshot()).isEqualTo("read 2")
                    now = Duration.ofMillis(1999).toNanos()
                    assertThat(snapshot()).isEqualTo("read 3")
                    assertThat(checks).isEqualTo(1)
                    now = Duration.ofSeconds(2).toNanos()
                    assertThat(snapshot()).isEqualTo("read 4")
                    assertThat(checks).isEqualTo(2)
                    assertThat(hit()).isEqualTo("hit")
                    assertThat(snapshot()).isEqualTo("read 6")
                    assertThat(checks).isEqualTo(3)
                    ArkWebLoopback.cssTapScope.remove()
                    assertThat(snapshot()).isEqualTo("read 7")
                    ArkWebLoopback.cssTapScope.set(Any())
                    assertThat(snapshot()).isEqualTo("read 8")
                    assertThat(checks).isEqualTo(5)
                    // Both PIDs have valid forwards. The old socket is still alive and must not be used.
                    pid = "17"
                    assertThat(hit()).isNull()
                    assertThat(checks).isEqualTo(6)
                    assertThat(expressions).hasSize(8)
                    assertThat(expressions.first()).contains("document[Symbol.for('maestro.arkWebDocument')] = ")
                    assertThat(expressions[1]).contains("document[Symbol.for('maestro.arkWebDocument')] === ")
                    assertThat(expressions[6]).doesNotContain("maestro.arkWebDocument")
                } finally {
                    ArkWebLoopback.cssTapScope.remove()
                }
            }
        }
    }

    @Test
    fun `same URL reload is allowed between steps but invalidates the hit in the current step`() {
        val bounds = listOf(0, 0, 100, 100)
        val timeout = Duration.ofSeconds(2)
        val target = HarmonyOSArkWebTarget("probe-secondary", "https://example.test/two")
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val documentKey = "document[Symbol.for('maestro.arkWebDocument')]"
            val bindings = java.util.concurrent.CopyOnWriteArrayList<String>()
            repeat(2) {
                enqueuePage(server) { expression ->
                    when {
                        expression.contains("($documentKey = ") -> {
                            bindings += expression.substringAfter("($documentKey = ").substringBefore(")")
                            """{"attributes":{"text":"current"},"children":[]}"""
                        }
                        expression.contains("$documentKey === ") -> "null" // the new document has no old marker
                        else -> "\"hit\""
                    }
                }
            }
            ArkWebLoopback(server.port, target).use { browser ->
                var checks = 0
                fun proof(remaining: Duration): String {
                    assertThat(remaining.isNegative).isFalse()
                    checks++
                    return "16"
                }
                try {
                    ArkWebLoopback.cssTapScope.set(Any())
                    assertThat(browser.snapshot(bounds, timeout, ::proof)).isNotNull()
                    // The next step starts after a reload on the same live socket.
                    ArkWebLoopback.cssTapScope.set(Any())
                    assertThat(browser.snapshot(bounds, timeout, ::proof)).isNotNull()
                    assertThat(checks).isEqualTo(2)
                    assertThat(server.requestCount).isEqualTo(2)
                    // Another reload during that step replaces the document that supplied the target.
                    assertThat(browser.tapHit("button", "old-node", Point(1, 1), bounds, timeout, true, ::proof)).isNull()
                    assertThat(browser.snapshot(bounds, timeout, ::proof)).isNotNull()
                    assertThat(checks).isEqualTo(4)
                    assertThat(bindings.toSet()).hasSize(3)
                    assertThat(server.requestCount).isEqualTo(4)
                } finally {
                    ArkWebLoopback.cssTapScope.remove()
                }
            }
        }
    }

    @Test
    fun `dispatch renews the same page when less than two seconds of socket life remain after verification`() {
        checkDispatchRenewal(3001L)
    }

    @Test
    fun `a live socket at its five second hard limit can only renew the same document`() {
        checkDispatchRenewal(5000L)
    }

    @Test
    fun `dispatch rejects a different page path or a replaced document when renewing`() {
        checkDispatchRenewal(3001L, nextPage = "changed")
        checkDispatchRenewal(5000L, sameDocument = false)
    }

    private fun checkDispatchRenewal(atMs: Long, nextPage: String = "two", sameDocument: Boolean = true) {
        val bounds = listOf(0, 0, 100, 100)
        val timeout = Duration.ofSeconds(2)
        val target = HarmonyOSArkWebTarget("probe-secondary", "https://example.test/two")
        val key = "document[Symbol.for('maestro.arkWebDocument')]"
        val token = java.util.concurrent.atomic.AtomicReference<String>()
        val renewed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val tree = """{"attributes":{"text":"current"},"children":[]}"""
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            enqueuePage(server) { expression ->
                if (expression.contains("($key = ")) {
                    token.set(expression.substringAfter("($key = ").substringBefore(")"))
                    tree
                } else "\"hit\""
            }
            enqueuePage(server, nextPage) { expression ->
                renewed += expression
                when {
                    !sameDocument || !expression.contains("$key === ${token.get()}") -> "null"
                    expression.contains("JSON.stringify(window.maestro.arkWebTapHit(") -> "\"hit\""
                    else -> tree
                }
            }
            var now = 0L
            var checks = 0
            ArkWebLoopback(server.port, target) { now }.use { browser ->
                fun read() = browser.snapshot(bounds, timeout) { checks++; "16" }
                fun hit(proofFinishedMs: Long) = browser.tapHit("button", "original", Point(1, 1), bounds, timeout, true) {
                    checks++
                    now = Duration.ofMillis(proofFinishedMs).toNanos()
                    "16"
                }
                try {
                    ArkWebLoopback.cssTapScope.set(Any())
                    assertThat(read()).isNotNull()
                    assertThat(hit(3000)).isEqualTo("hit") // Exactly two seconds remain.
                    assertThat(server.requestCount).isEqualTo(2)
                    if (atMs >= 5000) now = Duration.ofMillis(atMs).toNanos()
                    val result = hit(atMs)
                    assertThat(checks).isEqualTo(3)
                    if (nextPage != "two" || !sameDocument) {
                        assertThat(result).isNull()
                        assertThat(renewed).hasSize(if (nextPage != "two") 0 else 1)
                    } else {
                        assertThat(result).isEqualTo("hit")
                        assertThat(server.requestCount).isEqualTo(4)
                        now += Duration.ofMillis(1999).toNanos()
                        assertThat(read()).isNotNull()
                        assertThat(checks).isEqualTo(3)
                        now += Duration.ofMillis(1).toNanos()
                        assertThat(read()).isNotNull()
                        assertThat(checks).isEqualTo(4)
                    }
                } finally {
                    ArkWebLoopback.cssTapScope.remove()
                }
            }
        }
    }

    private fun enqueuePage(server: MockWebServer, page: String = "two", evaluate: (String) -> String) {
        val json = ObjectMapper()
        server.enqueue(MockResponse().setBody(
            """[{"type":"page","url":"https://example.test/two","webSocketDebuggerUrl":"ws://127.0.0.1:${server.port}/devtools/page/$page"}]"""
        ))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = json.readTree(text)
                val value = evaluate(request.path("params").path("expression").asText())
                webSocket.send(json.writeValueAsString(mapOf("id" to request.path("id").asLong(),
                    "result" to mapOf("result" to mapOf("value" to value)))))
            }
        }))
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
                val snapshot = browser.snapshot(listOf(0, 0, 100, 100), Duration.ofSeconds(2)) { "16" }
                assertThat(snapshot).isNull()
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }
}
