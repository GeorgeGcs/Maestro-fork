package maestro.harmonyos.driver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HarmonyOSHostFrameRecordingTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `concat resolves frames inside a recording directory containing spaces and a hash`() {
        val directory = Files.createDirectory(temp.resolve("recording #1"))
        val ffmpeg = temp.resolve("encoder tools").resolve("ffmpeg")
        val manifest = directory.resolve("frames.ffconcat")
        val output = directory.resolve("recording.mp4")
        val process = hostFrameEncoder(ffmpeg, manifest, output, 32, 32)
        val args = process.command()
        assertEquals(directory, process.directory().toPath())
        assertEquals(ffmpeg.toString(), args.first())
        assertEquals("frames.ffconcat", args[args.indexOf("-i") + 1])
        assertEquals("recording.mp4", args.last())
        assertThrows(IllegalArgumentException::class.java) {
            hostFrameEncoder(ffmpeg, manifest, temp.resolve("recording.mp4"), 32, 32)
        }
    }
}
