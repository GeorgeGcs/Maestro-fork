package maestro.harmonyos.layout

import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandException
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcTimeoutException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class HierarchyReaderTest {
    @Test
    fun `dump and transfer share the remaining budget instead of equal time slices`() {
        val runner = LayoutRunner()
        val delayed = HdcCommandRunner { command, timeout ->
            if (command.last().startsWith("'uitest' 'dumpLayout'")) Thread.sleep(20)
            runner.run(command, timeout)
        }
        HierarchyReader(HdcClient("hdc", delayed), "USB-TEST", totalTimeout = Duration.ofSeconds(10)).read()
        assertThat(runner.calls.first().timeout.toMillis()).isGreaterThan(5000)
        assertThat(runner.calls[1].timeout).isLessThan(runner.calls.first().timeout)
        assertThat(runner.calls.last().timeout).isEqualTo(Duration.ofSeconds(2))
        assertRemoved(runner)
    }

    @Test
    fun `exhausted total read budget still allows bounded remote cleanup`() {
        val runner = LayoutRunner()
        val delayed = HdcCommandRunner { command, timeout ->
            if (command.last().startsWith("'uitest' 'dumpLayout'")) Thread.sleep(timeout.toMillis() + 10)
            runner.run(command, timeout)
        }
        assertThrows(HdcTimeoutException::class.java) {
            HierarchyReader(HdcClient("hdc", delayed), "USB-TEST", totalTimeout = Duration.ofMillis(200)).read()
        }
        assertThat(runner.calls).hasSize(2)
        assertThat(runner.localPaths).isEmpty()
        assertThat(runner.calls.last().command.last()).startsWith("'rm' '-f'")
        assertThat(runner.calls.last().timeout).isEqualTo(Duration.ofSeconds(2))
    }

    @Test
    fun `reads fresh layout with target bound to dump transfer and cleanup`() {
        val runner = LayoutRunner()
        val reader = reader(runner)
        val root = reader.read()

        assertThat(root.attributes["text"]).isEqualTo("新的布局")
        assertThat(runner.calls.map { it.command.subList(1, 3) })
            .containsExactly(listOf("-t", "USB-TEST"), listOf("-t", "USB-TEST"), listOf("-t", "USB-TEST")).inOrder()
        val remote = runner.remotePaths.single()
        assertThat(remote).matches("/data/local/tmp/maestro-layout-[a-f0-9-]{36}\\.json")
        assertThat(runner.calls.first().command.last()).isEqualTo("'uitest' 'dumpLayout' '-p' '$remote'")
        assertThat(runner.calls[1].command.subList(3, 6)).containsExactly("file", "recv", remote).inOrder()
        assertThat(runner.calls.last().command.last()).isEqualTo("'rm' '-f' '$remote'")
        assertThat(runner.calls.last().timeout).isEqualTo(Duration.ofSeconds(2))
        assertRemoved(runner)
    }

    @Test
    fun `does not require a particular success sentence`() {
        val runner = LayoutRunner(dumpResult = HdcCommandResult(0, "布局已保存", ""))
        assertThat(reader(runner).read().attributes["text"]).isEqualTo("新的布局")
        assertRemoved(runner)
    }

    @Test
    fun `success without a newly received file fails`() {
        val runner = LayoutRunner(writeFile = false)
        assertThrows(HierarchyReadException::class.java) { reader(runner).read() }
        assertThat(runner.calls).hasSize(3)
        assertRemoved(runner)
    }

    @Test
    fun `a later failed transfer cannot reuse a previous layout`() {
        val runner = LayoutRunner()
        val reader = reader(runner)
        reader.read()
        runner.writeFile = false
        assertThrows(HierarchyReadException::class.java) { reader.read() }

        assertThat(runner.remotePaths.toSet()).hasSize(2)
        assertThat(runner.localPaths.toSet()).hasSize(2)
        assertRemoved(runner)
    }

    @Test
    fun `dump command failure still cleans up and does not receive a file`() {
        val runner = LayoutRunner(dumpResult = HdcCommandResult(4, "", "UITest unavailable"))
        val error = assertThrows(HdcCommandException::class.java) { reader(runner).read() }
        assertThat(error.result.exitCode).isEqualTo(4)
        assertThat(runner.calls).hasSize(2)
        assertThat(runner.localPaths).isEmpty()
        assertThat(runner.calls.last().command.last()).startsWith("'rm' '-f'")
    }

    @Test
    fun `device failure text with zero host exit does not trigger a transfer`() {
        val runner = LayoutRunner(dumpResult = HdcCommandResult(0, "DumpLayout failed:permission denied", ""))
        assertThrows(HierarchyReadException::class.java) { reader(runner).read() }
        assertThat(runner.calls).hasSize(2)
        assertThat(runner.localPaths).isEmpty()
    }

    @Test
    fun `partial transfer failure preserves the original error and removes the partial file`() {
        val runner = LayoutRunner(receiveResult = HdcCommandResult(1, "", "transfer interrupted"))
        val error = assertThrows(HdcCommandException::class.java) { reader(runner).read() }
        assertThat(error.result.stderr).isEqualTo("transfer interrupted")
        assertRemoved(runner)
    }

    @Test
    fun `empty oversized invalid encoding and malformed layouts fail and clean up`() {
        listOf(byteArrayOf(), ByteArray(33) { 65 }, byteArrayOf(0xc3.toByte(), 0x28))
            .forEach { bytes ->
                val runner = LayoutRunner(contents = bytes)
                assertThrows(HierarchyReadException::class.java) { reader(runner, maxLayoutBytes = 32).read() }
                assertRemoved(runner)
            }
        val invalidJson = LayoutRunner(contents = "not-json".toByteArray())
        assertThrows(UitestLayoutException::class.java) { reader(invalidJson).read() }
        assertRemoved(invalidJson)
    }

    @Test
    fun `cleanup failure does not replace a parse failure`() {
        val runner = LayoutRunner(contents = "invalid-json".toByteArray(), cleanupResult = HdcCommandResult(1, "", "denied"))
        val error = assertThrows(UitestLayoutException::class.java) { reader(runner).read() }
        assertThat(error.suppressed).hasLength(1)
        assertThat(error.suppressed.single().message).contains(runner.remotePaths.single())
        assertRemoved(runner)
    }

    @Test
    fun `cleanup failure after a successful parse is reported`() {
        val runner = LayoutRunner(cleanupResult = HdcCommandResult(0, "", "rm: permission denied"))
        val error = assertThrows(HierarchyReadException::class.java) { reader(runner).read() }
        assertThat(error.message).contains("cleanup failed")
        assertThat(error.suppressed.single().message).contains(runner.remotePaths.single())
        assertRemoved(runner)
    }

    @Test
    fun `cleanup interruption is rethrown after removing local files`() {
        val interruption = InterruptedException("cleanup interrupted")
        val runner = LayoutRunner(cleanupInterruption = interruption)
        try {
            val error = assertThrows(InterruptedException::class.java) { reader(runner).read() }
            assertThat(error).isSameInstanceAs(interruption)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(runner.calls).hasSize(3)
            assertThat(error.suppressed.single().message).contains(runner.remotePaths.single())
            assertRemoved(runner)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cleanup interruption does not replace an earlier parse failure`() {
        val interruption = InterruptedException("cleanup interrupted")
        val runner = LayoutRunner(contents = "invalid-json".toByteArray(), cleanupInterruption = interruption)
        try {
            val error = assertThrows(UitestLayoutException::class.java) { reader(runner).read() }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(error.suppressed).asList().contains(interruption)
            assertThat(error.suppressed.last().message).contains(runner.remotePaths.single())
            assertRemoved(runner)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interruption skips remote cleanup and preserves its path for later removal`() {
        val runner = LayoutRunner(interruptTransfer = true)
        try {
            val error = assertThrows(InterruptedException::class.java) { reader(runner).read() }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(runner.calls).hasSize(2)
            assertThat(error.suppressed.single().message).contains(runner.remotePaths.single())
            assertRemoved(runner)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `already interrupted caller does not issue commands`() {
        val runner = LayoutRunner()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { reader(runner).read() }
            assertThat(runner.calls).isEmpty()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    private fun reader(runner: LayoutRunner, maxLayoutBytes: Int = 1024) = HierarchyReader(
        hdc = HdcClient("hdc", runner),
        deviceId = "USB-TEST",
        maxLayoutBytes = maxLayoutBytes,
    )

    private fun assertRemoved(runner: LayoutRunner) {
        runner.localPaths.forEach { path ->
            assertThat(Files.exists(path)).isFalse()
            assertThat(Files.exists(path.parent)).isFalse()
        }
    }

    private data class Call(val command: List<String>, val timeout: Duration)

    private class LayoutRunner(
        private val contents: ByteArray = """{"attributes":{"text":"新的布局"},"children":[]}""".toByteArray(),
        var writeFile: Boolean = true,
        private val dumpResult: HdcCommandResult = HdcCommandResult(0, "", ""),
        private val receiveResult: HdcCommandResult = HdcCommandResult(0, "", ""),
        private val cleanupResult: HdcCommandResult = HdcCommandResult(0, "", ""),
        private val interruptTransfer: Boolean = false,
        private val cleanupInterruption: InterruptedException? = null,
    ) : HdcCommandRunner {
        val calls = mutableListOf<Call>()
        val localPaths = mutableListOf<Path>()
        val remotePaths = mutableListOf<String>()

        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            calls += Call(command, timeout)
            return when {
                command[3] == "shell" && command.last().startsWith("'uitest' 'dumpLayout'") -> {
                    remotePaths += command.last().substringAfter("'-p' '").removeSuffix("'")
                    dumpResult
                }
                command[3] == "file" -> {
                    val path = Path.of(command.last())
                    localPaths.add(path)
                    assertThat(Files.exists(path)).isFalse()
                    if (writeFile) Files.write(path, contents)
                    if (interruptTransfer) throw InterruptedException("transfer interrupted")
                    receiveResult
                }
                command[3] == "shell" && command.last().startsWith("'rm' '-f'") -> {
                    cleanupInterruption?.let { throw it }
                    cleanupResult
                }
                else -> error("Unexpected command: $command")
            }
        }
    }
}
