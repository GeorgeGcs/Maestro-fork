package maestro.harmonyos.layout

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.TreeNode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Reads a test WebView through an already configured, device-verified HDC forward. */
internal class ArkWebLoopback(private val port: Int) {
    private val endpoint = "127.0.0.1:$port"

    fun snapshot(bounds: List<Int>, timeout: Duration): TreeNode? {
        val started = System.nanoTime()
        val budgetMs = timeout.toMillis().coerceAtLeast(1)
        val request = Request.Builder().url("http://$endpoint/json/list").build()
        val call = client.newCall(request)
        call.timeout().timeout(budgetMs, TimeUnit.MILLISECONDS)
        val path = call.execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.peekBody(MAX_JSON_BYTES + 1).string()
            if (body.length > MAX_JSON_BYTES) return null
            val targets = json.readTree(body)
            if (!targets.isArray) return null
            val page = targets.filter { it.path("type").asText() == "page" }.singleOrNull() ?: return null
            val uri = URI(page.path("webSocketDebuggerUrl").asText())
            uri.rawPath?.takeIf { uri.scheme == "ws" && uri.port == port &&
                uri.host in setOf("localhost", "127.0.0.1") &&
                it.matches(Regex("/devtools/page/[A-Za-z0-9_-]+")) } ?: return null
        }

        val (left, top, right, bottom) = bounds
        val expression = "$script; maestro.viewportX = $left; maestro.viewportY = $top;" +
            " maestro.viewportWidth = ${right - left}; maestro.viewportHeight = ${bottom - top};" +
            " JSON.stringify(window.maestro.getContentDescription(), window.maestro.cycleSafeReplacer());"
        val payload = json.writeValueAsString(mapOf("id" to 1, "method" to "Runtime.evaluate",
            "params" to mapOf("expression" to expression, "returnByValue" to true)))
        val result = CompletableFuture<String>()
        val socket = client.newWebSocket(Request.Builder().url("ws://$endpoint$path").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(payload)) result.completeExceptionally(IllegalStateException("Could not send ArkWeb snapshot request"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_JSON_BYTES) {
                    result.completeExceptionally(IllegalStateException("ArkWeb snapshot exceeds size limit"))
                    return
                }
                try {
                    val message = json.readTree(text)
                    if (message.path("id").asInt() == 1) {
                        result.complete(message.path("result").path("result").path("value").asText())
                    }
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.completeExceptionally(t)
            }
        })
        return try {
            val remainingMs = (budgetMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).coerceAtLeast(1)
            val snapshot = result.get(remainingMs, TimeUnit.MILLISECONDS)
            if (snapshot.isEmpty() || snapshot == "null") null else json.readValue(snapshot, TreeNode::class.java)
        } finally {
            socket.cancel()
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).build()
        val json = jacksonObjectMapper()
        val script: String by lazy {
            requireNotNull(ArkWebLoopback::class.java.getResourceAsStream("/maestro-web.js")).bufferedReader().use { it.readText() }
        }
    }
}
