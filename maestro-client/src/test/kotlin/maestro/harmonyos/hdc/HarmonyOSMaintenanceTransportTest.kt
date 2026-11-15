package maestro.harmonyos.hdc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

class HarmonyOSMaintenanceTransportTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private val timeout = Duration.ofSeconds(10)
    private val bootId = "f0e81b81-4967-4a73-8109-21ed1f719f60"

    private fun create() = HarmonyOSRecoveryJournal.create(directory.resolve(UUID.randomUUID().toString()).resolve("journal.json"),
        "fixture-target", UUID.randomUUID().toString())
    private fun state(journal: HarmonyOSRecoveryJournal) = mapper.readTree(journal.path.toFile())
    private fun original(journal: HarmonyOSRecoveryJournal): JsonNode = state(journal).deepCopy<ObjectNode>().apply { remove("hostCalls") }
    private fun pending(journal: HarmonyOSRecoveryJournal, count: Int = 1, finite: Boolean = true) {
        repeat(count) {
            assertThrows(HdcException::class.java) {
                journal.shell(listOf("uitest", "dumpLayout"), finite) { HdcCommandResult(0, "no completion proof", "") }
            }
        }
    }
    private fun hash(arguments: List<String>) = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(arguments))
        .joinToString("") { "%02x".format(it) }
    private fun shellArguments(command: List<String>): List<String> {
        assertThat(command.take(4)).containsExactly("fake", "-t", "fixture-target", "shell").inOrder()
        val shell = command.last()
        assertThat(shell).startsWith("'sh' '-c' '")
        return listOf("sh", "-c", shell.removePrefix("'sh' '-c' '").removeSuffix("'").replace("'\"'\"'", "'"))
    }
    private fun nonce(command: List<String>) = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        .findAll(shellArguments(command).last()).last().value

    @Test fun `uptime query binds its fixed arguments and preserves the raw observation`() {
        create().use { journal ->
            pending(journal)
            journal.seal()
            val before = original(journal)
            val raw = " 23:02:14 up 4 days, 35 min,  0 users,  load average: 12.18, 12.26, 12.44\r\n"
            val bindings = mutableListOf<MaintenanceCall>()
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ ->
                assertThat(bindings).hasSize(1)
                assertThat(state(journal)["hostCalls"].single()["id"].asText()).isEqualTo(bindings.single().hostCallId)
                assertThat(command).containsExactly("fake", "-t", "fixture-target", "shell", "'uptime'").inOrder()
                HdcCommandResult(0, raw, "")
            })
            val observation = HarmonyOSMaintenanceTransport(journal, hdc).observeUptime(timeout, bindings::add)
            assertThat(observation.raw).isEqualTo(raw)
            assertThat(observation.minutes).isEqualTo("5795")
            assertThat(bindings.single().purpose).isEqualTo("observe_uptime")
            assertThat(bindings.single().argumentsSha256).isEqualTo(hash(listOf("uptime")))
            assertThat(original(journal)).isEqualTo(before)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
        }
    }

    @Test fun `uptime query rejects native failure diagnostics and malformed observations`() {
        val raw = " 23:02:14 up 4 days, 35 min,  0 users,  load average: 12.18, 12.26, 12.44\r\n"
        val outputs = listOf(HdcCommandResult(1, raw, ""), HdcCommandResult(0, raw, "\n"),
            HdcCommandResult(0, "", ""), HdcCommandResult(0, raw + raw, ""),
            HdcCommandResult(0, raw.replace("35 min", "60 min"), ""),
            HdcCommandResult(0, raw.replace("35 min", "25:00"), ""),
            HdcCommandResult(0, raw.replace("4 days", "-4 days"), ""),
            HdcCommandResult(0, "x".repeat(513), ""))
        for (output in outputs) create().use { journal ->
            journal.seal()
            val transport = HarmonyOSMaintenanceTransport(journal, HdcClient("fake", HdcCommandRunner { _, _ -> output }))
            assertThrows(HdcException::class.java) { transport.observeUptime(timeout) {} }
        }
    }

    @Test fun `uptime observations keep minutes beyond fixed width integer limits`() {
        create().use { journal ->
            journal.seal()
            val days = "18446744073709551616"
            val raw = " 23:02:14 up $days days, 35 min,  0 users,  load average: 12.18, 12.26, 12.44\n"
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> HdcCommandResult(0, raw, "") })
            val observation = HarmonyOSMaintenanceTransport(journal, hdc).observeUptime(timeout) {}
            assertThat(observation.minutes).isEqualTo(java.math.BigInteger(days).multiply(java.math.BigInteger.valueOf(1440)).add(java.math.BigInteger.valueOf(35)).toString())
            assertThat(observation.raw).isEqualTo(raw)
        }
    }

    @Test fun `boot query binds the durable call and actual arguments before transport`() {
        create().use { journal ->
            pending(journal)
            journal.seal()
            val before = original(journal)
            val bindings = mutableListOf<MaintenanceCall>()
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ ->
                assertThat(bindings).hasSize(1)
                val call = state(journal)["hostCalls"].single()
                assertThat(call["id"].asText()).isEqualTo(bindings.single().hostCallId)
                assertThat(call["returned"].asBoolean()).isFalse()
                assertThat(command).containsExactly("fake", "-t", "fixture-target", "shell",
                    "'cat' '/proc/sys/kernel/random/boot_id'").inOrder()
                HdcCommandResult(0, "$bootId\n", "")
            })
            assertThat(HarmonyOSMaintenanceTransport(journal, hdc).observeBoot(timeout, bindings::add)).isEqualTo(bootId)
            assertThat(bindings.single().purpose).isEqualTo("observe_boot")
            assertThat(bindings.single().argumentsSha256).isEqualTo(hash(listOf("cat", "/proc/sys/kernel/random/boot_id")))
            assertThat(state(journal)["hostCalls"].single()["stopped"].asBoolean()).isTrue()
            assertThat(original(journal)).isEqualTo(before)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
        }
    }

    @Test fun `boot query rejects nil malformed multiple values and diagnostics`() {
        val outputs = listOf(
            HdcCommandResult(0, "00000000-0000-0000-0000-000000000000\n", ""),
            HdcCommandResult(0, "$bootId\n$bootId\n", ""), HdcCommandResult(0, " $bootId", ""),
            HdcCommandResult(0, "$bootId\r", ""), HdcCommandResult(0, bootId.uppercase(), ""),
            HdcCommandResult(0, "", ""), HdcCommandResult(0, bootId, "\n"), HdcCommandResult(1, bootId, ""))
        for (output in outputs) create().use { journal ->
            journal.seal()
            val transport = HarmonyOSMaintenanceTransport(journal, HdcClient("fake", HdcCommandRunner { _, _ -> output }))
            assertThrows(HdcException::class.java) { transport.observeBoot(timeout) {} }
        }
    }

    @Test fun `cleanup confirms bounded explicit paths and preserves every original business field`() {
        val journal = create()
        pending(journal, 100)
        journal.seal()
        val before = original(journal)
        val root = state(journal)["remoteRoot"].asText()
        val expectedPaths = state(journal)["operations"].flatMap {
            val dir = "$root/${it["id"].asText()}"
            listOf("$dir/done", "$dir/done.pending", dir)
        } + listOf("$root/files", root)
        val bindings = mutableListOf<MaintenanceCall>()
        val confirmed = mutableListOf<MaintenanceCleanupBatch>()
        var calls = 0
        val hdc = HdcClient("fake", HdcCommandRunner { command, _ ->
            val bound = bindings[calls++]
            assertThat(bound.argumentsSha256).isEqualTo(hash(shellArguments(command)))
            assertThat(bound.purpose).isEqualTo("cleanup_owned_paths")
            assertThat(state(journal)["hostCalls"].last()["id"].asText()).isEqualTo(bound.hostCallId)
            assertThat(command.last().toByteArray(Charsets.UTF_8).size).isAtMost(6 * 1024)
            for (forbidden in listOf("rm -r", "kill", "toggle", "*", "mediatool")) assertThat(command.last()).doesNotContain(forbidden)
            assertThat(shellArguments(command).last()).contains("[ ! -L")
            HdcCommandResult(0, nonce(command), "")
        })
        HarmonyOSMaintenanceTransport(journal, hdc).cleanupOwnedPaths(timeout, bindings::add, confirmed::add)
        assertThat(calls).isGreaterThan(1)
        assertThat(confirmed.map { it.index }).containsExactlyElementsIn((0 until calls).toList()).inOrder()
        assertThat(confirmed.map { it.hostCallId }).containsExactlyElementsIn(bindings.map { it.hostCallId }).inOrder()
        assertThat(confirmed.flatMap { it.paths }).containsExactlyElementsIn(expectedPaths).inOrder()
        assertThat(original(journal)).isEqualTo(before)
        assertThat(journal.snapshot().state).isEqualTo("unknown")
        assertThat(journal.snapshot().pendingOperations).isEqualTo(100)
        val path = journal.path
        journal.close()
        HarmonyOSRecoveryJournal.open(path).use { reopened ->
            assertThat(original(reopened)).isEqualTo(before)
            assertThat(reopened.snapshot().pendingOperations).isEqualTo(100)
        }
    }

    @Test fun `bad cleanup nonce cannot confirm a batch or clear old pending`() {
        create().use { journal ->
            pending(journal)
            journal.seal()
            val before = original(journal)
            val confirmed = mutableListOf<MaintenanceCleanupBatch>()
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> HdcCommandResult(0, "wrong nonce", "") })
            assertThrows(HdcException::class.java) {
                HarmonyOSMaintenanceTransport(journal, hdc).cleanupOwnedPaths(timeout, {}, confirmed::add)
            }
            assertThat(confirmed).isEmpty()
            assertThat(original(journal)).isEqualTo(before)
        }
    }

    @Test fun `later cleanup failure retains only already confirmed batches`() {
        create().use { journal ->
            pending(journal, 100)
            journal.seal()
            val before = original(journal)
            var calls = 0
            val confirmed = mutableListOf<MaintenanceCleanupBatch>()
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ ->
                if (++calls == 2) throw HdcTimeoutException(Duration.ofMillis(1))
                HdcCommandResult(0, nonce(command), "")
            })
            assertThrows(HdcTimeoutException::class.java) {
                HarmonyOSMaintenanceTransport(journal, hdc).cleanupOwnedPaths(timeout, {}, confirmed::add)
            }
            assertThat(calls).isEqualTo(2)
            assertThat(confirmed).hasSize(1)
            assertThat(confirmed.single().index).isEqualTo(0)
            assertThat(confirmed.single().paths).doesNotContain(state(journal)["remoteRoot"].asText())
            assertThat(original(journal)).isEqualTo(before)
        }
    }

    @Test fun `failed batch persistence stops before any further cleanup`() {
        create().use { journal ->
            pending(journal, 100)
            journal.seal()
            val failure = IOException("Cannot persist batch confirmation")
            var calls = 0
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ -> calls++; HdcCommandResult(0, nonce(command), "") })
            assertThat(assertThrows(IOException::class.java) {
                HarmonyOSMaintenanceTransport(journal, hdc).cleanupOwnedPaths(timeout, {}, { throw failure })
            }).isSameInstanceAs(failure)
            assertThat(calls).isEqualTo(1)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(100)
        }
    }

    @Test fun `reopening retains temporary resource ordering and rejects duplicate reservations`() {
        val journal = create()
        repeat(20) { journal.allocateTemporaryFile(if (it % 2 == 0) "layout" else "screen", if (it % 2 == 0) "json" else "png") }
        journal.seal()
        val before = original(journal)
        val file = journal.path
        journal.close()
        repeat(3) {
            HarmonyOSRecoveryJournal.open(file).use { reopened -> assertThat(original(reopened)).isEqualTo(before) }
        }
        val duplicate = mapper.readTree(file.toFile()).deepCopy<ObjectNode>()
        val resources = duplicate["temporaryFiles"] as com.fasterxml.jackson.databind.node.ArrayNode
        resources.add(resources.first().asText())
        Files.write(file, mapper.writeValueAsBytes(duplicate))
        assertThrows(IllegalArgumentException::class.java) { HarmonyOSRecoveryJournal.open(file) }
    }

    @Test fun `maintenance removes only registered image files in a real shell and retains original outcomes`() {
        val shell = System.getenv("MAESTRO_TEST_POSIX_SHELL") ?: "/bin/sh"
        assumeTrue(Files.isRegularFile(Path.of(shell)), "Set MAESTRO_TEST_POSIX_SHELL on Windows")
        for (mode in listOf("normal", "unexpected-file", "directory-as-image")) create().use { journal ->
            val layout = journal.allocateTemporaryFile("layout", "json")
            val screen = journal.allocateTemporaryFile("screen", "png")
            pending(journal)
            journal.seal()
            val before = original(journal)
            val remoteRoot = state(journal)["remoteRoot"].asText()
            val work = Files.createDirectory(directory.resolve(mode))
            val owned = Files.createDirectory(work.resolve("owned"))
            val files = Files.createDirectory(owned.resolve("files"))
            val image = files.resolve(layout.substringAfterLast('/'))
            if (mode == "directory-as-image") Files.createDirectory(image) else Files.writeString(image, "retained layout")
            Files.writeString(files.resolve(screen.substringAfterLast('/')), "retained screenshot")
            if (mode == "unexpected-file") Files.writeString(files.resolve("unregistered.txt"), "keep")
            Files.writeString(work.resolve("outside.txt"), "keep")
            val confirmed = mutableListOf<MaintenanceCleanupBatch>()
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ ->
                val script = shellArguments(command).last().replace(remoteRoot, "./owned")
                val process = ProcessBuilder(shell, "-c", script).directory(work.toFile())
                    .redirectOutput(work.resolve("stdout.txt").toFile()).redirectError(work.resolve("stderr.txt").toFile()).start()
                check(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                HdcCommandResult(process.exitValue(), Files.readString(work.resolve("stdout.txt")), Files.readString(work.resolve("stderr.txt")))
            })
            val transport = HarmonyOSMaintenanceTransport(journal, hdc)
            if (mode == "normal") {
                transport.cleanupOwnedPaths(timeout, {}, confirmed::add)
                assertThat(Files.exists(owned)).isFalse()
                assertThat(confirmed.flatMap { it.paths }.take(2)).containsExactly(layout, screen).inOrder()
            } else {
                assertThrows(HdcException::class.java) { transport.cleanupOwnedPaths(timeout, {}, confirmed::add) }
                assertThat(Files.exists(owned)).isTrue()
                if (mode == "unexpected-file") assertThat(Files.readString(files.resolve("unregistered.txt"))).isEqualTo("keep")
            }
            assertThat(Files.readString(work.resolve("outside.txt"))).isEqualTo("keep")
            assertThat(original(journal)).isEqualTo(before)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
        }
    }

    @Test fun `maintenance refuses active unsealed unsupported temporary recording and nonfinite scopes`() {
        val fixtures: List<(HarmonyOSRecoveryJournal) -> Unit> = listOf(
            {}, { it.allocateTemporaryFile("recording", "mp4"); it.seal() },
            { it.allocateTemporaryFile("layout", "mp4"); it.seal() },
            { it.enableRecordingResources(); it.seal() }, { pending(it, finite = false); it.seal() },
            { journal ->
                journal.hostCall { observer ->
                    val process = ProcessHandle.current()
                    observer(HarmonyOSRecoveryJournal.HostProcess(process.pid(), process.info().startInstant().get().toString()))
                }
                journal.seal()
            })
        for (prepare in fixtures) create().use { journal ->
            prepare(journal)
            val transport = HarmonyOSMaintenanceTransport(journal, HdcClient("fake", HdcCommandRunner { _, _ -> error("Must not dispatch") }))
            assertThrows(IllegalStateException::class.java) { transport.observeBoot(timeout) {} }
            assertThrows(IllegalStateException::class.java) { transport.cleanupOwnedPaths(timeout, {}, {}) }
        }
        create().use { journal ->
            journal.seal()
            journal.hostCall(allowSealed = true) {
                val transport = HarmonyOSMaintenanceTransport(journal, HdcClient("fake", HdcCommandRunner { _, _ -> error("Must not dispatch") }))
                assertThrows(IllegalStateException::class.java) { transport.observeBoot(timeout) {} }
            }
        }
    }

    @Test fun `expired shared deadline or binding failure never enters the runner`() {
        create().use { journal ->
            journal.seal()
            val transport = HarmonyOSMaintenanceTransport(journal, HdcClient("fake", HdcCommandRunner { _, _ -> error("Must not dispatch") }))
            assertThrows(HdcTimeoutException::class.java) { transport.cleanupOwnedPaths(Duration.ofNanos(1), {}, {}) }
            val failure = IOException("Cannot bind checkpoint")
            assertThat(assertThrows(IOException::class.java) { transport.observeBoot(timeout) { throw failure } }).isSameInstanceAs(failure)
            assertThat(state(journal)["hostCalls"].size()).isEqualTo(0)
            assertThrows(HdcTimeoutException::class.java) {
                transport.observeBoot(Duration.ofMillis(30)) { Thread.sleep(50) }
            }
            assertThat(state(journal)["hostCalls"].size()).isEqualTo(0)
        }
    }

    @Test fun `interruption preserves the flag and cannot confirm cleanup`() {
        create().use { journal ->
            pending(journal)
            journal.seal()
            val confirmed = mutableListOf<MaintenanceCleanupBatch>()
            val interrupted = InterruptedException("Injected interruption")
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> throw interrupted })
            try {
                assertThat(assertThrows(InterruptedException::class.java) {
                    HarmonyOSMaintenanceTransport(journal, hdc).cleanupOwnedPaths(timeout, {}, confirmed::add)
                }).isSameInstanceAs(interrupted)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
                assertThat(confirmed).isEmpty()
                assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
            } finally { Thread.interrupted() }
        }
    }

    @Test fun `interrupted batch persistence restores the flag and prevents later cleanup`() {
        create().use { journal ->
            pending(journal, 100)
            journal.seal()
            val before = original(journal)
            val interrupted = InterruptedException("Batch persistence interrupted")
            var calls = 0
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ -> calls++; HdcCommandResult(0, nonce(command), "") })
            try {
                assertThat(assertThrows(InterruptedException::class.java) {
                    HarmonyOSMaintenanceTransport(journal, hdc).cleanupOwnedPaths(timeout, {}, { throw interrupted })
                }).isSameInstanceAs(interrupted)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
                assertThat(calls).isEqualTo(1)
                assertThat(original(journal)).isEqualTo(before)
            } finally { Thread.interrupted() }
        }
    }
}
