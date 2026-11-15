package maestro.test

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import maestro.DeviceUnreachableException
import maestro.Maestro
import maestro.MaestroException
import maestro.js.JsEvaluationException
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import maestro.orchestra.Orchestra
import maestro.orchestra.RetryCommand
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.ExecutionContext
import maestro.orchestra.debug.ExecutionEvent
import maestro.orchestra.debug.ExecutionTraceEnd
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.debug.RetryExecutionEvent
import maestro.test.drivers.FakeDriver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Exercises the real executor and Maestro; only the device boundary is fake. */
class RetryTraceIntegrationTest {
    private class Recorder : OrchestraListener {
        data class Step(val command: MaestroCommand, val context: ExecutionContext)
        val started = mutableListOf<Step>()
        val finished = mutableListOf<Pair<ExecutionContext, CommandOutcome>>()
        val interrupted = mutableListOf<Pair<ExecutionContext, Throwable>>()
        val retries = mutableListOf<RetryExecutionEvent>()
        val sequences = mutableListOf<Long>()
        var end: ExecutionTraceEnd? = null
        var legacyStarts = 0
        var legacyFinishes = 0
        override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) { legacyStarts++ }
        override fun onCommandFinished(cmd: MaestroCommand, outcome: CommandOutcome, startedAt: Long, finishedAt: Long) { legacyFinishes++ }
        override fun onCommandExecutionStarted(cmd: MaestroCommand, context: ExecutionContext, depth: Int, event: ExecutionEvent) {
            started += Step(cmd, context)
            sequences += event.sequence
            super.onCommandExecutionStarted(cmd, context, depth, event)
        }
        override fun onCommandExecutionFinished(cmd: MaestroCommand, context: ExecutionContext, outcome: CommandOutcome,
            startedAt: Long, finishedAt: Long, event: ExecutionEvent) {
            finished += context to outcome
            sequences += event.sequence
            super.onCommandExecutionFinished(cmd, context, outcome, startedAt, finishedAt, event)
        }
        override fun onCommandExecutionInterrupted(cmd: MaestroCommand, context: ExecutionContext, error: Throwable,
            startedAt: Long, finishedAt: Long, event: ExecutionEvent) {
            interrupted += context to error
            sequences += event.sequence
        }
        override fun onRetryExecution(event: RetryExecutionEvent) { retries += event; sequences += event.event.sequence }
        override fun onExecutionTraceEnd(end: ExecutionTraceEnd) { this.end = end; sequences += end.event.sequence }
        fun assertClosed() {
            assertThat(end!!.activeCommands).isEqualTo(0)
            assertThat(end!!.activeRetries).isEqualTo(0)
            assertThat(sequences).containsExactlyElementsIn((0L until end!!.completionSequence).toList()).inOrder()
            assertThat(end!!.expectedEventCount).isEqualTo(sequences.size + 1L)
            assertThat(legacyStarts).isEqualTo(started.size)
            assertThat(legacyFinishes).isEqualTo(finished.size)
            assertThat(started.size).isEqualTo(finished.size + interrupted.size)
        }
    }

    private fun back() = MaestroCommand(backPressCommand = BackPressCommand())
    private fun script(value: String) = MaestroCommand(evalScriptCommand = EvalScriptCommand(value))
    private fun retry(commands: List<MaestroCommand>, limit: String = "1", config: MaestroConfig? = null) =
        MaestroCommand(retryCommand = RetryCommand(limit, commands, config))

    private fun run(driver: FakeDriver, commands: List<MaestroCommand>, observer: Recorder,
        other: OrchestraListener? = null): Orchestra.FlowResult {
        driver.open()
        return Maestro(driver).use { maestro ->
            runBlocking { Orchestra(maestro, lookupTimeoutMs = 0, optionalLookupTimeoutMs = 0,
                listeners = listOfNotNull(other, observer)).runFlow(commands) }
        }
    }

    @Test
    fun `nested retries keep immutable contexts and create a new inner invocation on outer retry`() {
        val leaf = back()
        var calls = 0
        val driver = object : FakeDriver() {
            override fun backPress() {
                if (++calls == 1) throw MaestroException.UnableToLaunchApp("first attempt")
                super.backPress()
            }
        }
        val recorder = Recorder()
        val inner = retry(listOf(leaf), "0")
        val outer = retry(listOf(inner))
        val tail = script("1")
        assertThat(run(driver, listOf(outer, tail), recorder).success).isTrue()

        val leaves = recorder.started.filter { it.command === leaf }
        assertThat(leaves).hasSize(2)
        assertThat(leaves.map { it.context.executionSequence }.distinct()).hasSize(2)
        assertThat(leaves.map { it.context.retryContext.map { frame -> frame.tryNo } })
            .containsExactly(listOf(1, 1), listOf(2, 1)).inOrder()
        assertThat(leaves.map { it.context.retryContext.first().invocationId }.distinct()).hasSize(1)
        assertThat(leaves.map { it.context.retryContext.last().invocationId }.distinct()).hasSize(2)
        assertThat(recorder.started.single { it.command === outer }.context.retryContext).isEmpty()
        assertThat(recorder.started.single { it.command === tail }.context.retryContext).isEmpty()
        assertThrows<UnsupportedOperationException> {
            (leaves.first().context.retryContext as MutableList<*>).clear()
        }
        recorder.assertClosed()
    }

    @Test
    fun `subflow hooks stay inside their attempt and top level completion is outside retry`() {
        val start = script("1")
        val complete = script("2")
        val topComplete = script("3")
        val leaf = back()
        var calls = 0
        val driver = object : FakeDriver() {
            override fun backPress() {
                if (++calls == 1) throw MaestroException.UnableToLaunchApp("recover")
                super.backPress()
            }
        }
        val recorder = Recorder()
        val config = MaestroConfig(onFlowStart = MaestroOnFlowStart(listOf(start)),
            onFlowComplete = MaestroOnFlowComplete(listOf(complete)))
        val top = MaestroCommand(applyConfigurationCommand = ApplyConfigurationCommand(
            MaestroConfig(onFlowComplete = MaestroOnFlowComplete(listOf(topComplete)))))
        assertThat(run(driver, listOf(top, retry(listOf(leaf), config = config)), recorder).success).isTrue()
        for ((command, phase) in listOf(start to "onFlowStart", complete to "onFlowComplete")) {
            val hooks = recorder.started.filter { it.command === command }
            assertThat(hooks.map { it.context.phase }).containsExactly(phase, phase)
            assertThat(hooks.map { it.context.retryContext.single().tryNo }).containsExactly(1, 2).inOrder()
        }
        assertThat(recorder.started.single { it.command === topComplete }.context.retryContext).isEmpty()
        recorder.assertClosed()
    }

    @Test
    fun `cancellation and connection loss close contexts without retrying or fabricating Failed`() {
        for (failure in listOf(CancellationException("cancelled"), DeviceUnreachableException("back", RuntimeException("lost")))) {
            var calls = 0
            val driver = object : FakeDriver() {
                override fun backPress() { calls++; throw failure }
            }
            val recorder = Recorder()
            val thrown = assertThrows<Throwable> { run(driver, listOf(retry(listOf(back()), "3")), recorder) }
            // Coroutine stack-trace recovery may copy CancellationException at an IO boundary.
            assertThat(thrown.javaClass).isEqualTo(failure.javaClass)
            assertThat(generateSequence(thrown) { it.cause }.take(16).any { it === failure }).isTrue()
            assertThat(calls).isEqualTo(1)
            assertThat(recorder.retries.filter { it.kind == "retryTryStarted" }).hasSize(1)
            assertThat(recorder.interrupted).hasSize(2)
            assertThat(recorder.finished).isEmpty()
            recorder.assertClosed()
        }
    }

    @Test
    fun `Graal script errors propagate out of retry without another attempt or tail command`() {
        var calls = 0
        val driver = object : FakeDriver() {
            override fun backPress() { calls++; super.backPress() }
        }
        val broken = script("\${(() => { throw new Error('retry-graal-fixture') })()}")
        val innerTail = back()
        val outerTail = back()
        val block = retry(listOf(back(), broken, innerTail), "3")
        val recorder = Recorder()

        val thrown = assertThrows<JsEvaluationException> {
            run(driver, listOf(block, outerTail), recorder)
        }

        assertThat(thrown.message).contains("retry-graal-fixture")
        assertThat(thrown).isNotInstanceOf(MaestroException::class.java)
        assertThat(calls).isEqualTo(1)
        assertThat(recorder.started.filter { it.command === broken }).hasSize(1)
        assertThat(recorder.started.none { it.command === innerTail || it.command === outerTail }).isTrue()
        assertThat(recorder.retries.map { it.kind }).containsExactly(
            "retryBlockStarted", "retryTryStarted", "retryTryFinished", "retryBlockFinished",
        ).inOrder()
        assertThat(recorder.retries.single { it.kind == "retryTryStarted" }.tryNo).isEqualTo(1)
        val failedTry = recorder.retries.single { it.kind == "retryTryFinished" }
        assertThat(failedTry.outcome).isEqualTo("interrupted")
        assertThat(failedTry.error).isInstanceOf(JsEvaluationException::class.java)
        assertThat(generateSequence<Throwable>(thrown) { it.cause }.take(16).toList()).contains(failedTry.error)
        assertThat(recorder.retries.last().outcome).isEqualTo("interrupted")
        assertThat(recorder.retries.last().error).isSameInstanceAs(failedTry.error)
        val scriptContext = recorder.started.single { it.command === broken }.context
        assertThat(scriptContext.retryContext.map { it.tryNo }).containsExactly(1)
        val scriptOutcome = recorder.finished.single { it.first == scriptContext }.second
        assertThat(scriptOutcome).isInstanceOf(CommandOutcome.Failed::class.java)
        assertThat((scriptOutcome as CommandOutcome.Failed).error).isSameInstanceAs(failedTry.error)
        recorder.assertClosed()
    }

    @Test
    fun `listener failures cannot corrupt executor context or prevent other observers receiving events`() {
        val thrower = object : OrchestraListener {
            override fun onCommandExecutionStarted(cmd: MaestroCommand, context: ExecutionContext, depth: Int, event: ExecutionEvent) {
                throw IllegalStateException("observer failed")
            }
            override fun onRetryExecution(event: RetryExecutionEvent) { throw IllegalStateException("observer failed") }
        }
        val recorder = Recorder()
        assertThat(run(FakeDriver(), listOf(retry(listOf(back())), back()), recorder, thrower).success).isTrue()
        assertThat(recorder.started.last().context.retryContext).isEmpty()
        recorder.assertClosed()
    }

    @Test
    fun `exhausted retry reports every actual attempt and original failure`() {
        val failure = MaestroException.UnableToLaunchApp("exhausted")
        val driver = object : FakeDriver() { override fun backPress() { throw failure } }
        val recorder = Recorder()
        assertThat(assertThrows<MaestroException.UnableToLaunchApp> {
            run(driver, listOf(retry(listOf(back()), "2")), recorder)
        }).isSameInstanceAs(failure)
        assertThat(recorder.retries.filter { it.kind == "retryTryStarted" }.map { it.tryNo }).containsExactly(1, 2, 3).inOrder()
        assertThat(recorder.retries.last().outcome).isEqualTo("failed")
        recorder.assertClosed()
    }
}
