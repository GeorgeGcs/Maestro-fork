package maestro.harmonyos.driver

import maestro.harmonyos.hdc.HdcException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

class InvalidMp4RecordingException(message: String) : HdcException("Invalid MP4 recording: $message")

/** Checks a finalized ISO BMFF recording's structure, not whether its compressed frames decode. */
object Mp4RecordingValidator {
    fun validate(path: Path, maxBytes: Long) {
        require(maxBytes > 0) { "Recording size limit must be positive" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("MP4 validation interrupted")
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.isSymbolicLink) invalid("a regular file without symlinks is required")
        val size = before.size()
        if (size <= 0 || size > maxBytes) invalid("file must contain between 1 and $maxBytes bytes")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != size) invalid("file changed before validation")
            Reader(channel, size).validate()
            if (channel.size() != size) invalid("file changed during validation")
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!after.isRegularFile || after.isSymbolicLink || after.size() != size ||
            after.lastModifiedTime() != before.lastModifiedTime() ||
            (before.fileKey() != null && after.fileKey() != before.fileKey())
        ) {
            invalid("file changed during validation")
        }
    }

    private class Reader(private val channel: FileChannel, private val fileSize: Long) {
        private var boxCount = 0
        private var hasFileType = false
        private var hasMovie = false
        private var hasMediaData = false
        private val tracks = mutableListOf<Track>()

        fun validate() {
            boxes(0, fileSize, "file", null, 0)
            if (!hasFileType) invalid("missing ftyp box")
            if (!hasMovie) invalid("missing moov box; recording may not be finalized")
            if (!hasMediaData) invalid("missing non-empty mdat box")
            if (tracks.none { it.handler == "vide" && it.positiveDuration == true && it.positiveDimensions == true }) {
                invalid("no video track with positive duration, timescale and dimensions")
            }
        }

        private fun boxes(start: Long, end: Long, parent: String, track: Track?, depth: Int) {
            if (depth > MAX_DEPTH) invalid("box nesting exceeds $MAX_DEPTH levels")
            var position = start
            while (position < end) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("MP4 validation interrupted")
                if (++boxCount > MAX_BOXES) invalid("box count exceeds $MAX_BOXES")
                val box = box(position, end, parent)
                when (box.type) {
                    "ftyp" -> {
                        if (parent != "file" || hasFileType) invalid("misplaced or duplicate ftyp box")
                        if (box.payloadSize < 8 || box.payloadSize % 4 != 0L) invalid("malformed ftyp payload")
                        hasFileType = true
                    }
                    "moov" -> {
                        if (parent != "file" || hasMovie) invalid("misplaced or duplicate moov box")
                        hasMovie = true
                        boxes(box.payload, box.end, box.type, null, depth + 1)
                    }
                    "mdat" -> {
                        if (parent != "file") invalid("misplaced mdat box")
                        hasMediaData = hasMediaData || box.payloadSize > 0
                    }
                    "trak" -> {
                        if (parent != "moov") invalid("trak must be a direct child of moov")
                        val current = Track()
                        tracks += current
                        boxes(box.payload, box.end, box.type, current, depth + 1)
                    }
                    "mdia" -> {
                        if (parent != "trak" || track == null || track.hasMedia) invalid("misplaced or duplicate mdia box")
                        track.hasMedia = true
                        boxes(box.payload, box.end, box.type, track, depth + 1)
                    }
                    "tkhd" -> {
                        if (parent != "trak" || track == null || track.positiveDimensions != null) {
                            invalid("misplaced or duplicate tkhd box")
                        }
                        track.positiveDimensions = dimensions(box)
                    }
                    "mdhd" -> {
                        if (parent != "mdia" || track == null || track.positiveDuration != null) {
                            invalid("misplaced or duplicate mdhd box")
                        }
                        track.positiveDuration = duration(box)
                    }
                    "hdlr" -> {
                        if (parent == "mdia") {
                            if (track == null || track.handler != null) invalid("duplicate media handler")
                            if (box.payloadSize < 24) invalid("truncated hdlr box")
                            track.handler = typeAt(box.payload + 8)
                        }
                    }
                    "meta" -> {
                        if (box.payloadSize < 4) invalid("truncated meta full-box header")
                        boxes(box.payload + 4, box.end, box.type, track, depth + 1)
                    }
                    in CONTAINERS -> boxes(box.payload, box.end, box.type, track, depth + 1)
                }
                position = box.end
            }
        }

        private fun box(position: Long, parentEnd: Long, parent: String): Box {
            if (parentEnd - position < 8) invalid("truncated box header at $position")
            val header = read(position, 8)
            val size32 = header.int.toLong() and UINT32_MAX
            val typeBytes = ByteArray(4).also { header.get(it) }
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var headerSize = 8L
            val size = when (size32) {
                0L -> {
                    // The zero-size form extends to EOF, not to an arbitrary container boundary.
                    if (parent != "file") invalid("zero-size nested box at $position")
                    fileSize - position
                }
                1L -> {
                    if (parentEnd - position < 16) invalid("truncated extended box header at $position")
                    headerSize = 16
                    read(position + 8, 8).long.also {
                        if (it < 0) invalid("box size exceeds the signed file offset range")
                    }
                }
                else -> size32
            }
            if (size < headerSize || size > parentEnd - position) invalid("box $type exceeds its parent or has an invalid size")
            // The subtraction checks above make both additions safe from signed overflow.
            return Box(type, position + headerSize, position + size)
        }

        private fun duration(box: Box): Boolean {
            val version = version(box)
            val required = if (version == 0) 24 else 36
            if (box.payloadSize < required) invalid("truncated mdhd box")
            val offset = box.payload + if (version == 0) 12 else 20
            val timeScale = unsignedInt(offset)
            val duration = if (version == 0) unsignedInt(offset + 4) else read(offset + 4, 8).long
            // All-ones represents an unknown duration. A finalized recording needs a known duration.
            val known = duration > 0 && !(version == 0 && duration == UINT32_MAX)
            return timeScale > 0 && known
        }

        private fun dimensions(box: Box): Boolean {
            val version = version(box)
            val required = if (version == 0) 84 else 96
            if (box.payloadSize < required) invalid("truncated tkhd box")
            val offset = box.payload + if (version == 0) 76 else 88
            // tkhd stores display width and height in unsigned 16.16 fixed point.
            return unsignedInt(offset) >= 65536 && unsignedInt(offset + 4) >= 65536
        }

        private fun version(box: Box): Int {
            if (box.payloadSize < 4) invalid("truncated ${box.type} full-box header")
            val version = read(box.payload, 1).get().toInt() and 0xff
            if (version !in 0..1) invalid("unsupported ${box.type} version $version")
            return version
        }

        private fun unsignedInt(position: Long): Long = read(position, 4).int.toLong() and UINT32_MAX

        private fun typeAt(position: Long): String = String(read(position, 4).array(), Charsets.ISO_8859_1)

        private fun read(position: Long, count: Int): ByteBuffer {
            if (position < 0 || count.toLong() > fileSize - position) invalid("truncated field at $position")
            val buffer = ByteBuffer.allocate(count).order(ByteOrder.BIG_ENDIAN)
            var offset = position
            while (buffer.hasRemaining()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("MP4 validation interrupted")
                val read = channel.read(buffer, offset)
                if (read <= 0) invalid("truncated field at $offset")
                offset += read
            }
            return buffer.flip()
        }
    }

    private data class Box(val type: String, val payload: Long, val end: Long) {
        val payloadSize: Long get() = end - payload
    }

    private class Track {
        var hasMedia = false
        var handler: String? = null
        var positiveDuration: Boolean? = null
        var positiveDimensions: Boolean? = null
    }

    private const val UINT32_MAX = 0xffff_ffffL
    private const val MAX_DEPTH = 16
    private const val MAX_BOXES = 100_000
    private val CONTAINERS = setOf("minf", "stbl", "edts", "dinf", "udta", "mvex", "moof", "traf", "mfra", "tref", "sinf", "schi", "meco", "hnti", "hinf")

    private fun invalid(message: String): Nothing = throw InvalidMp4RecordingException(message)
}
