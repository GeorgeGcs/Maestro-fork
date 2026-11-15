package maestro.cli.harmonyos

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.orchestra.debug.ExecutionContext
import maestro.orchestra.debug.ExecutionEvent
import maestro.orchestra.debug.ExecutionTraceArtifacts
import maestro.orchestra.debug.ExecutionTraceEnd
import maestro.orchestra.debug.RetryExecutionEvent
import maestro.orchestra.debug.RetryExecutionFrame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class HarmonyOsRetryTraceTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    private inner class Fixture(failOn: String? = null, failRefresh: Boolean = false) {
        var sequence = 0L
        val trace = HarmonyOsRetryTrace("run", "attempt", "main.yaml", "a".repeat(64)) { path ->
            val delegate = Files.newBufferedWriter(path)
            object : Writer() {
                override fun write(chars: CharArray, offset: Int, length: Int) {
                    val text = String(chars, offset, length)
                    if (failOn != null && text.contains("\"event\":\"" + failOn + "\"")) throw IOException("injected trace failure")
                    delegate.write(chars, offset, length)
                }
                override fun flush() = delegate.flush()
                override fun close() = delegate.close()
            }
        }
        init {
            trace.ready(ExecutionTraceArtifacts({ name, _ -> directory.resolve(name).toFile() }, {
                if (failRefresh) throw IOException("injected manifest failure")
            }))
        }
        fun event() = ExecutionEvent(sequence++, sequence * 100)
        fun context(index: Int, retry: List<RetryExecutionFrame> = emptyList()) =
            ExecutionContext(index, if (retry.isEmpty()) null else 0, retry, null, "body", emptyList())
        fun start(index: Int, retry: List<RetryExecutionFrame> = emptyList()) = context(index, retry).also {
            trace.stepStarted("step-$index", it, mapOf("sourcePath" to "sources/a.yaml",
                "sourceIdentity" to directory.resolve("a.yaml").toAbsolutePath().normalize().toString(),
                "sourceOrigin" to "yaml", "commandIndex" to 1), event())
        }
        fun finish(context: ExecutionContext, outcome: String = "Completed") =
            trace.stepFinished("step-" + context.executionSequence, context, outcome, null, event())
        fun retry(kind: String, tryNo: Int? = null, outcome: String? = null, optional: Boolean = false) =
            trace.retry(RetryExecutionEvent(event(), kind, "retry-1", 0, emptyList(), 1, optional, tryNo, outcome))
        fun seal(lastStep: Int, success: Boolean? = true) {
            val ending = event()
            trace.ended(ExecutionTraceEnd(ending, sequence, sequence + 1, lastStep, 0, 0, success, null))
            trace.seal(success)
        }
        fun summary() = mapper.readTree(directory.resolve("retry-summary.json").toFile())
        fun events() = Files.readAllLines(directory.resolve("retry-trace.jsonl")).map { mapper.readTree(it) }
    }

    @Test
    fun `same source has separate steps and retry contexts with a verifiable sealed hash`() {
        val fixture = Fixture()
        val owner = fixture.start(0)
        fixture.retry("retryBlockStarted", optional = true)
        fixture.retry("retryTryStarted", 1, optional = true)
        val first = fixture.start(1, listOf(RetryExecutionFrame("retry-1", 0, 1)))
        fixture.finish(first, "Failed")
        fixture.retry("retryTryFinished", 1, "failed", optional = true)
        fixture.retry("retryScheduled", 2, optional = true)
        fixture.retry("retryTryStarted", 2, optional = true)
        val second = fixture.start(2, listOf(RetryExecutionFrame("retry-1", 0, 2)))
        fixture.finish(second)
        fixture.retry("retryTryFinished", 2, "completed", optional = true)
        fixture.retry("retryBlockFinished", outcome = "completed", optional = true)
        fixture.finish(owner)
        fixture.seal(2)

        val summary = fixture.summary()
        assertThat(summary["schemaVersion"].asInt()).isEqualTo(2)
        assertThat(fixture.events().first()["schemaVersion"].asInt()).isEqualTo(2)
        assertThat(summary["state"].asText()).isEqualTo("complete")
        assertThat(summary["firstPassStatus"].asText()).isEqualTo("not_passed")
        assertThat(summary["optionalRetryUsed"].asBoolean()).isTrue()
        val steps = fixture.events().filter { it["event"].asText() == "stepStarted" }.drop(1)
        assertThat(steps.map { it["sourcePath"].asText() }.distinct()).hasSize(1)
        assertThat(steps.map { it["sourceIdentity"].asText() }.distinct()).containsExactly(
            directory.resolve("a.yaml").toAbsolutePath().normalize().toString())
        fixture.events().filter { it["event"].asText() in setOf("stepFinished", "stepInterrupted") }.forEach { terminal ->
            val start = fixture.events().single { it["event"].asText() == "stepStarted" && it["stepId"] == terminal["stepId"] }
            assertThat(terminal["sourceIdentity"]).isEqualTo(start["sourceIdentity"])
        }
        assertThat(steps.map { it["stepId"].asText() }).containsExactly("step-1", "step-2").inOrder()
        assertThat(steps.map { it["retryContext"][0]["tryNo"].asInt() }).containsExactly(1, 2).inOrder()
        assertThat(steps.map { it["retryContext"][0]["retryCommandStepId"].asText() }).containsExactly("step-0", "step-0")
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve("retry-trace.jsonl")))
            .joinToString("") { "%02x".format(it) }
        assertThat(summary["traceSha256"].asText()).isEqualTo(hash)
        assertThat(summary["eventCount"].asLong()).isEqualTo(summary["expectedEventCount"].asLong())
    }

    @Test
    fun `warnings and skips remain distinct without inventing retries`() {
        val fixture = Fixture()
        fixture.finish(fixture.start(0), "Warned")
        fixture.finish(fixture.start(1), "Skipped")
        fixture.seal(1)
        assertThat(fixture.summary()["firstPassStatus"].asText()).isEqualTo("passed")
        assertThat(fixture.summary()["outcomeCounts"]["Warned"].asInt()).isEqualTo(1)
        assertThat(fixture.summary()["outcomeCounts"]["Skipped"].asInt()).isEqualTo(1)
        assertThat(fixture.summary()["retryUsed"].asBoolean()).isFalse()
    }

    @Test
    fun `missing completion notification never means zero retries`() {
        val fixture = Fixture()
        fixture.finish(fixture.start(0))
        fixture.trace.seal(true)
        assertThat(fixture.summary()["firstPassStatus"].asText()).isEqualTo("unknown")
        assertThat(fixture.summary()["retryUsed"].isNull).isTrue()
        assertThat(fixture.events().any { it["event"].asText() == "traceCompleted" }).isFalse()
    }

    @Test
    fun `a swallowed write failure is sticky even when the flow later passes`() {
        val fixture = Fixture(failOn = "stepFinished")
        fixture.finish(fixture.start(0))
        val original = fixture.trace.traceFailure
        fixture.seal(0)
        assertThat(original).isInstanceOf(IOException::class.java)
        assertThat(fixture.trace.traceFailure).isSameInstanceAs(original)
        assertThat(fixture.summary()["state"].asText()).isEqualTo("incomplete")
        assertThat(fixture.summary()["firstPassStatus"].asText()).isEqualTo("unknown")
        assertThat(fixture.events().any { it["event"].asText() == "traceCompleted" }).isFalse()
    }

    @Test
    fun `missing engine event and reused step identity cannot seal complete`() {
        val fixture = Fixture()
        fixture.sequence++
        fixture.start(0)
        fixture.seal(0)
        assertThat(fixture.summary()["state"].asText()).isEqualTo("incomplete")
        assertThat(fixture.summary()["retryUsed"].isNull).isTrue()
    }

    @Test
    fun `publication failure cannot leave a complete summary`() {
        val fixture = Fixture(failRefresh = true)
        fixture.finish(fixture.start(0))
        fixture.seal(0)
        assertThat(fixture.summary()["state"].asText()).isEqualTo("incomplete")
        assertThat(fixture.summary()["firstPassStatus"].asText()).isEqualTo("unknown")
    }

    @Test
    fun `completed interruption records are complete evidence of an unsuccessful execution`() {
        val fixture = Fixture()
        fixture.finish(fixture.start(0), "Interrupted")
        fixture.seal(0, null)
        assertThat(fixture.summary()["state"].asText()).isEqualTo("complete")
        assertThat(fixture.summary()["firstPassStatus"].asText()).isEqualTo("not_passed")
        val interrupted = fixture.events().single { it["event"].asText() == "stepInterrupted" }
        assertThat(interrupted["outcome"].isNull).isTrue()
        assertThat(interrupted["sourceIdentity"].asText()).isEqualTo(
            directory.resolve("a.yaml").toAbsolutePath().normalize().toString())
    }

    @Test
    fun `v2 source identity cannot silently fall back to content identity`() {
        val fixture = Fixture()
        fixture.trace.stepStarted("step-0", fixture.context(0),
            mapOf("sourcePath" to "sources/a.yaml", "sourceOrigin" to "yaml", "commandIndex" to 1), fixture.event())
        fixture.trace.seal(true)
        assertThat(fixture.summary()["schemaVersion"].asInt()).isEqualTo(2)
        assertThat(fixture.summary()["state"].asText()).isEqualTo("incomplete")
        assertThat(fixture.events().any { it["event"].asText() == "stepStarted" }).isFalse()
    }
}
