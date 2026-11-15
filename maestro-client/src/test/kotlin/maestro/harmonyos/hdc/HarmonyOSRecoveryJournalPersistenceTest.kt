package maestro.harmonyos.hdc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HarmonyOSRecoveryJournalPersistenceTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private fun file() = directory.resolve("journal.json")
    private fun state() = mapper.readTree(file().toFile())
    private val unavailable = HdcClient("fake", HdcCommandRunner { _, _ -> error("No device query expected") })

    private inner class Writer {
        var fail: (JsonNode) -> Boolean = { false }
        val failure = IOException("Injected journal persistence failure")
        private val real = HarmonyOSJournalFileWriter()
        fun write(path: Path, bytes: ByteArray) {
            if (fail(mapper.readTree(bytes))) throw failure
            real.write(path, bytes)
        }
    }
    private fun create(writer: Writer) = HarmonyOSRecoveryJournal.create(file(), "fixture-target", UUID.randomUUID().toString(), writer::write)

    @Test fun `failed shell registration never executes or survives close as pending`() {
        val writer = Writer()
        val journal = create(writer)
        var failed = false
        writer.fail = { it["operations"].size() == 1 && !failed.also { failed = true } }
        var dispatched = 0
        assertThat(assertThrows(IOException::class.java) {
            journal.shell(listOf("uitest", "dumpLayout"), true) { dispatched++; error("Must not execute") }
        }).isSameInstanceAs(writer.failure)
        assertThat(dispatched).isEqualTo(0)
        assertThat(state()["operations"].size()).isEqualTo(0)
        assertThat(state()["rootRemoved"].asBoolean()).isTrue()
        journal.close()
        HarmonyOSRecoveryJournal.open(file()).use { assertThat(it.recover(unavailable).state).isEqualTo("complete") }
    }

    @Test fun `failed registration retains previous pending operation and root ownership`() {
        val writer = Writer()
        create(writer).use { journal ->
            assertThrows(HdcException::class.java) { journal.shell(listOf("uitest", "dumpLayout"), true) { HdcCommandResult(0, "no proof", "") } }
            val oldId = state()["operations"][0]["id"].asText()
            var failed = false
            writer.fail = { it["operations"].size() == 2 && !failed.also { failed = true } }
            assertThrows(IOException::class.java) { journal.shell(listOf("uitest", "uiInput"), true) { error("Must not execute") } }
            assertThat(state()["operations"].size()).isEqualTo(1)
            assertThat(state()["operations"][0]["id"].asText()).isEqualTo(oldId)
            assertThat(state()["rootRemoved"].asBoolean()).isFalse()
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
        }
    }

    @Test fun `failed standalone host registration removes call and active count before close`() {
        val writer = Writer()
        val journal = create(writer)
        var failed = false
        writer.fail = { it["hostCalls"].size() == 1 && !failed.also { failed = true } }
        var entered = false
        assertThat(assertThrows(IOException::class.java) { journal.hostCall { entered = true; Unit } }).isSameInstanceAs(writer.failure)
        assertThat(entered).isFalse()
        assertThat(journal.snapshot().issues.map { it.code }).doesNotContain("HOST_CALL_ACTIVE")
        assertThat(state()["hostCalls"].size()).isEqualTo(0)
        journal.close()
        HarmonyOSRecoveryJournal.open(file()).use { assertThat(it.recover(unavailable).state).isEqualTo("complete") }
    }

    @Test fun `nested host registration failure also rolls back the already committed shell operation`() {
        val writer = Writer()
        val journal = create(writer)
        var failed = false
        writer.fail = { it["hostCalls"].size() == 1 && !failed.also { failed = true } }
        val dispatches = AtomicInteger()
        val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> dispatches.incrementAndGet(); error("Must not dispatch") }).withRecoveryJournal(journal)
        assertThat(assertThrows(IOException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }).isSameInstanceAs(writer.failure)
        assertThat(dispatches.get()).isEqualTo(0)
        assertThat(state()["operations"].size()).isEqualTo(0)
        assertThat(state()["hostCalls"].size()).isEqualTo(0)
        assertThat(state()["rootRemoved"].asBoolean()).isTrue()
        journal.close()
        HarmonyOSRecoveryJournal.open(file()).use { assertThat(it.recover(unavailable).state).isEqualTo("complete") }
    }

    @Test fun `failed rollback remains unknown and forbids new normal dispatch until persisted`() {
        val writer = Writer()
        val journal = create(writer)
        writer.fail = { true }
        assertThrows(IOException::class.java) { journal.shell(listOf("uitest", "dumpLayout"), true) { error("Must not execute") } }
        assertThat(journal.snapshot().state).isEqualTo("unknown")
        assertThat(journal.snapshot().issues.map { it.code }).contains("JOURNAL_PERSISTENCE_UNCONFIRMED")
        assertThrows(IllegalStateException::class.java) { journal.hostCall { error("Must not execute") } }
        writer.fail = { false }
        journal.close()
        HarmonyOSRecoveryJournal.open(file()).use { assertThat(it.recover(unavailable).state).isEqualTo("complete") }
    }

    @Test fun `ordinary failure after transport entry cannot roll back a dispatched operation`() {
        val writer = Writer()
        create(writer).use { journal ->
            val failure = IOException("Transport entered; response unavailable")
            var entered = 0
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> entered++; throw failure }).withRecoveryJournal(journal)
            assertThat(assertThrows(IOException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }).isSameInstanceAs(failure)
            assertThat(entered).isEqualTo(1)
            assertThat(state()["operations"].size()).isEqualTo(1)
            assertThat(state()["hostCalls"].size()).isEqualTo(1)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
        }
        HarmonyOSRecoveryJournal.open(file()).use { journal ->
            var queries = 0
            val noProof = HdcClient("fake", HdcCommandRunner { _, _ -> queries++; HdcCommandResult(0, "", "") })
            assertThat(journal.recover(noProof).issues.map { it.code }).contains("REMOTE_COMPLETION_UNCONFIRMED")
            assertThat(queries).isEqualTo(1)
        }
    }

    @Test fun `a later pre-execute failure cannot erase an earlier dispatch in the same shell scope`() {
        val writer = Writer()
        create(writer).use { journal ->
            var failed = false
            writer.fail = { it["hostCalls"].size() == 2 && !failed.also { failed = true } }
            var entered = 0
            assertThrows(IOException::class.java) {
                journal.shell(listOf("uitest", "dumpLayout"), true) {
                    journal.hostCall { entered++ }
                    journal.hostCall { error("Second transport must not enter") }
                    HdcCommandResult(0, "", "")
                }
            }
            assertThat(entered).isEqualTo(1)
            assertThat(state()["operations"].size()).isEqualTo(1)
            assertThat(state()["hostCalls"].size()).isEqualTo(1)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
        }
    }

    @Test fun `rollback cannot reset root removed over a concurrently registered operation`() {
        val writer = Writer()
        create(writer).use { journal ->
            val firstRegistered = CountDownLatch(1)
            val secondRegistered = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            var failed = false
            writer.fail = { Thread.currentThread().name == "registration-failure" && it["hostCalls"].size() == 1 && !failed.also { failed = true } }
            val first = Thread({
                try {
                    journal.shell(listOf("uitest", "first"), true) {
                        firstRegistered.countDown()
                        check(secondRegistered.await(5, TimeUnit.SECONDS))
                        journal.hostCall { error("Must not execute first operation") }
                        HdcCommandResult(0, "", "")
                    }
                } catch (error: Throwable) { failure.set(error) }
            }, "registration-failure")
            first.start()
            try {
                check(firstRegistered.await(5, TimeUnit.SECONDS))
                assertThrows(HdcException::class.java) { journal.shell(listOf("uitest", "second"), true) { HdcCommandResult(0, "unconfirmed", "") } }
                val secondId = state()["operations"].last()["id"].asText()
                secondRegistered.countDown()
                first.join(5_000)
                assertThat(first.isAlive).isFalse()
                assertThat(failure.get()).isSameInstanceAs(writer.failure)
                assertThat(state()["operations"].size()).isEqualTo(1)
                assertThat(state()["operations"][0]["id"].asText()).isEqualTo(secondId)
                assertThat(state()["rootRemoved"].asBoolean()).isFalse()
            } finally { secondRegistered.countDown(); first.interrupt(); first.join(5_000) }
        }
    }

    @Test fun `nested shell dispatch also prevents rollback of its enclosing callback`() {
        val writer = Writer()
        create(writer).use { journal ->
            var failed = false
            writer.fail = { it["hostCalls"].size() == 2 && !failed.also { failed = true } }
            assertThrows(IOException::class.java) {
                journal.shell(listOf("uitest", "outer"), true) {
                    journal.shell(listOf("uitest", "inner"), true) {
                        journal.hostCall {
                            val token = state()["operations"].last()["token"].asText().replace("-", "")
                            HdcCommandResult(0, "\nMAESTRO_RECOVERY_$token=0\n", "")
                        }
                    }
                    journal.hostCall { error("Must not enter this later transport") }
                    HdcCommandResult(0, "", "")
                }
            }
            assertThat(state()["operations"].size()).isEqualTo(2)
            assertThat(state()["operations"][0]["completed"].asBoolean()).isFalse()
            assertThat(state()["operations"][1]["completed"].asBoolean()).isTrue()
        }
    }

    @Test fun `transport error remains primary when recording its return also fails`() {
        val writer = Writer()
        val journal = create(writer)
        val transportFailure = IOException("Actual transport failure")
        writer.fail = { it["hostCalls"].any { call -> call["returned"].asBoolean() } }
        val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> throw transportFailure }).withRecoveryJournal(journal)
        assertThat(assertThrows(IOException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }).isSameInstanceAs(transportFailure)
        assertThat(transportFailure.suppressed.toList()).contains(writer.failure)
        assertThat(journal.snapshot().issues.map { it.code }).contains("JOURNAL_PERSISTENCE_UNCONFIRMED")
        assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
        writer.fail = { false }
        journal.close()
        assertThat(state()["operations"].size()).isEqualTo(1)
    }

    @Test fun `acknowledged operation is retained when persisting its completion fails`() {
        val writer = Writer()
        create(writer).use { journal ->
            var failed = false
            writer.fail = { it["operations"].any { op -> op["completed"].asBoolean() } && !failed.also { failed = true } }
            var entered = 0
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ ->
                entered++
                val token = state()["operations"].last()["token"].asText().replace("-", "")
                HdcCommandResult(0, "\nMAESTRO_RECOVERY_$token=0\n", "")
            }).withRecoveryJournal(journal)
            assertThat(assertThrows(IOException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }).isSameInstanceAs(writer.failure)
            assertThat(entered).isEqualTo(1)
            assertThat(state()["operations"].size()).isEqualTo(1)
            assertThat(state()["operations"][0]["completed"].asBoolean()).isFalse()
            assertThat(journal.snapshot().issues.map { it.code }).contains("JOURNAL_PERSISTENCE_UNCONFIRMED")
        }
        assertThat(state()["operations"].size()).isEqualTo(1)
        assertThat(state()["operations"][0]["completed"].asBoolean()).isTrue()
    }

    @Test fun `failure recording a started native process retains its identity and pending command`() {
        val writer = Writer()
        val observedPid = AtomicReference<Long>()
        create(writer).use { journal ->
            var failed = false
            writer.fail = { state ->
                val processes = state["hostCalls"].lastOrNull()?.get("processes")
                if (processes != null && !processes.isEmpty && !failed) {
                    failed = true; observedPid.set(processes[0]["pid"].asLong()); true
                } else false
            }
            assertThat(assertThrows(IOException::class.java) {
                journal.shell(listOf("uitest", "dumpLayout"), true) {
                    journal.hostCall { observer ->
                        ProcessHdcCommandRunner().runObserved(HdcProcessTestChild.command("sleep", directory.resolve("owned-child.pid").toString()), Duration.ofSeconds(10), observer)
                    }
                }
            }).isSameInstanceAs(writer.failure)
            assertThat(ProcessHandle.of(observedPid.get()).map { it.isAlive }.orElse(false)).isFalse()
            assertThat(state()["hostCalls"][0]["processes"][0]["pid"].asLong()).isEqualTo(observedPid.get())
            assertThat(state()["hostCalls"][0]["returned"].asBoolean()).isTrue()
            assertThat(state()["hostCalls"][0]["stopped"].asBoolean()).isTrue()
            assertThat(state()["operations"].size()).isEqualTo(1)
            journal.seal()
            val noProof = HdcClient("fake", HdcCommandRunner { _, _ -> HdcCommandResult(0, "", "") })
            assertThat(journal.recover(noProof).issues.map { it.code }).contains("REMOTE_COMPLETION_UNCONFIRMED")
        }
    }
}
