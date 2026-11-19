package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcTimeoutException
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import javax.imageio.ImageIO

/** Real driver, device adapter and journal; only the HDC transport is simulated. */
class HarmonyOSQueryRecoveryTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(strings = ["dumpLayout", "screenCap"])
    fun `successful rm cannot hide an unconfirmed query`(query: String) {
        val runner = QueryRunner(query)
        HarmonyOSRecoveryJournal.create(directory.resolve("journal.json"), TARGET, UUID.randomUUID().toString()).use { journal ->
            val hdc = HdcClient("simulated-hdc", runner).withRecoveryJournal(journal)
            val driver = HarmonyOSDriver(TARGET, hdc)
            driver.open()
            assertThat(journal.snapshot().pendingOperations).isEqualTo(0)
            runner.inject = true

            val failure = assertThrows(HdcTimeoutException::class.java) {
                if (query == "dumpLayout") driver.waitForAppToSettle(null, null, 1000)
                else driver.waitUntilScreenIsStatic(1000)
            }
            assertThat(failure).isSameInstanceAs(runner.failure)
            assertThat(failure.suppressed).isEmpty()
            assertThat(runner.cleanupAcknowledgements).isEqualTo(1)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
            val dispatches = runner.calls
            assertThat(driver.isShutdown()).isTrue()
            assertThrows(HdcTimeoutException::class.java) { driver.inputText("completion hook") }
            assertThrows(HdcTimeoutException::class.java) { driver.takeScreenshot(Buffer(), false) }
            driver.close()
            assertThrows(HdcTimeoutException::class.java) { driver.open() }
            assertThat(runner.calls).isEqualTo(dispatches)
            journal.seal()
            assertThat(journal.snapshot().pendingOperations).isEqualTo(1)
            assertThat(journal.snapshot().issues.map { it.code }).contains("REMOTE_COMPLETION_UNCONFIRMED")
        }
    }

    private class QueryRunner(private val query: String) : HdcCommandRunner {
        var inject = false
        var calls = 0
        var cleanupAcknowledgements = 0
        var failure: HdcTimeoutException? = null

        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            calls++
            if (command.drop(1) == listOf("list", "targets")) return HdcCommandResult(0, "$TARGET\n", "")
            if (command.size == 7 && command.subList(3, 5) == listOf("file", "recv")) {
                val local = Path.of(command[6])
                if (command[5].endsWith(".png")) {
                    ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", local.toFile())
                } else {
                    Files.writeString(local, """{"attributes":{"text":"synthetic"},"children":[]}""")
                }
                return HdcCommandResult(0, "", "")
            }
            check(command.size == 5 && command[3] == "shell") { "Unexpected simulated HDC call" }
            val wrapper = command[4]
            val outer = checkNotNull(Regex("MAESTRO_RECOVERY_[0-9a-f]{32}=").find(wrapper)).value
            if (inject && wrapper.contains(query)) {
                // Preserve the real journal entry; only the transport response is injected.
                check(timeout > Duration.ofSeconds(10)) { "Wait window must not truncate the query budget" }
                val error = HdcTimeoutException(timeout)
                failure = error
                throw error
            }
            check(listOf("dumpLayout", "screenCap", "DisplayManagerService", "rm").any(wrapper::contains))
            if (failure != null && wrapper.contains("rm")) cleanupAcknowledgements++
            val inner = Regex("MAESTRO_EXIT_[0-9a-f]{32}=").find(wrapper)?.value
            return HdcCommandResult(0, (inner?.let { "\n${it}0\n" } ?: "") + "\n${outer}0\n", "")
        }
    }

    companion object { private const val TARGET = "simulated-recovery-target" }
}
