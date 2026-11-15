package maestro.harmonyos.hdc

import com.google.common.truth.Truth.assertThat
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

class ProcessHdcCommandRunnerTest {
    @Test
    fun `captures stdout stderr and nonzero exit independently`() {
        val result = ProcessHdcCommandRunner().run(HdcProcessTestChild.command("failure"), Duration.ofSeconds(10))

        assertThat(result.exitCode).isEqualTo(7)
        assertThat(result.stdout.trim()).isEqualTo("command output")
        assertThat(result.stderr.trim()).isEqualTo("command error")
    }

    @Test
    fun `host arguments remain literal without a command shell`() {
        val arguments = listOf(
            "space separated", "a'\"b", "\$HOME; echo injected", "", "line\nbreak",
            "\"", "\"\"", "\"quoted\"", "plain\\", "path with space\\", "\\", "\\\\",
            " leading ", "\ttab\t", "line\r\nbreak",
        ) + (0..5).flatMap { count ->
            val slashes = "\\".repeat(count)
            listOf("before${slashes}\"after", "before${slashes}\"", "tail with space$slashes")
        }
        val result = ProcessHdcCommandRunner().run(
            HdcProcessTestChild.command("arguments", *arguments.toTypedArray()), Duration.ofSeconds(10),
        )
        val decoded = result.stdout.lineSequence().filter { it.startsWith("arg:") }
            .map { String(Base64.getDecoder().decode(it.removePrefix("arg:")), Charsets.UTF_8) }.toList()

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(decoded).containsExactlyElementsIn(arguments).inOrder()
    }

    @Test
    fun `preserves Unicode arguments with a Unicode-capable child process`() {
        val text = "中文"
        if (!System.getProperty("os.name").startsWith("Windows")) {
            val result = ProcessHdcCommandRunner().run(HdcProcessTestChild.command("arguments", text), Duration.ofSeconds(10))
            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.stdout.trim()).isEqualTo("arg:" + Base64.getEncoder().encodeToString(text.toByteArray()))
            return
        }

        // The Windows Java 17 launcher converts its own argv through the ANSI code page.
        // Use PowerShell 7 to check the Unicode actually sent by the Windows process runner.
        TempFileHandler().use { temporary ->
            val script = temporary.createTempFile("hdc-unicode", ".ps1")
            script.writeText("""[Console]::WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}args[0])))""")
            val result = ProcessHdcCommandRunner().run(
                listOf("pwsh.exe", "-NoProfile", "-NonInteractive", "-File", script.absolutePath, text), Duration.ofSeconds(10),
            )
            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.stdout.trim()).isEqualTo(Base64.getEncoder().encodeToString(text.toByteArray()))
        }
    }

    @Test
    fun `drains both streams while process is running`() {
        val result = ProcessHdcCommandRunner().run(HdcProcessTestChild.command("flood"), Duration.ofSeconds(10))

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.length).isEqualTo(262144)
        assertThat(result.stderr.length).isEqualTo(262144)
    }

    @Test
    fun `output limit fails explicitly instead of truncating a result`() {
        assertThrows(HdcOutputLimitException::class.java) {
            ProcessHdcCommandRunner(maxOutputBytes = 1024).run(
                HdcProcessTestChild.command("flood"), Duration.ofSeconds(10),
            )
        }
    }

    @Test
    fun `timeout terminates the command and its child`() {
        TempFileHandler().use { temporary ->
            val parentPid = temporary.createTempFile("hdc-parent", ".pid")
            val childPid = temporary.createTempFile("hdc-child", ".pid")
            try {
                assertThrows(HdcTimeoutException::class.java) {
                    ProcessHdcCommandRunner().run(
                        HdcProcessTestChild.command("spawn", parentPid.path, childPid.path), Duration.ofSeconds(3),
                    )
                }
                assertStopped(parentPid)
                assertStopped(childPid)
            } finally {
                stopFromPidFile(parentPid)
                stopFromPidFile(childPid)
            }
        }
    }

    @Test
    fun `interruption terminates the command and preserves interrupted status`() {
        TempFileHandler().use { temporary ->
            val pidFile = temporary.createTempFile("hdc-interrupted", ".pid")
            val thrown = AtomicReference<Throwable>()
            val interrupted = AtomicBoolean()
            val thread = Thread {
                try {
                    ProcessHdcCommandRunner().run(
                        HdcProcessTestChild.command("sleep", pidFile.path), Duration.ofSeconds(30),
                    )
                } catch (e: Throwable) {
                    thrown.set(e)
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            }
            thread.start()
            try {
                waitUntil { pidFile.readText().trim().toLongOrNull() != null }
                thread.interrupt()
                thread.join(5000)

                assertThat(thread.isAlive).isFalse()
                assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
                assertThat(interrupted.get()).isTrue()
                assertStopped(pidFile)
            } finally {
                thread.interrupt()
                stopFromPidFile(pidFile)
                thread.join(5000)
            }
        }
    }

    @Test
    fun `missing executable and invalid inputs fail clearly`() {
        assertThrows(HdcException::class.java) {
            ProcessHdcCommandRunner().run(listOf("maestro-nonexistent-hdc-test-executable"), Duration.ofSeconds(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessHdcCommandRunner().run(HdcProcessTestChild.command("failure"), Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessHdcCommandRunner().run(listOf("hdc", "\u0000"), Duration.ofSeconds(1))
        }
    }

    private fun assertStopped(pidFile: File) {
        val pid = pidFile.readText().trim().toLong()
        waitUntil { !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }
    }

    private fun stopFromPidFile(pidFile: File) {
        pidFile.readText().trim().toLongOrNull()?.let { pid ->
            ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertThat(condition()).isTrue()
    }
}

/** Local JVM fixture; never invokes HDC or accesses a device. */
object HdcProcessTestChild {
    fun command(vararg arguments: String): List<String> {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java").toString()
        val classpath = listOf(HdcProcessTestChild::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(File.pathSeparator)
        return listOf(java, "-Dfile.encoding=UTF-8", "-cp", classpath, HdcProcessTestChild::class.java.name) + arguments
    }

    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments[0]) {
            "failure" -> {
                println("command output")
                System.err.println("command error")
                exitProcess(7)
            }
            "arguments" -> arguments.drop(1).forEach {
                println("arg:" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)))
            }
            "flood" -> {
                val bytes = ByteArray(4096) { 'x'.code.toByte() }
                repeat(64) {
                    System.out.write(bytes)
                    System.err.write(bytes)
                }
            }
            "sleep" -> {
                File(arguments[1]).writeText(ProcessHandle.current().pid().toString())
                Thread.sleep(30000)
            }
            "spawn" -> {
                File(arguments[1]).writeText(ProcessHandle.current().pid().toString())
                ProcessBuilder(command("sleep", arguments[2])).inheritIO().start()
                Thread.sleep(30000)
            }
            "spawn-and-exit" -> {
                // The descendant retains the parent's pipe handles after the root exits.
                // This exercises output-drain timeout independently from waitFor timeout.
                File(arguments[1]).writeText(ProcessHandle.current().pid().toString())
                ProcessBuilder(command("sleep", arguments[2])).inheritIO().start()
                val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                while (File(arguments[2]).readText().trim().toLongOrNull() == null && System.nanoTime() < deadline) Thread.sleep(10)
                check(File(arguments[2]).readText().trim().toLongOrNull() != null)
                // Give the owner time to observe the descendant while the root is alive.
                Thread.sleep(500)
            }
            else -> error("Unknown fixture mode")
        }
    }
}
