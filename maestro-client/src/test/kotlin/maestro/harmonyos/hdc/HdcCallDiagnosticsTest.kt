package maestro.harmonyos.hdc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HdcCallDiagnosticsTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private val lines = CopyOnWriteArrayList<String>()
    private fun events(): List<JsonNode> = lines.map { mapper.readTree(it.removePrefix("MAESTRO_HDC_CALL ")) }
    private fun terminal(): JsonNode = events().single { it["phase"].asText() == "FINISH" }
    private fun runner() = ProcessHdcCommandRunner(diagnosticSink = lines::add)

    @Test fun `real process success records finite phases and both drained byte counts`() {
        val result = runner().run(HdcProcessTestChild.command("flood"), Duration.ofSeconds(10))
        assertThat(result.stdout.length).isEqualTo(262144)
        assertThat(result.stderr.length).isEqualTo(262144)
        val events = events()
        assertThat(events.size).isAtMost(33)
        assertThat(events.map { it["callId"].asText() }.distinct()).hasSize(1)
        assertThat(events.map { it["sequence"].asInt() }).containsExactlyElementsIn(1..events.size).inOrder()
        for (phase in listOf("SPAWN", "WAIT_EXIT", "STDOUT_READ", "STDERR_READ", "DRAIN_STDOUT", "DRAIN_STDERR")) {
            assertThat(events.filter { it["phase"].asText() == phase }.map { it["event"].asText() })
                .containsExactly("begin", "returned").inOrder()
        }
        for (phase in listOf("STDOUT_READ", "STDERR_READ")) {
            assertThat(events.single { it["phase"].asText() == phase && it["event"].asText() == "returned" }["bytes"].asLong()).isEqualTo(262144L)
        }
        assertThat(terminal()["exitCode"].asInt()).isEqualTo(0)
        assertThat(terminal()["event"].asText()).isEqualTo("returned")
        assertThat(terminal()["timedOut"].asBoolean()).isFalse()
        assertThat(terminal()["budgetMs"].asLong()).isEqualTo(10000L)
        assertThat(events.all { it["elapsedMs"].asLong() >= 0 }).isTrue()
        assertThat(lines.joinToString()).doesNotContain("xxxxxxxx")
    }

    @Test fun `real timeout identifies wait exit and termination without poll events`() {
        val pid = Files.createFile(directory.resolve("sleep.pid"))
        try {
            assertThrows(HdcTimeoutException::class.java) {
                runner().run(HdcProcessTestChild.command("sleep", pid.toString()), Duration.ofSeconds(3))
            }
            assertThat(terminal()["event"].asText()).isEqualTo("failed")
            assertThat(terminal()["failurePhase"].asText()).isEqualTo("WAIT_EXIT")
            assertThat(terminal()["lastPhase"].asText()).isEqualTo("TERMINATE")
            assertThat(terminal()["timedOut"].asBoolean()).isTrue()
            assertThat(terminal()["exitCode"].isNull).isTrue()
            assertThat(events().count { it["phase"].asText() == "WAIT_EXIT" }).isEqualTo(1)
            assertThat(events().size).isAtMost(33)
            assertStopped(pid)
        } finally { stop(pid) }
    }

    @Test fun `real root exit followed by inherited pipe timeout is not reported as success`() {
        val parentPid = Files.createFile(directory.resolve("parent.pid"))
        val childPid = Files.createFile(directory.resolve("child.pid"))
        try {
            assertThrows(HdcTimeoutException::class.java) {
                runner().run(HdcProcessTestChild.command("spawn-and-exit", parentPid.toString(), childPid.toString()), Duration.ofSeconds(4))
            }
            val final = terminal()
            assertThat(final["exitCode"].isNull).isFalse()
            assertThat(final["exitCode"].isInt).isTrue()
            assertThat(final["exitCode"].asInt()).isEqualTo(0)
            assertThat(final["event"].asText()).isEqualTo("failed")
            assertThat(final["failurePhase"].asText()).isAnyOf("DRAIN_STDOUT", "DRAIN_STDERR")
            assertThat(final["failureKind"].asText()).isEqualTo("timeout")
            assertThat(final["timedOut"].asBoolean()).isTrue()
            assertStopped(parentPid)
            assertStopped(childPid)
        } finally { stop(parentPid); stop(childPid) }
    }

    @Test fun `real interruption preserves its exception flag and diagnostic outcome`() {
        val pid = Files.createFile(directory.resolve("interrupted.pid"))
        val error = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val thread = Thread {
            try { runner().run(HdcProcessTestChild.command("sleep", pid.toString()), Duration.ofSeconds(30)) }
            catch (failure: Throwable) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted) }
        }
        thread.start()
        try {
            waitUntil { Files.readString(pid).trim().toLongOrNull() != null }
            thread.interrupt()
            thread.join(7000)
            assertThat(thread.isAlive).isFalse()
            assertThat(error.get()).isInstanceOf(InterruptedException::class.java)
            assertThat(interrupted.get()).isTrue()
            assertThat(terminal()["interrupted"].asBoolean()).isTrue()
            assertThat(terminal()["failureKind"].asText()).isEqualTo("interrupted")
            assertStopped(pid)
        } finally { thread.interrupt(); stop(pid); thread.join(7000) }
    }

    @Test fun `diagnostic emitter failures do not change real process output or client exception identity`() {
        val broken: (String) -> Unit = { throw IllegalStateException("logging unavailable") }
        val result = ProcessHdcCommandRunner(diagnosticSink = broken).run(HdcProcessTestChild.command("failure"), Duration.ofSeconds(10))
        assertThat(result.exitCode).isEqualTo(7)
        assertThat(result.stdout.trim()).isEqualTo("command output")
        assertThat(result.stderr.trim()).isEqualTo("command error")
        val expected = HdcTimeoutException(Duration.ofMillis(51))
        val client = HdcClient("private-executable", HdcCommandRunner { _, _ -> throw expected }, diagnosticSink = broken)
        assertThat(assertThrows(HdcTimeoutException::class.java) { client.shell("private-device", listOf("uitest", "dumpLayout")) }).isSameInstanceAs(expected)
    }

    @Test fun `classification never exposes arguments device paths output or exception messages`() {
        val secret = "private-input-中文; shell-secret"
        val client = HdcClient("private-executable", HdcCommandRunner { _, _ -> HdcCommandResult(0, secret, "") }, diagnosticSink = lines::add)
        client.shell("private-device", listOf("uitest", "dumpLayout", "-p", "/private/layout.json"))
        client.shell("private-device", listOf("uitest", "uiInput", "text", secret))
        client.shell("private-device", listOf("uitest", "uiInput", "swipe", "1", "2", "3", "4", "200"))
        client.shell("private-device", listOf("rm", "-f", "/private/file"))
        client.shell("private-device", listOf("private-command", secret))
        client.receiveFile("private-device", "/private/remote", directory.resolve("private-local"))
        val final = events().filter { it["phase"].asText() == "FINISH" }
        assertThat(final.map { it["category"].asText() }).containsExactly(
            "DUMP_LAYOUT", "INPUT_TEXT", "INPUT_SWIPE", "REMOVE_FILE", "SHELL_OTHER", "FILE_RECEIVE",
        ).inOrder()
        assertThat(final.map { it["callId"].asText() }.distinct()).hasSize(6)
        val text = lines.joinToString()
        assertThat(text).doesNotContain("private")
        assertThat(text).doesNotContain(secret)
        assertThat(text).doesNotContain(directory.toString())
    }

    @Test fun `device wrapper preserves safe input and screenshot categories`() {
        val fake = HdcCommandRunner { command, _ ->
            val marker = checkNotNull(Regex("MAESTRO_EXIT_[0-9a-f]+=").find(command.last())).value
            HdcCommandResult(0, "No Error\n${marker}0\n", "")
        }
        val device = HdcHarmonyOSDevice("private-device", HdcClient("fake", fake, diagnosticSink = lines::add))
        device.execute(listOf("uitest", "uiInput", "text", "private-input"), Duration.ofSeconds(1))
        device.execute(listOf("uitest", "screenCap", "-p", "/private/screen.png"), Duration.ofSeconds(1))
        device.execute(listOf("rm", "-f", "/private/screen.png"), Duration.ofSeconds(1))
        assertThat(events().filter { it["phase"].asText() == "FINISH" }.map { it["category"].asText() })
            .containsExactly("INPUT_TEXT", "SCREEN_CAPTURE", "REMOVE_FILE").inOrder()
        assertThat(lines.joinToString()).doesNotContain("private")
        assertThat(lines.joinToString()).doesNotContain("MAESTRO_EXIT_")
    }

    @Test fun `call identifiers correlate with journal operations without changing disk schema`() {
        val path = directory.resolve("journal.json")
        HarmonyOSRecoveryJournal.create(path, "private-device", UUID.randomUUID().toString()).use { journal ->
            val fake = HdcCommandRunner { command, _ ->
                val marker = checkNotNull(Regex("MAESTRO_RECOVERY_[0-9a-f]+=").find(command.last())).value
                HdcCommandResult(0, "\n${marker}0\n", "")
            }
            HdcClient("fake", fake, diagnosticSink = lines::add).withRecoveryJournal(journal)
                .shell("private-device", listOf("uitest", "dumpLayout", "-p", "/private/layout.json"))
            val state = mapper.readTree(path.toFile())
            assertThat(terminal()["operationId"].asText()).isEqualTo(state["operations"][0]["id"].asText())
            assertThat(terminal()["hostCallId"].asText()).isEqualTo(state["hostCalls"][0]["id"].asText())
            assertThat(terminal()["category"].asText()).isEqualTo("DUMP_LAYOUT")
            assertThat(state["version"].asInt()).isEqualTo(1)
            assertThat(state.has("callId")).isFalse()
            assertThat(lines.joinToString()).doesNotContain("private")
        }
    }

    private fun assertStopped(file: Path) {
        val pid = Files.readString(file).trim().toLong()
        waitUntil { !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
    }
    private fun stop(file: Path) { Files.readString(file).trim().toLongOrNull()?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } } }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(condition()).isTrue()
    }
}
