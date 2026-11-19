package maestro.harmonyos.hdc

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class UitestForwardRecoveryTest {
    @TempDir lateinit var directory: Path
    private val device = "fixture-device"
    private val timeout = Duration.ofSeconds(2)
    private val path get() = directory.resolve("recovery.json")

    @Test fun `missing listener does not create or register a forward`() {
        val fixture = ForwardFixture(listening = false)
        newJournal().use { journal ->
            assertThat(fixture.client.withRecoveryJournal(journal).openAutomaticUitestLayoutForward(device, timeout)).isNull()
            journal.seal()
            assertThat(journal.recover(fixture.client).state).isEqualTo("complete")
        }
        assertThat(fixture.creates).isEqualTo(0)
        assertThat(Files.readString(path)).doesNotContain("layoutForward")
    }

    @Test fun `a confirmed forward is persisted before use and recovered after reopen`() {
        val fixture = ForwardFixture()
        newJournal().use { journal ->
            assertThat(fixture.client.withRecoveryJournal(journal).openAutomaticUitestLayoutForward(device, timeout)).isEqualTo(fixture.port)
            assertThat(fixture.intentAtCreation).contains("\"confirmed\":false")
            assertThat(Files.readString(path)).contains("\"confirmed\":true")
        }
        HarmonyOSRecoveryJournal.open(path).use { journal ->
            assertThat(journal.recover(fixture.client).state).isEqualTo("complete")
        }
        assertThat(fixture.removes).isEqualTo(1)
        assertThat(fixture.row).isNull()
        assertThat(Files.readString(path)).doesNotContain("layoutForward")
    }

    @Test fun `manual or replacement rule owned by another device is preserved`() {
        val fixture = ForwardFixture()
        newJournal().use { journal ->
            assertThat(fixture.client.withRecoveryJournal(journal).openAutomaticUitestLayoutForward(device, timeout)).isNotNull()
            fixture.row = fixture.row!!.replace(device, "another-device")
            journal.seal()
            assertThat(journal.recover(fixture.client).state).isEqualTo("complete")
        }
        assertThat(fixture.removes).isEqualTo(0)
        assertThat(fixture.row).startsWith("another-device")
    }

    @Test fun `ambiguous creation is quarantined and can be recovered after external release`() {
        val fixture = ForwardFixture(confirmCreation = false)
        newJournal().use { journal ->
            assertThrows<HdcException> { fixture.client.withRecoveryJournal(journal).openAutomaticUitestLayoutForward(device, timeout) }
        }
        HarmonyOSRecoveryJournal.open(path).use { journal ->
            val report = journal.recover(fixture.client)
            assertThat(report.state).isEqualTo("unknown")
            assertThat(report.issues.map { it.code }).contains("UITEST_FORWARD_CLEANUP_UNCONFIRMED")
            assertThat(fixture.removes).isEqualTo(0)
            fixture.row = null
            assertThat(journal.recover(fixture.client).state).isEqualTo("complete")
        }
    }

    @Test fun `malformed rule listing never counts as confirmed release`() {
        val fixture = ForwardFixture()
        newJournal().use { journal ->
            assertThat(fixture.client.withRecoveryJournal(journal).openAutomaticUitestLayoutForward(device, timeout)).isNotNull()
            fixture.malformedList = true
            journal.seal()
            assertThat(journal.recover(fixture.client).state).isEqualTo("unknown")
            assertThat(fixture.removes).isEqualTo(0)
            fixture.malformedList = false
            assertThat(journal.recover(fixture.client).state).isEqualTo("complete")
        }
    }

    private fun newJournal() = HarmonyOSRecoveryJournal.create(path, device, UUID.randomUUID().toString())

    private inner class ForwardFixture(private val listening: Boolean = true, private val confirmCreation: Boolean = true) {
        var creates = 0
        var removes = 0
        var port = 0
        var row: String? = null
        var intentAtCreation = ""
        var malformedList = false
        val client = HdcClient("fixture", HdcCommandRunner { command, _ ->
            val output = when {
                command.drop(1) == listOf("-t", device, "shell", "cat /proc/net/unix") ->
                    if (listening) "0: 00000002 0 10000 1 1 1234 @uitest_socket" else ""
                command.drop(1) == listOf("fport", "ls") -> if (malformedList) "unrecognized" else row ?: "[Empty]"
                command.take(5) == listOf("fixture", "-t", device, "fport", "rm") -> {
                    check(command.drop(5) == listOf("tcp:$port", "localabstract:uitest_socket"))
                    removes++
                    row = null
                    "Remove forward ruler success"
                }
                command.take(4) == listOf("fixture", "-t", device, "fport") -> {
                    check(command.last() == "localabstract:uitest_socket")
                    intentAtCreation = Files.readString(path)
                    creates++
                    port = command[4].removePrefix("tcp:").toInt()
                    row = "$device tcp:$port localabstract:uitest_socket [Forward]"
                    if (confirmCreation) "Forwardport result:OK" else "unconfirmed"
                }
                else -> error("Unexpected HDC arguments: $command")
            }
            HdcCommandResult(0, output, "")
        })
    }
}
