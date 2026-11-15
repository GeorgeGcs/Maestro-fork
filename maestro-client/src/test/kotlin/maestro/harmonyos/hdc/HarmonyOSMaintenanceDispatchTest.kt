package maestro.harmonyos.hdc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class HarmonyOSMaintenanceDispatchTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private fun file() = directory.resolve("journal.json")
    private fun state() = mapper.readTree(file().toFile())
    private fun create() = HarmonyOSRecoveryJournal.create(file(), "fixture-target", UUID.randomUUID().toString())

    @Test fun `binding sees persisted identity before the runner is entered`() {
        create().use { journal ->
            var bound: String? = null
            journal.hostCall(beforeDispatch = { id ->
                assertThat(state()["hostCalls"].single()["id"].asText()).isEqualTo(id)
                assertThat(state()["hostCalls"].single()["returned"].asBoolean()).isFalse()
                bound = id
            }) {
                assertThat(bound).isNotNull()
                assertThat(state()["hostCalls"].single()["id"].asText()).isEqualTo(bound)
            }
            assertThat(state()["hostCalls"].single()["returned"].asBoolean()).isTrue()
            assertThat(state()["hostCalls"].single()["stopped"].asBoolean()).isTrue()
        }
    }

    @Test fun `failed binding rolls back only the new host call and leaves no active count`() {
        val journal = create()
        journal.hostCall { Unit }
        val prior = state()["hostCalls"].single().deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        val failure = IOException("Binding could not be saved")
        assertThat(assertThrows(IOException::class.java) {
            journal.hostCall(beforeDispatch = { throw failure }) { error("Must not enter runner") }
        }).isSameInstanceAs(failure)
        assertThat(state()["hostCalls"].size()).isEqualTo(1)
        assertThat(state()["hostCalls"].single()).isEqualTo(prior)
        assertThat(journal.snapshot().issues.map { it.code }).doesNotContain("HOST_CALL_ACTIVE")
        journal.close()
        HarmonyOSRecoveryJournal.open(file()).use { reopened ->
            assertThat(reopened.snapshot().state).isEqualTo("complete")
            assertThat(state()["hostCalls"].size()).isEqualTo(1)
        }
    }

    @Test fun `nested binding failure can roll back its never entered shell without deleting history`() {
        create().use { journal ->
            assertThrows(HdcException::class.java) {
                journal.shell(listOf("uitest", "dumpLayout"), true) { HdcCommandResult(0, "missing proof", "") }
            }
            val prior = state()["operations"].single().deepCopy<com.fasterxml.jackson.databind.JsonNode>()
            val failure = IOException("Binding failure")
            assertThat(assertThrows(IOException::class.java) {
                journal.shell(listOf("uitest", "uiInput", "click", "1", "2"), true) {
                    journal.hostCall(beforeDispatch = { throw failure }) { error("Must not enter runner") }
                }
            }).isSameInstanceAs(failure)
            assertThat(state()["operations"].size()).isEqualTo(1)
            assertThat(state()["operations"].single()).isEqualTo(prior)
            assertThat(state()["rootRemoved"].asBoolean()).isFalse()
            assertThat(state()["hostCalls"].size()).isEqualTo(0)
        }
    }

    @Test fun `rollback persistence failure retains original error and blocks maintenance`() {
        var failWrite = false
        val writeFailure = IOException("Rollback cannot be persisted")
        val writer = HarmonyOSJournalFileWriter()
        val journal = HarmonyOSRecoveryJournal.create(file(), "fixture-target", UUID.randomUUID().toString()) { path, bytes ->
            if (failWrite) throw writeFailure
            writer.write(path, bytes)
        }
        val original = IOException("Binding cannot be persisted")
        try {
            journal.seal()
            assertThat(assertThrows(IOException::class.java) {
                journal.hostCall(allowSealed = true, beforeDispatch = { failWrite = true; throw original }) { error("Must not enter runner") }
            }).isSameInstanceAs(original)
            assertThat(original.suppressed.toList()).contains(writeFailure)
            assertThat(journal.snapshot().issues.map { it.code }).contains("JOURNAL_PERSISTENCE_UNCONFIRMED")
            assertThat(journal.snapshot().issues.map { it.code }).doesNotContain("HOST_CALL_ACTIVE")
            assertThat(state()["hostCalls"].size()).isEqualTo(1)
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> error("Must not dispatch") })
            assertThrows(IllegalStateException::class.java) {
                HarmonyOSMaintenanceTransport(journal, hdc).observeBoot(Duration.ofSeconds(1)) {}
            }
        } finally { failWrite = false; journal.close() }
        HarmonyOSRecoveryJournal.open(file()).use {
            assertThat(state()["hostCalls"].size()).isEqualTo(0)
            assertThat(it.snapshot().state).isEqualTo("complete")
        }
    }

    @Test fun `binding interruption preserves interrupt and original exception without executing`() {
        create().use { journal ->
            val interrupted = InterruptedException("Binding interrupted")
            try {
                assertThat(assertThrows(InterruptedException::class.java) {
                    journal.hostCall(beforeDispatch = { throw interrupted }) { error("Must not enter runner") }
                }).isSameInstanceAs(interrupted)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
                assertThat(state()["hostCalls"].size()).isEqualTo(0)
            } finally { Thread.interrupted() }
        }
    }

    @Test fun `runner entry keeps the bound host call and pending operation on failure`() {
        create().use { journal ->
            var bound: String? = null
            val failure = IOException("Runner was entered")
            assertThat(assertThrows(IOException::class.java) {
                journal.shell(listOf("uitest", "dumpLayout"), true) {
                    journal.hostCall(beforeDispatch = { bound = it }) { throw failure }
                }
            }).isSameInstanceAs(failure)
            assertThat(state()["operations"].size()).isEqualTo(1)
            assertThat(state()["operations"].single()["completed"].asBoolean()).isFalse()
            assertThat(state()["hostCalls"].single()["id"].asText()).isEqualTo(bound)
            assertThat(state()["hostCalls"].single()["returned"].asBoolean()).isTrue()
        }
    }
}
