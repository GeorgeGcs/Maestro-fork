package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import maestro.Driver
import maestro.Maestro
import maestro.ScreenRecording
import maestro.debuglog.RecordingDiagnosticContext
import maestro.debuglog.ScopedLogCapture
import maestro.utils.TempFileHandler
import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import okio.sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException
import java.time.Instant

class ScreenRecordingOutputTest {
    @Test
    fun `diagnostic context is bound once to its exact output without changing bytes`() = withOutput { file, sink ->
        val lines = mutableListOf<String>()
        val output = RecordingOutputSink(sink, file, lines::add)
        assertThat(lines).isEmpty()
        val id = output.recordingDiagnosticContext()
        assertThat(output.recordingDiagnosticContext()).isEqualTo(id)
        assertThat(id).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        assertThat(lines).hasSize(1)
        val binding = jacksonObjectMapper().readTree(lines.single().removePrefix("MAESTRO_RECORDING_OUTPUT "))
        assertThat(binding["schemaVersion"].intValue()).isEqualTo(1)
        assertThat(binding["contextId"].asText()).isEqualTo(id)
        assertThat(binding["outputPath"].asText()).isEqualTo(file.toPath().toAbsolutePath().normalize().toString())
        assertThat(binding["pathAvailable"].booleanValue()).isTrue()
        output.write(Buffer().writeUtf8("video"), 5)
        output.close()
        output.close()
        assertThat(file.readText()).isEqualTo("video")
        assertThat(sink.closeCalls).isEqualTo(1)
    }

    @Test
    fun `failed binding logging is not retried and cannot fail output writes`() = withOutput { file, sink ->
        var calls = 0
        val output = RecordingOutputSink(sink, file) { calls++; throw IllegalStateException("logger failed") }
        val id = output.recordingDiagnosticContext()
        assertThat(output.recordingDiagnosticContext()).isEqualTo(id)
        assertThat(calls).isEqualTo(1)
        output.write(Buffer().writeUtf8("video"), 5)
        output.close()
        assertThat(file.readText()).isEqualTo("video")
    }

    @Test
    fun `overlong output paths are explicitly unavailable without changing the sink`() {
        val bytes = Buffer()
        val lines = mutableListOf<String>()
        val output = RecordingOutputSink(bytes, File("x".repeat(4097)), lines::add)
        val id = output.recordingDiagnosticContext()
        assertThat(output.recordingDiagnosticContext()).isEqualTo(id)
        assertThat(lines).hasSize(1)
        val binding = jacksonObjectMapper().readTree(lines.single().substringAfter("MAESTRO_RECORDING_OUTPUT "))
        assertThat(binding["pathAvailable"].booleanValue()).isFalse()
        assertThat(binding["outputPath"].isNull).isTrue()
        output.write(Buffer().writeUtf8("video"), 5)
        output.flush()
        assertThat(bytes.readUtf8()).isEqualTo("video")
        output.close()
        output.close()
    }

    @Test
    fun `backend context reaches the real scoped log with a distinct id per output`() = TempFileHandler().use { files ->
        val log = files.createTempFile("recording-bindings-", ".log")
        val ids = mutableListOf<String>()
        ScopedLogCapture.start(log).use {
            repeat(2) {
                withOutput { file, sink ->
                    val driver = mockk<Driver>()
                    every { driver.startScreenRecording(any()) } answers {
                        val context = firstArg<Sink>() as RecordingDiagnosticContext
                        ids += checkNotNull(context.recordingDiagnosticContext())
                        object : ScreenRecording {
                            override val startedAt = Instant.EPOCH
                            override fun close() = Unit
                        }
                    }
                    runBlocking { Maestro(driver).startScreenRecordingInto(sink, file) }!!.close()
                }
            }
        }
        val bindings = log.readLines().filter { it.contains("MAESTRO_RECORDING_OUTPUT ") }
            .map { jacksonObjectMapper().readTree(it.substringAfter("MAESTRO_RECORDING_OUTPUT ")) }
        assertThat(ids.distinct()).hasSize(2)
        assertThat(bindings.map { it["contextId"].asText() }).containsExactlyElementsIn(ids).inOrder()
    }

    @Test
    fun `successful close flushes and releases the output exactly once`() = withOutput { file, sink ->
        val fixture = RecordingFixture { out -> out.write(Buffer().writeUtf8("recording"), 9) }
        val recording = runBlocking { fixture.maestro.startScreenRecordingInto(sink, file) }!!

        assertThat(recording.startedAt).isEqualTo(fixture.startedAt)
        assertThat(sink.closeCalls).isEqualTo(0)
        recording.close()
        recording.close()

        assertThat(fixture.stopCalls).isEqualTo(1)
        assertThat(sink.closeCalls).isEqualTo(1)
        assertThat(file.readText()).isEqualTo("recording")
    }

    @Test
    fun `stop failure still closes the output and preserves the original error`() = withOutput { file, sink ->
        val failure = IOException("stop failed")
        val fixture = RecordingFixture { throw failure }
        val recording = runBlocking { fixture.maestro.startScreenRecordingInto(sink, file) }!!

        assertThat(assertThrows<IOException> { recording.close() }).isSameInstanceAs(failure)
        recording.close()

        assertThat(fixture.stopCalls).isEqualTo(1)
        assertThat(sink.closeCalls).isEqualTo(1)
    }

    @Test
    fun `output close failure is suppressed on the original stop error`() = withOutput { file, sink ->
        val stopFailure = IOException("stop failed")
        val closeFailure = IOException("output close failed")
        sink.closeFailure = closeFailure
        val fixture = RecordingFixture { throw stopFailure }
        val recording = runBlocking { fixture.maestro.startScreenRecordingInto(sink, file) }!!

        val error = assertThrows<IOException> { recording.close() }

        assertThat(error).isSameInstanceAs(stopFailure)
        assertThat(error.suppressed).asList().containsExactly(closeFailure)
        assertThat(sink.closeCalls).isEqualTo(1)
    }

    @Test
    fun `output close failure is reported when stopping succeeded`() = withOutput { file, sink ->
        val failure = IOException("output close failed")
        sink.closeFailure = failure
        val fixture = RecordingFixture()
        val recording = runBlocking { fixture.maestro.startScreenRecordingInto(sink, file) }!!

        assertThat(assertThrows<IOException> { recording.close() }).isSameInstanceAs(failure)
        recording.close()

        assertThat(fixture.stopCalls).isEqualTo(1)
        assertThat(sink.closeCalls).isEqualTo(1)
    }

    @Test
    fun `a backend can close its sink without closing the underlying output twice`() = withOutput { file, sink ->
        val fixture = RecordingFixture { out ->
            out.write(Buffer().writeUtf8("recording"), 9)
            out.close()
        }
        val recording = runBlocking { fixture.maestro.startScreenRecordingInto(sink, file) }!!

        recording.close()
        recording.close()

        assertThat(sink.closeCalls).isEqualTo(1)
        assertThat(file.readText()).isEqualTo("recording")
    }

    @Test
    fun `start failure closes and removes the output without replacing the start error`() = withOutput { file, sink ->
        val failure = IOException("start failed")
        sink.closeFailure = IOException("output close failed")
        val driver = mockk<Driver>()
        every { driver.startScreenRecording(any()) } throws failure

        val error = assertThrows<IOException> {
            runBlocking { Maestro(driver).startScreenRecordingInto(sink, file) }
        }

        // Coroutines may copy IOException while recovering its stack; the original
        // start failure must remain in the cause chain rather than a close failure.
        assertThat(generateSequence<Throwable>(error) { it.cause }.toList()).contains(failure)
        assertThat(sink.closeCalls).isEqualTo(1)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `duplicate start removes only the unused output and keeps the first recording open`() = withOutput { file, sink ->
        val fixture = RecordingFixture()
        val recording = runBlocking { fixture.maestro.startScreenRecordingInto(sink, file) }!!
        try {
            withOutput { unusedFile, unusedSink ->
                assertThat(runBlocking { fixture.maestro.startScreenRecordingInto(unusedSink, unusedFile) }).isNull()
                assertThat(unusedSink.closeCalls).isEqualTo(1)
                assertThat(unusedFile.exists()).isFalse()
            }
            assertThat(sink.closeCalls).isEqualTo(0)
            assertThat(fixture.stopCalls).isEqualTo(0)
            verify(exactly = 1) { fixture.driver.startScreenRecording(any()) }
        } finally {
            recording.close()
        }
        assertThat(sink.closeCalls).isEqualTo(1)
    }

    private fun withOutput(block: (File, OutputSink) -> Unit) = TempFileHandler().use { files ->
        val file = files.createTempFile("recording-output-", ".mp4")
        val sink = OutputSink(file.sink().buffer())
        try {
            block(file, sink)
        } finally {
            if (sink.closeCalls == 0) sink.close()
        }
    }

    private class OutputSink(delegate: Sink) : ForwardingSink(delegate) {
        var closeCalls = 0
        var closeFailure: IOException? = null
        override fun close() {
            closeCalls++
            super.close()
            closeFailure?.let { throw it }
        }
    }

    private class RecordingFixture(stop: (Sink) -> Unit = {}) {
        val driver = mockk<Driver>()
        val maestro = Maestro(driver)
        val startedAt: Instant = Instant.ofEpochMilli(1_700_000_000_000)
        var stopCalls = 0

        init {
            every { driver.startScreenRecording(any()) } answers {
                val sink = firstArg<Sink>()
                object : ScreenRecording {
                    override val startedAt = this@RecordingFixture.startedAt
                    override fun close() {
                        stopCalls++
                        stop(sink)
                    }
                }
            }
        }
    }
}
