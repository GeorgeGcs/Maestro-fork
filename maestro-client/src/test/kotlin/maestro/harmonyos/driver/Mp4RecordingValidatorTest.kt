package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class Mp4RecordingValidatorTest {
    @Test
    fun `accepts finalized video metadata with media before or after movie`() {
        validate(minimalVideoRecording())
        validate(ftyp() + mdat() + box("moov", track()))
    }

    @Test
    fun `accepts extended box sizes and version one media headers`() {
        validate(box("ftyp", "isom".toByteArray() + int(0), extended = true) +
            box("moov", track(version = 1), extended = true) + box("mdat", byteArrayOf(1), extended = true))
    }

    @Test
    fun `accepts top level media data extending to end of file`() {
        validate(ftyp() + box("moov", track()) + int(0) + "mdat".toByteArray() + byteArrayOf(1))
    }

    @Test
    fun `video metadata must belong to the same track`() {
        invalid(ftyp() + box("moov", track(handler = "soun") + track(duration = 0)) + mdat())
        invalid(ftyp() + box("moov", box("trak", tkhd()) + box("trak", media())) + mdat())
    }

    @Test
    fun `rejects audio only recording and video without dimensions`() {
        invalid(ftyp() + box("moov", track(handler = "soun")) + mdat())
        invalid(ftyp() + box("moov", track(width = 0)) + mdat())
        invalid(ftyp() + box("moov", track(height = 0)) + mdat())
    }

    @Test
    fun `rejects missing or empty required boxes`() {
        invalid(box("moov", track()) + mdat())
        invalid(ftyp() + mdat())
        invalid(ftyp() + box("moov", track()))
        invalid(ftyp() + box("moov", track()) + box("mdat", byteArrayOf()))
        invalid(ftyp() + box("moov", byteArrayOf()) + mdat())
    }

    @Test
    fun `rejects zero and unknown duration and invalid timescale`() {
        invalid(ftyp() + box("moov", track(duration = 0)) + mdat())
        invalid(ftyp() + box("moov", track(duration = 0xffff_ffffL)) + mdat())
        invalid(ftyp() + box("moov", track(timeScale = 0)) + mdat())
        invalid(ftyp() + box("moov", track(version = 1, duration = -1)) + mdat())
    }

    @Test
    fun `rejects unsupported metadata versions and truncated fixed fields`() {
        invalid(ftyp() + box("moov", track(version = 2)) + mdat())
        invalid(ftyp() + box("moov", box("trak", box("tkhd", ByteArray(80)) + media())) + mdat())
        invalid(ftyp() + box("moov", box("trak", tkhd() + box("mdia", box("mdhd", ByteArray(20)) + hdlr("vide")))) + mdat())
        invalid(ftyp() + box("moov", box("trak", tkhd() + box("mdia", mdhd() + box("hdlr", ByteArray(12))))) + mdat())
    }

    @Test
    fun `rejects partial headers payload truncation and boxes escaping their parent`() {
        invalid(minimalVideoRecording() + byteArrayOf(0, 0, 0))
        invalid(minimalVideoRecording().dropLast(1).toByteArray())
        invalid(ftyp() + int(7) + "moov".toByteArray() + mdat())
        invalid(ftyp() + box("moov", int(1000) + "trak".toByteArray()) + mdat())
        invalid(ftyp() + int(1) + "moov".toByteArray() + byteArrayOf(0, 0, 0))
        invalid(ftyp() + int(1) + "moov".toByteArray() + long(15) + mdat())
        invalid(ftyp() + int(1) + "moov".toByteArray() + long(Long.MAX_VALUE) + mdat())
        invalid(ftyp() + int(1) + "moov".toByteArray() + long(Long.MIN_VALUE) + mdat())
    }

    @Test
    fun `rejects duplicate critical boxes and malformed hierarchy`() {
        invalid(ftyp() + ftyp() + box("moov", track()) + mdat())
        invalid(ftyp() + box("moov", track()) + box("moov", track()) + mdat())
        invalid(ftyp() + box("moov", box("trak", tkhd() + tkhd() + media())) + mdat())
        invalid(ftyp() + box("moov", box("trak", tkhd() + media() + media())) + mdat())
        invalid(ftyp() + box("moov", box("trak", tkhd() + box("mdia", mdhd() + mdhd() + hdlr("vide")))) + mdat())
        invalid(ftyp() + box("moov", box("trak", tkhd() + box("mdia", mdhd() + hdlr("vide") + hdlr("soun")))) + mdat())
        invalid(ftyp() + track() + mdat())
        invalid(ftyp() + box("moov", box("mdia", mdhd() + hdlr("vide"))) + mdat())
        invalid(ftyp() + box("moov", int(0) + "trak".toByteArray()) + mdat())
    }

    @Test
    fun `rejects excessive container nesting without unbounded recursion`() {
        var nested = box("free", byteArrayOf())
        repeat(30) { nested = box("udta", nested) }
        invalid(ftyp() + box("moov", track() + nested) + mdat())
    }

    @Test
    fun `skips bounded unknown boxes and inspects a later video track`() {
        validate(ftyp() + box("free", ByteArray(100)) +
            box("moov", track(handler = "soun", width = 0, height = 0) + track()) + mdat())
    }

    @Test
    fun `rejects file size limit and nonregular file`() {
        withFile(minimalVideoRecording()) { path ->
            assertThrows(InvalidMp4RecordingException::class.java) {
                Mp4RecordingValidator.validate(path, Files.size(path) - 1)
            }
            assertThrows(IllegalArgumentException::class.java) { Mp4RecordingValidator.validate(path, 0) }
        }
        TempFileHandler().use { files ->
            val directory = files.createTempDirectory("maestro-mp4-test-").toPath()
            assertThrows(InvalidMp4RecordingException::class.java) { Mp4RecordingValidator.validate(directory, LIMIT) }
        }
    }

    @Test
    fun `filesystem errors retain their original type`() {
        TempFileHandler().use { files ->
            val path = files.createTempDirectory("maestro-mp4-test-").toPath().resolve("missing.mp4")
            assertThrows(NoSuchFileException::class.java) { Mp4RecordingValidator.validate(path, LIMIT) }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `rejects symlinks even when their target is a valid recording`() {
        TempFileHandler().use { files ->
            val directory = files.createTempDirectory("maestro-mp4-test-").toPath()
            val target = directory.resolve("target.mp4")
            Files.write(target, minimalVideoRecording())
            val link = Files.createSymbolicLink(directory.resolve("linked.mp4"), target)
            assertThrows(InvalidMp4RecordingException::class.java) { Mp4RecordingValidator.validate(link, LIMIT) }
        }
    }

    @Test
    fun `interruption prevents metadata reads and remains cancellation`() {
        withFile(minimalVideoRecording()) { path ->
            try {
                Thread.currentThread().interrupt()
                assertThrows(InterruptedException::class.java) { Mp4RecordingValidator.validate(path, LIMIT) }
                assertThat(Thread.currentThread().isInterrupted).isTrue()
            } finally {
                Thread.interrupted()
            }
        }
    }

    private fun validate(bytes: ByteArray) = withFile(bytes) { Mp4RecordingValidator.validate(it, LIMIT) }

    private fun invalid(bytes: ByteArray) = withFile(bytes) { path ->
        assertThrows(InvalidMp4RecordingException::class.java) { Mp4RecordingValidator.validate(path, LIMIT) }
        Unit
    }

    private fun withFile(bytes: ByteArray, action: (Path) -> Unit) {
        TempFileHandler().use { files ->
            val path = files.createTempFile("maestro-mp4-test-", ".mp4").toPath()
            Files.write(path, bytes)
            action(path)
        }
    }

    companion object {
        private const val LIMIT = 1024 * 1024L

        /** Minimal structurally valid metadata; the one-byte sample is deliberately not decodable video. */
        internal fun minimalVideoRecording(): ByteArray = ftyp() + box("moov", track()) + mdat()

        private fun ftyp() = box("ftyp", "isom".toByteArray() + int(0) + "mp42".toByteArray())
        private fun mdat() = box("mdat", byteArrayOf(1))
        private fun track(
            version: Int = 0,
            duration: Long = 1000,
            timeScale: Int = 1000,
            width: Int = 80,
            height: Int = 120,
            handler: String = "vide",
        ): ByteArray = box("trak", tkhd(version, width, height) + media(version, duration, timeScale, handler))

        private fun media(version: Int = 0, duration: Long = 1000, timeScale: Int = 1000, handler: String = "vide") =
            box("mdia", mdhd(version, duration, timeScale) + hdlr(handler))

        private fun tkhd(version: Int = 0, width: Int = 80, height: Int = 120): ByteArray {
            val payload = ByteBuffer.allocate(if (version == 0) 84 else 96).order(ByteOrder.BIG_ENDIAN)
            payload.put(0, version.toByte())
            payload.putInt(if (version == 0) 76 else 88, width shl 16)
            payload.putInt(if (version == 0) 80 else 92, height shl 16)
            return box("tkhd", payload.array())
        }

        private fun mdhd(version: Int = 0, duration: Long = 1000, timeScale: Int = 1000): ByteArray {
            val payload = ByteBuffer.allocate(if (version == 0) 24 else 36).order(ByteOrder.BIG_ENDIAN)
            payload.put(0, version.toByte())
            payload.putInt(if (version == 0) 12 else 20, timeScale)
            if (version == 0) payload.putInt(16, duration.toInt()) else payload.putLong(24, duration)
            return box("mdhd", payload.array())
        }

        private fun hdlr(handler: String): ByteArray = box("hdlr", ByteArray(8) + handler.toByteArray() + ByteArray(12))
        private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
        private fun long(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()
        private fun box(type: String, bytes: ByteArray, extended: Boolean = false): ByteArray =
            if (extended) int(1) + type.toByteArray() + long(bytes.size + 16L) + bytes
            else int(bytes.size + 8) + type.toByteArray() + bytes
    }
}
