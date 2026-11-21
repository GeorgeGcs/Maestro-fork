package maestro.harmonyos.hdc

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class HarmonyOSRecoveryJournalTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private fun file() = directory.resolve("journal.json")
    private fun create() = HarmonyOSRecoveryJournal.create(file(), "fixture-target", UUID.randomUUID().toString())

    @Test fun `batched cleanup executes named paths and preserves unexpected content in a real shell`() {
        val shell = System.getenv("MAESTRO_TEST_POSIX_SHELL") ?: "/bin/sh"
        assumeTrue(Files.isRegularFile(Path.of(shell)), "Set MAESTRO_TEST_POSIX_SHELL to exercise the generated shell on Windows")
        for (mode in listOf("normal", "missing-directory", "unexpected-file", "not-a-directory", "recreated-layout")) {
            val work = Files.createDirectory(directory.resolve(mode))
            val journalPath = work.resolve("journal.json")
            HarmonyOSRecoveryJournal.create(journalPath, "fixture-target", UUID.randomUUID().toString()).use { journal ->
                val remote = FakeRemote(journalPath)
                val hdc = HdcClient("fake", HdcCommandRunner { command, timeout ->
                    if (command.last().contains("MAESTRO_RECOVERY_")) remote.run(command, timeout)
                    else {
                        val state = mapper.readTree(journalPath.toFile())
                        // Only this test's scratch tree is exposed to the shell; never /data/local/tmp.
                        val script = command.last().replace(state["remoteRoot"].asText(), "./owned")
                        check(!script.contains("/data/local/tmp"))
                        val stdout = work.resolve("stdout.txt"); val stderr = work.resolve("stderr.txt")
                        // A file avoids Windows native argv rewriting nested POSIX quotes.
                        Files.writeString(work.resolve("cleanup.sh"), "PATH=/usr/bin:/bin:\"\$PATH\"; export PATH\n" + script + "\n")
                        val builder = ProcessBuilder(shell, "cleanup.sh").directory(work.toFile())
                            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
                        val process = builder.start()
                        try {
                            check(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { "Test shell timed out" }
                            HdcCommandResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
                        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor() } }
                    }
                })
                val tracked = hdc.withRecoveryJournal(journal)
                repeat(3) { tracked.shell("fixture-target", listOf("uitest", "dumpLayout")) }
                val removedLayout = if (mode == "recreated-layout") {
                    tracked.allocateTemporaryFile("layout", "json").also { remotePath ->
                        tracked.shell("fixture-target", listOf("uitest", "dumpLayout", "-p", remotePath))
                        tracked.shell("fixture-target", listOf("rm", "-f", remotePath))
                        tracked.forgetTemporaryFile(remotePath)
                    }
                } else null
                val state = mapper.readTree(journalPath.toFile())
                val owned = Files.createDirectory(work.resolve("owned")); Files.createDirectory(owned.resolve("files"))
                // Model the real device finding: the named layout exists after a
                // successful eager deletion. Final cleanup must still know its owner.
                removedLayout?.let { Files.writeString(owned.resolve("files").resolve(it.substringAfterLast('/')), "late layout") }
                val ids = state["operations"].map { it["id"].asText() }
                for ((index, id) in ids.withIndex()) {
                    if (index == 0 && mode == "missing-directory") continue
                    if (index == 0 && mode == "not-a-directory") { Files.writeString(owned.resolve(id), "keep"); continue }
                    val operation = Files.createDirectory(owned.resolve(id))
                    Files.writeString(operation.resolve("done"), "proof")
                    Files.writeString(operation.resolve("done.pending"), "partial")
                    if (index == 0 && mode == "unexpected-file") Files.writeString(operation.resolve("foreign"), "keep")
                }
                Files.writeString(work.resolve("outside"), "keep")
                journal.seal()
                val report = journal.recover(hdc)
                val successful = mode in listOf("normal", "missing-directory", "recreated-layout")
                org.junit.jupiter.api.Assertions.assertEquals(if (successful) "complete" else "unknown", report.state) {
                    "$mode: ${report.issues}; stderr=${if (Files.exists(work.resolve("stderr.txt"))) Files.readString(work.resolve("stderr.txt")) else "absent"}"
                }
                assertThat(Files.readString(work.resolve("outside"))).isEqualTo("keep")
                if (successful) assertThat(Files.exists(owned)).isFalse()
                else {
                    val foreign = if (mode == "unexpected-file") owned.resolve(ids.first()).resolve("foreign") else owned.resolve(ids.first())
                    assertThat(Files.readString(foreign)).isEqualTo("keep")
                    assertThat(mapper.readTree(journalPath.toFile())["operations"].all { !it["markerRemoved"].asBoolean() }).isTrue()
                }
            }
        }
    }

    @Test fun `empty failed-open session releases without consulting an unavailable device`() {
        create().use { journal ->
            journal.seal()
            val unavailable = HdcClient("fake", HdcCommandRunner { _, _ -> error("No device query expected") })
            assertThat(journal.recover(unavailable).state).isEqualTo("complete")
        }
    }

    @Test fun `dispatch is durable before transport and recovery removes only owned resource paths`() {
        create().use { journal ->
            val remote = FakeRemote(file())
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            val temporary = hdc.allocateTemporaryFile("layout", "json")
            val result = hdc.shell("fixture-target", listOf("uitest", "dumpLayout", "-p", temporary))
            assertThat(result.stdout).isEqualTo("device output")
            assertThat(remote.dispatched).isEqualTo(1)
            assertThat(journal.snapshot().state).isEqualTo("unknown")
            journal.seal()
            val recovered = journal.recover(hdc)
            assertThat(recovered.state).isEqualTo("complete")
            assertThat(recovered.temporaryFiles).isEqualTo(0)
            assertThat(remote.dispatched).isEqualTo(1)
            assertThat(remote.cleanup.single()).contains(temporary.substringBeforeLast('/'))
            assertThat(remote.cleanup.single()).contains(temporary.substringAfterLast('/'))
            assertThat(remote.cleanup.single()).doesNotContain("rm -r")
            assertThat(remote.cleanup.single()).doesNotContain("kill")
            assertThat(remote.cleanup.single()).doesNotContain("*")
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
            assertThat(remote.cleanup).hasSize(1)
        }
    }

    @Test fun `lost response requires exact completion proof and never redispatches the operation`() {
        create().use { journal ->
            val remote = FakeRemote(file()).apply { loseResponse = true; proofAvailable = false }
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            assertThrows(InterruptedException::class.java) { hdc.shell("fixture-target", listOf("uitest", "uiInput", "click", "1", "2")) }
            journal.seal()
            assertThat(journal.recover(hdc).state).isEqualTo("unknown")
            assertThat(remote.cleanup).isEmpty()
            remote.proofAvailable = true
            remote.foreignProof = true
            assertThat(journal.recover(hdc).state).isEqualTo("unknown")
            assertThat(remote.cleanup).isEmpty()
            remote.foreignProof = false
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
            assertThat(remote.dispatched).isEqualTo(1)
        }
    }

    @Test fun `completion assessment preserves owned files until resource recovery approves cleanup`() {
        create().use { journal ->
            val remote = FakeRemote(file())
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            val path = hdc.allocateTemporaryFile("recording", "mp4")
            hdc.shell("fixture-target", listOf("uitest", "dumpLayout", "-p", path))
            journal.seal()
            val observed = journal.recover(hdc, cleanupResources = false)
            assertThat(observed.pendingOperations).isEqualTo(0)
            assertThat(observed.temporaryFiles).isEqualTo(1)
            assertThat(remote.cleanup).isEmpty()
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
            assertThat(remote.cleanup.single()).contains(path.substringBeforeLast('/'))
            assertThat(remote.cleanup.single()).contains(path.substringAfterLast('/'))
        }
    }

    @Test fun `journal can recover in a new process owner and cannot be opened while owned`() {
        val remote = FakeRemote(file()).apply { loseResponse = true }
        val journal = create()
        val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
        assertThrows(InterruptedException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }
        assertThrows(HdcException::class.java) { HarmonyOSRecoveryJournal.open(file()) }
        journal.close()
        HarmonyOSRecoveryJournal.open(file()).use { reopened ->
            assertThat(reopened.recover(HdcClient("fake", remote)).state).isEqualTo("complete")
            assertThat(remote.dispatched).isEqualTo(1)
        }
    }

    @Test fun `unsealed and wrong-device operations cannot be recovered or dispatched`() {
        create().use { journal ->
            val remote = FakeRemote(file())
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            assertThat(journal.recover(hdc).issues.map { it.code }).contains("SESSION_ACTIVE")
            assertThrows(IllegalArgumentException::class.java) { hdc.shell("another-target", listOf("uitest", "dumpLayout")) }
            journal.seal()
            assertThrows(IllegalStateException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }
            assertThat(remote.dispatched).isEqualTo(0)
        }
    }

    @Test fun `remote failure is still completed but cannot be mistaken for command success`() {
        create().use { journal ->
            val remote = FakeRemote(file()).apply { remoteStatus = 17 }
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            val failure = assertThrows(HdcCommandException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }
            assertThat(failure.result.exitCode).isEqualTo(17)
            assertThat(failure.result.stdout).isEqualTo("device output")
            journal.seal()
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
        }
    }

    @Test fun `completion suffix preserves command output whitespace`() {
        create().use { journal ->
            val remote = FakeRemote(file()).apply { output = "device output  \n\n" }
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            assertThat(hdc.shell("fixture-target", listOf("uitest", "dumpLayout")).stdout).isEqualTo(remote.output)
        }
    }

    @Test fun `long session cleanup uses bounded batches and can retry an unconfirmed batch`() {
        create().use { journal ->
            val remote = FakeRemote(file())
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            repeat(120) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }
            journal.seal()
            remote.failCleanup = true
            assertThat(journal.recover(hdc).state).isEqualTo("unknown")
            remote.failCleanup = false
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
            assertThat(remote.dispatched).isEqualTo(120)
            assertThat(remote.cleanup.size).isGreaterThan(2)
            assertThat(remote.cleanup.all { it.length < 10 * 1024 }).isTrue()
        }
    }

    @Test fun `normal recording sized session cleans explicit directories in few bounded calls`() {
        create().use { journal ->
            val remote = FakeRemote(file())
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            repeat(89) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }
            val state = mapper.readTree(file().toFile())
            val owned = state["operations"].map { it["id"].asText() }
            journal.seal()
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
            assertThat(remote.cleanup.size).isAtMost(3)
            assertThat(remote.cleanup.all { it.length < 10 * 1024 }).isTrue()
            val cleanup = remote.cleanup.joinToString("\n")
            owned.forEach { directory -> assertThat(Regex(Regex.escape(directory)).findAll(cleanup).count()).isEqualTo(1) }
            assertThat(cleanup).doesNotContain("rm -r")
            assertThat(cleanup).doesNotContain("*")
            val after = mapper.readTree(file().toFile())
            assertThat(after["operations"].all { it["markerRemoved"].asBoolean() }).isTrue()
            assertThat(after["rootRemoved"].asBoolean()).isTrue()
        }
    }

    @Test fun `full component suite sized cleanup keeps all registered resources in bounded calls`() {
        create().use { journal ->
            val remote = FakeRemote(file())
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            repeat(727) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }
            val resources = List(240) { hdc.allocateTemporaryFile("layout", "json") }
            resources.forEach(hdc::forgetTemporaryFile)
            val before = mapper.readTree(file().toFile())
            journal.seal()
            assertThat(journal.recover(hdc, Duration.ofSeconds(30)).state).isEqualTo("complete")
            assertThat(remote.dispatched).isEqualTo(727)
            assertThat(remote.cleanup.size).isAtMost(12)
            assertThat(remote.cleanup.all { it.length < 7 * 1024 }).isTrue()
            val cleanup = remote.cleanup.joinToString("\n")
            before["operations"].forEach { operation ->
                assertThat(Regex(Regex.escape(operation["id"].asText())).findAll(cleanup).count()).isEqualTo(1)
            }
            resources.forEach { resource ->
                assertThat(Regex(Regex.escape(resource.substringAfterLast('/'))).findAll(cleanup).count()).isEqualTo(1)
            }
            assertThat(cleanup).doesNotContain("rm -r")
            assertThat(cleanup).doesNotContain("*")
            val after = mapper.readTree(file().toFile())
            assertThat(after["operations"].all { it["markerRemoved"].asBoolean() }).isTrue()
            assertThat(after["temporaryFiles"].isEmpty).isTrue()
            assertThat(after["rootRemoved"].asBoolean()).isTrue()
        }
    }

    @Test fun `completion probes and cleanup share one recovery deadline`() {
        create().use { journal ->
            val remote = FakeRemote(file()).apply { loseResponse = true; proofDelayMillis = 50 }
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            assertThrows(InterruptedException::class.java) { hdc.shell("fixture-target", listOf("uitest", "dumpLayout")) }
            journal.seal()
            assertThat(journal.recover(hdc, Duration.ofMillis(20)).state).isEqualTo("unknown")
            assertThat(remote.cleanup).isEmpty()
            remote.proofDelayMillis = 0
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
        }
    }

    @Test fun `reopened cleanup keeps only acknowledged batches and retries an unconfirmed later batch`() {
        val remote = FakeRemote(file()).apply { failCleanupCall = 2 }
        val hdc = HdcClient("fake", remote)
        create().use { journal ->
            val tracked = hdc.withRecoveryJournal(journal)
            repeat(240) { tracked.shell("fixture-target", listOf("uitest", "dumpLayout")) }
            journal.seal()
            assertThat(journal.recover(hdc).state).isEqualTo("unknown")
            val state = mapper.readTree(file().toFile())
            assertThat(remote.cleanup).hasSize(2)
            val completed = state["operations"].filter { it["markerRemoved"].asBoolean() }
            assertThat(completed.size).isGreaterThan(0)
            assertThat(completed.size).isLessThan(240)
            state["operations"].forEach { operation ->
                assertThat(operation["markerRemoved"].asBoolean())
                    .isEqualTo(remote.cleanup.first().contains(operation["id"].asText()))
            }
            assertThat(state["rootRemoved"].asBoolean()).isFalse()
        }
        val preserved = mapper.readTree(file().toFile())["operations"].filter { it["markerRemoved"].asBoolean() }.map { it["id"].asText() }
        HarmonyOSRecoveryJournal.open(file()).use { journal ->
            remote.failCleanupCall = null
            assertThat(journal.recover(hdc).state).isEqualTo("complete")
            val resumed = remote.cleanup.drop(2).joinToString("\n")
            preserved.forEach { assertThat(resumed).doesNotContain(it) }
            assertThat(remote.dispatched).isEqualTo(240)
        }
    }

    @Test fun `unknown command scope remains unknown even if its shell returned a completion file`() {
        create().use { journal ->
            val remote = FakeRemote(file()).apply { loseResponse = true }
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            assertThrows(InterruptedException::class.java) { hdc.shell("fixture-target", listOf("sh", "-c", "sleep 20 &")) }
            journal.seal()
            assertThat(journal.recover(hdc).issues.map { it.code }).contains("UNSUPPORTED_RECOVERY_SCOPE")
            assertThat(remote.cleanup).isEmpty()
        }
    }

    @Test fun `process-start crash window without a native identity is never inferred stopped`() {
        create().close()
        val tree = mapper.readTree(file().toFile())
        (tree["hostCalls"] as com.fasterxml.jackson.databind.node.ArrayNode).add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            HarmonyOSRecoveryJournal.HostCall(UUID.randomUUID().toString())))
        mapper.writeValue(file().toFile(), tree)
        HarmonyOSRecoveryJournal.open(file()).use { journal ->
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> error("No remote query before host stop is confirmed") })
            assertThat(journal.recover(hdc).issues.map { it.code }).contains("HOST_PROCESS_UNCONFIRMED")
        }
    }

    @Test fun `a live recorded host process blocks recovery even with remote completion evidence`() {
        create().close()
        val tree = mapper.readTree(file().toFile())
        val current = ProcessHandle.current()
        val call = HarmonyOSRecoveryJournal.HostCall(UUID.randomUUID().toString(), mutableListOf(
            HarmonyOSRecoveryJournal.HostProcess(current.pid(), current.info().startInstant().orElseThrow().toString())))
        (tree["hostCalls"] as com.fasterxml.jackson.databind.node.ArrayNode).add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(call))
        mapper.writeValue(file().toFile(), tree)
        HarmonyOSRecoveryJournal.open(file()).use { journal ->
            val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> error("Must not query/clean while a native invocation is active") })
            assertThat(journal.recover(hdc).issues.map { it.code }).contains("HOST_PROCESS_UNCONFIRMED")
            assertThat(current.isAlive).isTrue()
        }
    }

    @Test fun `a recorded child exiting during the bounded close grace is confirmed`() {
        create().close()
        val child = ProcessBuilder(HdcProcessTestChild.command("sleep", directory.resolve("grace-child.pid").toString())).start()
        try {
            val identity = HarmonyOSRecoveryJournal.HostProcess(
                child.pid(), child.info().startInstant().orElseThrow().toString())
            val tree = mapper.readTree(file().toFile())
            val call = HarmonyOSRecoveryJournal.HostCall(UUID.randomUUID().toString(), mutableListOf(identity), returned = true)
            (tree["hostCalls"] as com.fasterxml.jackson.databind.node.ArrayNode).add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(call))
            mapper.writeValue(file().toFile(), tree)
            HarmonyOSRecoveryJournal.open(file()).use { journal ->
                val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> error("No device query is needed") })
                assertThat(journal.recover(hdc, Duration.ofSeconds(2), hostProcessGrace = Duration.ofMillis(100)).issues.map { it.code })
                    .contains("HOST_PROCESS_UNCONFIRMED")
                assertThat(child.isAlive).isTrue()
                val stopper = Thread {
                    Thread.sleep(200)
                    child.destroyForcibly().waitFor()
                }
                stopper.start()
                try {
                    assertThat(journal.recover(hdc, Duration.ofSeconds(3), hostProcessGrace = Duration.ofSeconds(2)).state)
                        .isEqualTo("complete")
                } finally { stopper.join(3000) }
            }
            assertThat(mapper.readTree(file().toFile())["hostCalls"][0]["stopped"].asBoolean()).isTrue()
        } finally { if (child.isAlive) child.destroyForcibly().waitFor() }
    }

    @Test fun `ownership corruption is rejected before any recovery action`() {
        create().close()
        val tree = mapper.readTree(file().toFile()) as com.fasterxml.jackson.databind.node.ObjectNode
        tree.put("remoteRoot", "/data/local/tmp/another-tool")
        mapper.writeValue(file().toFile(), tree)
        assertThrows(IllegalArgumentException::class.java) { HarmonyOSRecoveryJournal.open(file()) }
    }

    @Test fun `missing duplicate trailing or coerced evidence cannot open as an empty journal`() {
        create().close()
        val valid = Files.readString(file())
        val missing = mapper.readTree(valid) as com.fasterxml.jackson.databind.node.ObjectNode
        missing.remove("operations")
        val coerced = mapper.readTree(valid) as com.fasterxml.jackson.databind.node.ObjectNode
        coerced.put("rootRemoved", "true")
        for (invalid in listOf(mapper.writeValueAsString(missing), mapper.writeValueAsString(coerced),
            valid + " {}", valid.replaceFirst("{", "{\"rootRemoved\":false,"))) {
            Files.writeString(file(), invalid)
            assertThrows(Exception::class.java) { HarmonyOSRecoveryJournal.open(file()) }
        }
    }

    @Test fun `removed markers cannot substitute for an absent command completion proof`() {
        create().use { journal ->
            val remote = FakeRemote(file()).apply { loseResponse = true }
            assertThrows(InterruptedException::class.java) {
                HdcClient("fake", remote).withRecoveryJournal(journal).shell("fixture-target", listOf("uitest", "dumpLayout"))
            }
        }
        val tree = mapper.readTree(file().toFile())
        (tree["operations"][0] as com.fasterxml.jackson.databind.node.ObjectNode).put("markerRemoved", true)
        mapper.writeValue(file().toFile(), tree)
        assertThrows(IllegalArgumentException::class.java) { HarmonyOSRecoveryJournal.open(file()) }
    }

    @Test fun `native process is terminated if recording its identity fails`() {
        val handle = AtomicReference<HarmonyOSRecoveryJournal.HostProcess>()
        assertThrows(IllegalStateException::class.java) {
            ProcessHdcCommandRunner().runObserved(HdcProcessTestChild.command("sleep", directory.resolve("child.pid").toString()), Duration.ofSeconds(10)) {
                handle.set(it)
                error("Journal write failed")
            }
        }
        assertThat(ProcessHandle.of(handle.get().pid).map { it.isAlive }.orElse(false)).isFalse()
    }

    @Test fun `legacy preexisting process entries stay recorded but cannot block confirmed owned process exit`() {
        create().close()
        val child = ProcessBuilder(HdcProcessTestChild.command("failure")).start()
        val root = HarmonyOSRecoveryJournal.HostProcess(child.pid(), child.info().startInstant().orElseThrow().toString())
        child.waitFor()
        val current = ProcessHandle.current()
        val older = HarmonyOSRecoveryJournal.HostProcess(current.pid(), current.info().startInstant().orElseThrow().toString())
        val tree = mapper.readTree(file().toFile())
        val call = HarmonyOSRecoveryJournal.HostCall(UUID.randomUUID().toString(), mutableListOf(root, older))
        (tree["hostCalls"] as com.fasterxml.jackson.databind.node.ArrayNode).add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(call))
        mapper.writeValue(file().toFile(), tree)

        HarmonyOSRecoveryJournal.open(file()).use { journal ->
            assertThat(journal.recover(HdcClient("fake", HdcCommandRunner { _, _ -> error("No device work exists") })).state).isEqualTo("complete")
        }
        val retained = mapper.readTree(file().toFile())["hostCalls"][0]["processes"]
        assertThat(retained.size()).isEqualTo(2)
        assertThat(retained[1]["pid"].asLong()).isEqualTo(current.pid())
        assertThat(current.isAlive).isTrue()
    }

    @Test fun `missing native creation time remains unknown rather than excluding a possible owner`() {
        create().close()
        val tree = mapper.readTree(file().toFile())
        val call = HarmonyOSRecoveryJournal.HostCall(UUID.randomUUID().toString(), mutableListOf(
            HarmonyOSRecoveryJournal.HostProcess(ProcessHandle.current().pid(), null)))
        (tree["hostCalls"] as com.fasterxml.jackson.databind.node.ArrayNode).add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(call))
        mapper.writeValue(file().toFile(), tree)
        HarmonyOSRecoveryJournal.open(file()).use { journal ->
            assertThat(journal.recover(HdcClient("fake", HdcCommandRunner { _, _ -> error("No query permitted") })).issues.map { it.code })
                .contains("HOST_PROCESS_UNCONFIRMED")
        }
    }

    @Test fun `an identity lost during exit can recover after the recorded PID has disappeared`() {
        create().close()
        val child = ProcessBuilder(HdcProcessTestChild.command("sleep", directory.resolve("exit-race.pid").toString())).start()
        try {
            val known = HarmonyOSRecoveryJournal.HostProcess(child.pid(), child.info().startInstant().orElseThrow().toString())
            val unknown = HarmonyOSRecoveryJournal.HostProcess(child.pid(), null)
            val tree = mapper.readTree(file().toFile())
            val call = HarmonyOSRecoveryJournal.HostCall(UUID.randomUUID().toString(), mutableListOf(known, unknown))
            (tree["hostCalls"] as com.fasterxml.jackson.databind.node.ArrayNode).add(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(call))
            mapper.writeValue(file().toFile(), tree)
            HarmonyOSRecoveryJournal.open(file()).use { journal ->
                val hdc = HdcClient("fake", HdcCommandRunner { _, _ -> error("No device work exists") })
                assertThat(journal.recover(hdc).issues.map { it.code }).contains("HOST_PROCESS_UNCONFIRMED")
                child.destroyForcibly().waitFor()
                assertThat(journal.recover(hdc).state).isEqualTo("complete")
            }
            assertThat(mapper.readTree(file().toFile())["hostCalls"][0]["processes"].size()).isEqualTo(2)
        } finally {
            child.destroyForcibly().waitFor()
        }
    }

    @Test fun `reviewed legacy fault query requires exact proof and never redispatches`() {
        create().use { journal ->
            val remote = FakeRemote(file()).apply { loseResponse = true; proofAvailable = false }
            val hdc = HdcClient("fake", remote).withRecoveryJournal(journal)
            hdc.allocateTemporaryFile("layout", "json")
            assertThrows(InterruptedException::class.java) { hdc.shell("fixture-target", listOf("sh", "-c", "hisysevent -l")) }
            journal.seal()
            assertThat(journal.recover(hdc).issues.map { it.code }).contains("UNSUPPORTED_RECOVERY_SCOPE")
            val state = mapper.readTree(file().toFile()); val op = state["operations"].first()
            val trace = directory.resolve("reviewed.log")
            val line = mapOf("schemaVersion" to 1, "operationId" to op["id"].asText(), "callId" to UUID.randomUUID().toString(),
                "hostCallId" to state["hostCalls"].last()["id"].asText(), "category" to "FAULT_QUERY")
            Files.writeString(trace, "MAESTRO_HDC_CALL " + mapper.writeValueAsString(line + ("hostCallId" to null)) + "\nMAESTRO_HDC_CALL " + mapper.writeValueAsString(line))
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(trace)).joinToString("") { "%02x".format(it) }
            fun recover(commandHash: String = op["sha256"].asText()): RecoveryReport =
                HarmonyOSDiagnosticCompletionRecovery.withReviewedFaultQuery("fixture-target", journal.ownerId, op["id"].asText(), commandHash,
                    trace, hash, java.util.function.Supplier { journal.recover(hdc) })
            assertThat(recover().state).isEqualTo("unknown")
            assertThat(remote.cleanup).isEmpty()
            remote.proofAvailable = true; remote.foreignProof = true
            assertThat(recover().state).isEqualTo("unknown")
            assertThat(remote.cleanup).isEmpty()
            remote.foreignProof = false
            assertThat(recover("0".repeat(64)).state).isEqualTo("unknown")
            assertThat(remote.cleanup).isEmpty()
            assertThat(recover().state).isEqualTo("complete")
            assertThat(remote.dispatched).isEqualTo(1)
            val final = mapper.readTree(file().toFile())["operations"].first()
            assertThat(final["recoverable"].asBoolean()).isFalse()
            assertThat(final["completed"].asBoolean()).isTrue()
            assertThat(remote.cleanup).hasSize(1)
        }
    }

    @Test fun `reviewed fault scope rejects changed traces shell categories and mismatched owners`() {
        val owner = UUID.randomUUID().toString(); val operation = UUID.randomUUID().toString()
        val trace = directory.resolve("trace.log")
        val event = mutableMapOf<String, Any>("schemaVersion" to 1, "operationId" to operation, "callId" to UUID.randomUUID().toString(),
            "hostCallId" to UUID.randomUUID().toString(), "category" to "SHELL_SCRIPT")
        fun write() { Files.writeString(trace, "MAESTRO_HDC_CALL " + mapper.writeValueAsString(event)) }
        fun hash() = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(trace)).joinToString("") { "%02x".format(it) }
        write()
        assertThrows(IllegalArgumentException::class.java) {
            HarmonyOSDiagnosticCompletionRecovery.withReviewedFaultQuery("fixture-target", owner, operation, "1".repeat(64), trace, hash(),
                java.util.function.Supplier { error("No cleanup allowed") })
        }
        event["category"] = "FAULT_QUERY"; write(); val expected = hash(); Files.writeString(trace, "changed")
        assertThrows(IllegalArgumentException::class.java) {
            HarmonyOSDiagnosticCompletionRecovery.withReviewedFaultQuery("fixture-target", owner, operation, "1".repeat(64), trace, expected,
                java.util.function.Supplier { error("No cleanup allowed") })
        }
        write()
        assertThrows(IllegalStateException::class.java) {
            HarmonyOSDiagnosticCompletionRecovery.withReviewedFaultQuery("fixture-target", owner, operation, "1".repeat(64), trace, hash(),
                java.util.function.Supplier {
                    val hosts = listOf(event["hostCallId"] as String)
                    assertThat(HarmonyOSDiagnosticCompletionRecovery.accepts("fixture-target", owner, operation, "1".repeat(64), hosts)).isTrue()
                    assertThat(HarmonyOSDiagnosticCompletionRecovery.accepts("other-target", owner, operation, "1".repeat(64), hosts)).isFalse()
                    assertThat(HarmonyOSDiagnosticCompletionRecovery.accepts("fixture-target", UUID.randomUUID().toString(), operation, "1".repeat(64), hosts)).isFalse()
                    assertThat(HarmonyOSDiagnosticCompletionRecovery.accepts("fixture-target", owner, UUID.randomUUID().toString(), "1".repeat(64), hosts)).isFalse()
                    assertThat(HarmonyOSDiagnosticCompletionRecovery.accepts("fixture-target", owner, operation, "1".repeat(64), listOf(UUID.randomUUID().toString()))).isFalse()
                    assertThat(java.util.concurrent.CompletableFuture.supplyAsync {
                        HarmonyOSDiagnosticCompletionRecovery.accepts("fixture-target", owner, operation, "1".repeat(64), hosts)
                    }.get()).isFalse()
                    error("Scope must be cleared after exceptions")
                })
        }
        assertThat(HarmonyOSDiagnosticCompletionRecovery.accepts("fixture-target", owner, operation, "1".repeat(64), listOf(event["hostCallId"] as String))).isFalse()
    }

    private class FakeRemote(private val journal: Path) : HdcCommandRunner {
        var dispatched = 0
        var loseResponse = false
        var proofAvailable = true
        var foreignProof = false
        var remoteStatus = 0
        var output = "device output"
        var failCleanup = false
        var failCleanupCall: Int? = null
        var proofDelayMillis = 0L
        val cleanup = mutableListOf<String>()
        private val mapper = jacksonObjectMapper()
        private var proof: String? = null
        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            val script = command.last()
            if (script.contains("MAESTRO_RECOVERY_")) {
                val state = mapper.readTree(journal.toFile())
                val operation = state["operations"].last()
                check(!state["hostCalls"].last()["returned"].asBoolean()) { "Dispatch was not write-ahead recorded" }
                proof = listOf(state["generation"], state["ownerId"], operation["id"], operation["sha256"], operation["token"])
                    .joinToString(":") { it.asText() } + ":$remoteStatus"
                dispatched++
                if (loseResponse) throw InterruptedException("Lost host response; remote command may still be finishing")
                return HdcCommandResult(0, "$output\nMAESTRO_RECOVERY_${operation["token"].asText().replace("-", "")}=$remoteStatus\n", "")
            }
            if (script.contains("cat ")) {
                if (proofDelayMillis > 0) Thread.sleep(proofDelayMillis)
                return HdcCommandResult(0, if (!proofAvailable) "" else if (foreignProof) proof!!.replaceFirst(':', '-') else proof!!, "")
            }
            cleanup += script
            if (failCleanup || cleanup.size == failCleanupCall) return HdcCommandResult(0, "", "")
            val nonce = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").findAll(script).last().value
            return HdcCommandResult(0, nonce, "")
        }
    }
}
