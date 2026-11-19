package maestro.harmonyos.layout

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import maestro.TreeNode
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.time.Duration

/** UITest operations over a separately managed loopback forward. */
internal class LoopbackLayoutChannel(private val port: Int) : AutoCloseable {
    private val mapper = ObjectMapper()
    private val layouts = UitestLayoutParser()
    private var connection: Socket? = null
    private var input: BufferedInputStream? = null
    private var readDeadlineNanos = 0L
    private var sequence = 0L
    private var driverRef: String? = null
    private val logger = org.slf4j.LoggerFactory.getLogger(LoopbackLayoutChannel::class.java)

    @Synchronized
    fun snapshot(timeout: Duration): TreeNode {
        readDeadlineNanos = System.nanoTime() + timeout.toNanos()
        try {
            return request(timeout)
        } catch (error: EOFException) {
            // Only this read-only request can be repeated after a peer closes the socket.
            logger.debug("UITest closed the layout connection; opening it once more", error)
            return try {
                request(timeout)
            } catch (repeated: EOFException) {
                throw HdcException("UITest closed the layout connection again", repeated)
            }
        }
    }

    private fun request(timeout: Duration): TreeNode {
        val layout = exchange("Captures", mapOf("api" to "captureLayout", "args" to emptyMap<String, String>()), timeout)
        return try {
            val tree = layouts.parse(if (layout.isTextual) layout.asText() else layout.toString())
            remainingMillis()
            tree
        } catch (error: SocketTimeoutException) {
            close()
            throw HdcTimeoutException(timeout, "UITest layout RPC exceeded its read deadline")
        } catch (error: UitestLayoutException) {
            close()
            throw error
        }
    }

    @Synchronized
    fun displaySize(timeout: Duration): Pair<Int, Int> {
        readDeadlineNanos = System.nanoTime() + timeout.toNanos()
        try {
            val size = driverCall("Driver.getDisplaySize", emptyList(), timeout)
            val width = size.path("x").asInt()
            val height = size.path("y").asInt()
            if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) {
                throw HdcException("UITest returned invalid display dimensions")
            }
            return width to height
        } catch (error: EOFException) {
            throw HdcException("UITest closed the display-size connection", error)
        }
    }

    @Synchronized
    fun displayRotation(timeout: Duration): Int {
        readDeadlineNanos = System.nanoTime() + timeout.toNanos()
        val value = rotationCall("Driver.getDisplayRotation", emptyList(), timeout)
        if (!value.isIntegralNumber || value.asInt(-1) !in 0..3) {
            throw HdcException("UITest did not return a valid display rotation.")
        }
        return value.asInt()
    }

    @Synchronized
    fun setDisplayRotation(rotation: Int, timeout: Duration) {
        readDeadlineNanos = System.nanoTime() + timeout.toNanos()
        rotationCall("Driver.setDisplayRotation", listOf(rotation), timeout)
    }

    private fun rotationCall(api: String, arguments: List<Any>, timeout: Duration): JsonNode =
        try { driverCall(api, arguments, timeout) } catch (_: EOFException) {
            // A mutation is never replayed after disconnect.
            throw HdcException("UITest closed the display-rotation connection.")
        }

    private fun driverCall(api: String, arguments: List<Any>, timeout: Duration): JsonNode {
        fun call(method: String, receiver: String?, args: List<Any>): JsonNode = exchange(
            "callHypiumApi", mapOf("api" to method, "this" to receiver, "args" to args, "message_type" to "hypium"), timeout)
        val receiver = driverRef ?: call("Driver.create", null, emptyList()).takeIf(JsonNode::isTextual)?.asText()
            ?.takeIf(String::isNotBlank)?.also { driverRef = it } ?: throw HdcException("UITest returned no driver reference")
        return call(api, receiver, arguments)
    }

    private fun exchange(method: String, params: Map<String, Any?>, timeout: Duration): JsonNode {
        try {
            val socket = connection ?: Socket().also {
                connection = it
                it.connect(InetSocketAddress("127.0.0.1", port), remainingMillis())
                it.tcpNoDelay = true
                input = BufferedInputStream(object : FilterInputStream(it.getInputStream()) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        it.soTimeout = remainingMillis()
                        return super.read(bytes, offset, length)
                    }
                })
            }
            val id = (++sequence).toString()
            socket.getOutputStream().write(mapper.writeValueAsBytes(mapOf(
                "module" to "com.ohos.devicetest.hypiumApiHelper",
                "method" to method,
                "params" to params,
                "request_id" to id,
            )))
            val source = checkNotNull(input)
            val payload = receiveLayoutFrame {
                source.read()
            }
            val result = mapper.readTree(payload)
            if (result == null || !result.isObject) throw HdcException("Invalid UITest layout RPC response")
            val responseId = result.get("request_id")
            if ((responseId != null && !responseId.isNull && responseId.asText() != id) ||
                result.get("exception")?.let { !it.isNull && !(it.isTextual && it.asText().isEmpty()) } == true
            ) throw HdcException("UITest layout RPC returned an error or a mismatched request ID")
            remainingMillis()
            return result.get("result") ?: throw HdcException("UITest RPC returned no result")
        } catch (error: Exception) {
            close()
            when (error) {
                is SocketTimeoutException -> throw HdcTimeoutException(timeout, "UITest layout RPC exceeded its read deadline")
                is EOFException -> throw error
                is ConnectException -> throw HdcException("Cannot connect to the configured UITest layout forward", error)
                is IOException -> throw HdcException("UITest layout RPC failed: ${error.message}", error)
                else -> throw error
            }
        }
    }

    private fun remainingMillis(): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Layout RPC interrupted")
        val nanos = readDeadlineNanos - System.nanoTime()
        if (nanos <= 0) throw SocketTimeoutException("Layout RPC deadline expired")
        return (nanos / 1_000_000).coerceAtLeast(1).toInt()
    }

    @Synchronized
    override fun close() {
        try { connection?.close() } catch (_: IOException) { /* Socket is no longer usable. */ }
        connection = null
        input = null
        driverRef = null
    }
}

private const val MAX_FRAME = 8 * 1024 * 1024

/** The supported daemon can reply with a bare object or a big-endian length prefix. */
internal fun receiveLayoutFrame(read: () -> Int): ByteArray {
    fun next(): Int = read().also { if (it < 0) throw EOFException("Incomplete UITest layout frame") }
    var first = next()
    while (first in listOf(9, 10, 13, 32)) first = next()
    if (first != '{'.code) {
        val size = (first shl 24) or (next() shl 16) or (next() shl 8) or next()
        if (size !in 2..MAX_FRAME) throw IOException("Invalid UITest layout frame size")
        return ByteArray(size) { next().toByte() }
    }
    val bytes = ByteArrayOutputStream()
    bytes.write(first)
    var depth = 1
    var quoted = false
    var escaped = false
    while (depth != 0) {
        if (bytes.size() >= MAX_FRAME) throw IOException("UITest layout frame exceeds 8 MiB")
        val char = next()
        bytes.write(char)
        if (escaped) escaped = false
        else if (quoted && char == '\\'.code) escaped = true
        else if (char == '"'.code) quoted = !quoted
        else if (!quoted) when (char) {
            '{'.code -> depth++
            '}'.code -> depth--
        }
    }
    return bytes.toByteArray()
}
