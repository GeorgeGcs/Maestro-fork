package maestro.harmonyos.layout

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.TreeNode
import maestro.harmonyos.driver.HarmonyOSArkWebTarget
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Reads a test WebView through an already configured, device-verified HDC forward. */
internal class ArkWebLoopback(
    private val port: Int,
    private val target: HarmonyOSArkWebTarget? = null,
    private val nowNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val endpoint = "127.0.0.1:$port"
    private val logger = LoggerFactory.getLogger(ArkWebLoopback::class.java)
    private var connection: Connection? = null
    private var sequence = 0L

    private fun discoverPage(timeout: Duration): String? {
        val request = Request.Builder().url("http://$endpoint/json/list").build()
        val call = client.newCall(request)
        call.timeout().timeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.peekBody(MAX_JSON_BYTES + 1).string()
            if (body.length > MAX_JSON_BYTES) return null
            val targets = json.readTree(body)
            if (!targets.isArray) return null
            val page = targets.singleOrNull { page ->
                page.path("type").asText() == "page" && (target == null || page.path("url").textValue() == target.pageUrl)
            } ?: return null
            val uri = URI(page.path("webSocketDebuggerUrl").asText())
            uri.rawPath?.takeIf { uri.scheme == "ws" && uri.port == port &&
                uri.host in setOf("localhost", "127.0.0.1") &&
                it.matches(Regex("/devtools/page/[A-Za-z0-9_-]+")) } ?: return null
        }
    }

    fun snapshot(bounds: List<Int>, timeout: Duration, verifyForward: (Duration) -> Boolean): TreeNode? =
        read(bounds, timeout, "window.maestro.getContentDescription()", verifyForward)
            ?.let { json.readValue(it, TreeNode::class.java) }

    fun queryCss(
        selector: String,
        bounds: List<Int>,
        timeout: Duration,
        verifyForward: (Duration) -> Boolean,
    ): List<TreeNode>? {
        val argument = json.writeValueAsString(selector)
        val operation = """(() => {
            try { return window.maestro.queryCss($argument); }
            catch (error) { if (error.name === 'SyntaxError') return 'invalid-css'; throw error; }
        })()"""
        val result = read(bounds, timeout, operation, verifyForward) ?: return null
        val nodes = json.readTree(result)
        require(!nodes.isTextual || nodes.textValue() != "invalid-css") { "Invalid ArkWeb CSS selector." }
        check(nodes.isArray) { "ArkWeb returned an invalid CSS result." }
        return nodes.filterNot { it.isNull }.map { json.treeToValue(it, TreeNode::class.java) }
    }

    @Synchronized
    private fun read(
        bounds: List<Int>,
        timeout: Duration,
        operation: String,
        verifyForward: (Duration) -> Boolean,
    ): String? {
        val deadline = System.nanoTime() + timeout.toNanos()
        fun remaining(): Duration {
            val nanos = deadline - System.nanoTime()
            if (nanos <= 0) throw java.util.concurrent.TimeoutException("ArkWeb snapshot deadline expired")
            return Duration.ofNanos(nanos)
        }
        var active = connection?.takeIf { !it.failed && nowNanos() - it.openedAt < FORWARD_TTL_NANOS }
        val needsVerification = active == null || (target != null &&
            (active.reusedVerification || nowNanos() - active.verifiedAt >= EXPLICIT_FORWARD_REUSE_NANOS))
        var verifiedAt = active?.verifiedAt ?: 0L
        if (needsVerification) {
            if (!measurePhase("device-forward") { verifyForward(remaining()) }) {
                logger.debug("ArkWeb device forward not verified")
                close()
                return null
            }
            verifiedAt = nowNanos()
            active?.apply {
                this.verifiedAt = verifiedAt
                reusedVerification = false
            }
        } else if (target != null) {
            checkNotNull(active).reusedVerification = true
            logger.info("HarmonyOS ArkWeb phase=device-forward-reused elapsedMs=0")
        }
        if (active == null) {
            close()
            val path = measurePhase("page-discovery") { discoverPage(remaining()) }
            if (path == null) {
                logger.debug("ArkWeb page discovery did not confirm a unique loopback target")
                return null
            }
            active = Connection(nowNanos(), verifiedAt)
            active.socket = client.newWebSocket(Request.Builder().url("ws://$endpoint$path").build(), active)
            connection = active
        }
        val (left, top, right, bottom) = bounds
        val capture = "$script; maestro.viewportX = $left; maestro.viewportY = $top;" +
            " maestro.viewportWidth = ${right - left}; maestro.viewportHeight = ${bottom - top};" +
            " JSON.stringify($operation, window.maestro.cycleSafeReplacer());"
        val expression = if (target == null) capture else {
            val url = json.writeValueAsString(target.pageUrl)
            val webViewId = json.writeValueAsString(target.webViewId)
            "if (window.location.href === $url && " +
                "document.querySelector('meta[name=maestro-webview-id]')?.content === $webViewId) { $capture } else { null; }"
        }
        val id = ++sequence
        val payload = json.writeValueAsString(mapOf("id" to id, "method" to "Runtime.evaluate",
            "params" to mapOf("expression" to expression, "returnByValue" to true)))
        val pending = Pending(id, CompletableFuture())
        active.pending.set(pending)
        return try {
            measurePhase("dom") {
                check(!active.failed && active.socket.send(payload)) { "Could not send ArkWeb snapshot request" }
                val snapshot = pending.result.get(remaining().toNanos(), TimeUnit.NANOSECONDS)
                if (snapshot.isEmpty() || snapshot == "null") {
                    logger.debug("ArkWeb page returned no verified DOM snapshot")
                    close()
                    null
                } else snapshot
            }
        } catch (error: Exception) {
            close()
            throw error
        } finally {
            active.pending.compareAndSet(pending, null)
        }
    }

    private inline fun <T> measurePhase(phase: String, action: () -> T): T {
        val started = System.nanoTime()
        try {
            return action()
        } finally {
            logger.info("HarmonyOS ArkWeb phase={} elapsedMs={}", phase,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
        }
    }

    @Synchronized
    override fun close() {
        connection?.socket?.cancel()
        connection = null
    }

    private data class Pending(val id: Long, val result: CompletableFuture<String>)

    private class Connection(val openedAt: Long, var verifiedAt: Long) : WebSocketListener() {
        lateinit var socket: WebSocket
        var reusedVerification = false
        val pending = AtomicReference<Pending?>()
        @Volatile var failed = false
            private set

        override fun onMessage(webSocket: WebSocket, text: String) {
            val current = pending.get() ?: return
            try {
                check(text.length <= MAX_JSON_BYTES) { "ArkWeb snapshot exceeds size limit" }
                val message = json.readTree(text)
                if (message.path("id").asLong() == current.id) {
                    current.result.complete(message.path("result").path("result").path("value").asText())
                }
            } catch (error: Exception) {
                current.result.completeExceptionally(error)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onFailure(webSocket, IllegalStateException("ArkWeb closed the page connection"), null)
            webSocket.cancel()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failed = true
            pending.get()?.result?.completeExceptionally(t)
        }
    }

    private companion object {
        val FORWARD_TTL_NANOS = TimeUnit.SECONDS.toNanos(5)
        // Only the next read on an already verified socket can reuse the proof.
        // URL and WebView marker are still checked for every DOM read.
        val EXPLICIT_FORWARD_REUSE_NANOS = TimeUnit.SECONDS.toNanos(2)
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).build()
        val json = jacksonObjectMapper()
        val script: String by lazy {
            requireNotNull(ArkWebLoopback::class.java.getResourceAsStream("/maestro-web.js")).bufferedReader().use { it.readText() }
        }
    }
}
