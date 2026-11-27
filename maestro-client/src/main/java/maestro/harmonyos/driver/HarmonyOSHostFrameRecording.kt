package maestro.harmonyos.driver

import maestro.ScreenRecording
import okio.Sink
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class HarmonyOSScreenFrame(val bytes: ByteArray, val capturedAt: Long = System.nanoTime())

/** Stages a bounded number of PNGs on the host and publishes a closed MP4 to the artifact sink. */
internal class HarmonyOSHostFrameRecording(
    private val out: Sink,
    private val ffmpeg: Path,
    private val maxDuration: Duration,
    private val maxBytes: Long,
    private val stopTimeout: Duration,
    private val deduplicateFrames: Boolean,
) : ScreenRecording {
    override val startedAt = Instant.now()
    private val startNanos = System.nanoTime()
    private val endNanos = startNanos + maxDuration.toNanos()
    private val directory = Files.createTempDirectory("maestro-harmonyos-host-recording-")
    private val pending = ArrayBlockingQueue<HarmonyOSScreenFrame>(2)
    private val dropped = AtomicInteger()
    private val frames = mutableListOf<Pair<String, Long>>()
    private var savedBytes = 0L
    private var duplicates = 0
    @Volatile private var accepting = true
    @Volatile private var failure: IOException? = null
    private val writer = Thread({ stageFrames() }, "maestro-harmonyos-host-video").apply {
        isDaemon = true
        start()
    }

    fun wantsFrame(): Boolean = accepting && failure == null && System.nanoTime() < endNanos

    fun offer(frame: HarmonyOSScreenFrame) {
        synchronized(pending) {
            if (frame.capturedAt !in startNanos until endNanos || !accepting || failure != null) return
            if (!pending.offer(frame)) dropped.incrementAndGet()
        }
    }

    private fun stageFrames() {
        var previousBytes: ByteArray? = null
        try {
            while (accepting || pending.isNotEmpty()) {
                val frame = pending.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (deduplicateFrames && previousBytes?.contentEquals(frame.bytes) == true) {
                    duplicates++
                    continue
                }
                if (savedBytes + frame.bytes.size > maxBytes) {
                    throw IOException("Host recording reached the frame storage limit.")
                }
                val name = "frame-${frames.size}.png"
                Files.write(directory.resolve(name), frame.bytes)
                savedBytes += frame.bytes.size
                frames += name to frame.capturedAt
                if (deduplicateFrames) previousBytes = frame.bytes
            }
        } catch (error: IOException) {
            failure = error
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Synchronized
    override fun close() {
        if (!accepting) return
        synchronized(pending) { accepting = false }
        val finishedAt = System.nanoTime()
        val deadline = System.nanoTime() + stopTimeout.toNanos()
        try {
            writer.join(stopTimeout.toMillis().coerceAtLeast(1))
            if (writer.isAlive) throw IOException("Host recording could not flush its frame queue.")
            failure?.let { throw it }
            if (finishedAt > endNanos) throw IOException("Host recording exceeded its configured duration.")
            if (frames.isEmpty()) throw IOException("Host recording did not capture a frame.")
            val manifest = directory.resolve("frames.ffconcat")
            Files.writeString(manifest, buildString {
                appendLine("ffconcat version 1.0")
                // Skipped duplicates extend the previous image to the next saved frame or recording stop.
                frames.forEachIndexed { index, (name, capturedAt) ->
                    val end = frames.getOrNull(index + 1)?.second ?: finishedAt
                    val duration = (end - capturedAt).coerceAtLeast(40_000_000) / 1e9
                    appendLine("file $name")
                    appendLine("duration " + String.format(Locale.ROOT, "%.6f", duration))
                }
                appendLine("file ${frames.last().first}")
            })
            val output = directory.resolve("recording.mp4")
            encode(manifest, output, deadline)
            Mp4RecordingValidator.validate(output, maxBytes)
            output.toFile().source().buffer().use { source ->
                val sink = out.buffer()
                sink.writeAll(source)
                sink.flush()
            }
            logger.info("HarmonyOS host recording: frames={} duplicates={} deduplicateFrames={} dropped={} durationMs={}",
                frames.size, duplicates, deduplicateFrames, dropped.get(), Duration.ofNanos(finishedAt - startNanos).toMillis())
        } finally {
            if (writer.isAlive) writer.interrupt()
            if (!writer.isAlive) directory.toFile().deleteRecursively()
        }
    }

    private fun encode(manifest: Path, output: Path, deadline: Long) {
        val header = Files.newInputStream(directory.resolve(frames.first().first)).use { it.readNBytes(24) }
        if (header.size != 24) throw IOException("Host recording received a truncated PNG header.")
        val dimensions = ByteBuffer.wrap(header, 16, 8)
        val width = dimensions.int
        val height = dimensions.int
        if (width < 2 || height < 2 || width.toLong() * height > 16_000_000) {
            throw IOException("Host recording received unsupported PNG dimensions.")
        }
        val ratio = minOf(1.0, 960.0 / maxOf(width, height))
        val canvasWidth = ((width * ratio).toInt() / 2 * 2).coerceAtLeast(2)
        val canvasHeight = ((height * ratio).toInt() / 2 * 2).coerceAtLeast(2)
        val errors = directory.resolve("ffmpeg.log")
        val encoder = hostFrameEncoder(ffmpeg, manifest, output, canvasWidth, canvasHeight).redirectError(errors.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            if (!encoder.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
                throw IOException("FFmpeg exceeded the host recording stop timeout.")
            }
            if (encoder.exitValue() != 0) {
                val detail = Files.newInputStream(errors).use { it.readNBytes(4096) }.decodeToString().trim()
                throw IOException("FFmpeg could not encode the host recording. $detail")
            }
        } finally {
            if (encoder.isAlive) {
                encoder.destroyForcibly()
                encoder.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(HarmonyOSHostFrameRecording::class.java)
    }
}

internal fun hostFrameEncoder(ffmpeg: Path, manifest: Path, output: Path, canvasWidth: Int, canvasHeight: Int): ProcessBuilder {
    val directory = manifest.toAbsolutePath().normalize().parent
    require(output.toAbsolutePath().normalize().parent == directory)
    // The concat demuxer resolves entries as URLs. A '#' in an absolute manifest path changes their base.
    return ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin", "-loglevel", "error", "-xerror",
        "-f", "concat", "-safe", "1", "-i", manifest.fileName.toString(), "-an", "-vf",
        "scale=$canvasWidth:$canvasHeight:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=$canvasWidth:$canvasHeight:(ow-iw)/2:(oh-ih)/2",
        "-fps_mode", "vfr", "-c:v", "libx264", "-preset", "veryfast", "-threads", "2",
        "-maxrate", "4M", "-bufsize", "8M", "-pix_fmt", "yuv420p",
        "-movflags", "+faststart", output.fileName.toString()).directory(directory.toFile())
}
