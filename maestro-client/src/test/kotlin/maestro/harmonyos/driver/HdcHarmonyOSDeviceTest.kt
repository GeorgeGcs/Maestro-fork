package maestro.harmonyos.driver

import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandException
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcTimeoutException
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HdcHarmonyOSDeviceTest {
    @Test
    fun `permissions require API 26 a debug bundle a user grant and explicit state readback`() {
        val permission = "ohos.permission.CAMERA"
        var api = "25"
        var debug = false
        var grantMode = "USER_GRANT"
        var state = -1
        var writes = 0
        var changeReported = true
        val device = device { args, _ ->
            val input = args.last()
            val output = when {
                "const.ohos.apiversion" in input -> api
                "atm" in input && "dump" in input -> """{"permissionName":"$permission","grantMode":"$grantMode"}"""
                "bm" in input && "dump" in input -> """
                    {"name":"org.example.demo","applicationInfo":{"appProvisionType":"${if (debug) "debug" else "release"}",
                    "debug":$debug,"accessTokenId":4000000000},"reqPermissions":["$permission"],"reqPermissionStates":[$state]}
                """.trimIndent()
                else -> {
                    assertThat(input).contains("atm")
                    assertThat(input).contains("perm")
                    assertThat(input).contains("4000000000")
                    assertThat(input).contains(permission)
                    writes++
                    if (changeReported) state = if ("-g" in input) 0 else -1
                    "Success"
                }
            }
            HdcCommandResult(0, "$output\n${marker(args)}0\n", "")
        }
        fun change(allow: Boolean) = device.setAppPermission("org.example.demo", permission, allow, TIMEOUT)
        assertThrows(UnsupportedOperationException::class.java) { change(true) }
        api = "26"
        assertThrows(UnsupportedOperationException::class.java) { change(true) }
        debug = true
        grantMode = "SYSTEM_GRANT"
        assertThrows(UnsupportedOperationException::class.java) { change(true) }
        assertThat(writes).isEqualTo(0)
        grantMode = "USER_GRANT"
        change(true)
        change(false)
        assertThat(state).isEqualTo(-1)
        changeReported = false
        assertThrows(IllegalStateException::class.java) { change(true) }
    }

    @Test
    fun `preparing a pinned HAP verifies the bundle and version and requires opt in to replace`() {
        TempFileHandler().use { files ->
            val dir = files.createTempDirectory("maestro-hap-test").toPath()
            val source = dir.resolve("fixture.hap")
            val bundle = "org.example.demo"
            ZipOutputStream(Files.newOutputStream(source)).use { archive ->
                archive.putNextEntry(ZipEntry("module.json"))
                archive.write("""{"app":{"bundleName":"$bundle","versionCode":7}}""".toByteArray())
                archive.closeEntry()
            }
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))
            assertThrows(IllegalArgumentException::class.java) {
                HarmonyOSHap.stage(source, dir.resolve("rejected.hap"), bundle, "0".repeat(64))
            }
            val hap = HarmonyOSHap.stage(source, dir.resolve("staged.hap"), bundle, digest)
            var installed = false
            var installs = 0
            var reject = false
            val device = device { args, _ ->
                if (args.getOrNull(3) == "install") {
                    assertThat(args.last()).isEqualTo(hap.path.toAbsolutePath().toString())
                    assertThat("-r" in args).isEqualTo(installed)
                    installs++
                    if (reject) {
                        HdcCommandResult(1, "[Info]App install path:fixture msg:error: failed to install bundle. code:9568322 test\nAppMod finish\n", "")
                    } else {
                        installed = true
                        HdcCommandResult(0, "[Info]App install path:fixture msg:install bundle successfully.\nAppMod finish\n", "")
                    }
                } else {
                    val output = if ("'-a'" in args.last()) {
                        if (installed) "ID: 100:\n$bundle" else "ID: 100:\n"
                    } else """{"name":"$bundle","versionCode":7}"""
                    HdcCommandResult(0, "$output\n${marker(args)}0\n", "")
                }
            }
            device.installHap(hap, false, TIMEOUT)
            assertThrows(IllegalStateException::class.java) { device.installHap(hap, false, TIMEOUT) }
            assertThat(installs).isEqualTo(1)
            device.installHap(hap, true, TIMEOUT)
            reject = true
            val failure = assertThrows(HdcException::class.java) { device.installHap(hap, true, TIMEOUT) }
            assertThat(failure.message).contains("9568322")
        }
    }

    @Test
    fun `preview requests bounded pixels and explicit screenshots keep the original backend`() {
        val commands = mutableListOf<String>()
        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(960, 630, 1), "png", it)
        }.toByteArray()
        val device = device { command, _ ->
            commands += command.last()
            if (command.getOrNull(3) == "file") {
                Files.write(Path.of(command.last()), png)
                HdcCommandResult(0, "FileTransfer finish", "")
            } else {
                val output = if ("hidumper" in command.last()) """
                    [SCREEN SESSION]
                    DisplayId: 0
                    activeModes<id, W, H, RS>: 512, 2800, 1840, 60
                    Rotation: 0
                """.trimIndent() else ""
                HdcCommandResult(0, "$output\n${marker(command)}0\n", "")
            }
        }
        assertThat(device.previewFrame(TIMEOUT)).isEqualTo(png)
        assertThat(commands.any { "snapshot_display" in it && "'960'" in it && "'630'" in it }).isTrue()
        commands.clear()
        device.screenshot(TIMEOUT)
        assertThat(commands.any { "screenCap" in it }).isTrue()
        assertThat(commands.any { "snapshot_display" in it }).isFalse()
    }

    @Test
    fun `focus follows the window owner rather than the presence of the target process`() {
        var focus = 13
        val device = device { command, _ ->
            val output = when {
                "WindowManagerService" in command.last() -> """
                    WindowName DisplayId Pid WinId Type Mode Flag ZOrd Orientation [ x y w h ]
                    SCB Desktop 0 1623 13 2001 1 0 2 0 [ 0 0 1272 2756 ]
                    componentcollection0 0 20165 68 1 1 0 102 0 [ 0 0 1272 2756 ]
                    Focus window: $focus
                """.trimIndent()
                else -> "PID NAME\n1623 com.ohos.sceneboard\n20165 org.example.demo:ui"
            }
            HdcCommandResult(0, "$output\n${marker(command)}0\n", "")
        }
        assertThat(device.hasAppFocus("org.example.demo", TIMEOUT)).isFalse()
        focus = 68
        assertThat(device.hasAppFocus("org.example.demo", TIMEOUT)).isTrue()
        assertThat(device.hasAppFocus("org.example", TIMEOUT)).isFalse()
    }

    @Test
    fun `empty and populated fault windows each use one all-domain read with the full limit`() {
        val names = listOf("CPP_CRASH", "JS_ERROR", "RUST_PANIC", "CJ_ERROR", "APP_CRASH", "APP_FREEZE")
        val rows = names.mapIndexed { index, name ->
            """{"domain_":"SOURCE_$index","name_":"$name","PACKAGE_NAME":"demo.target","time_":${100 + index}}"""
        }
        val other = """{"name_":"CPP_CRASH","PACKAGE_NAME":"another.app","time_":103}"""
        var populated = false
        val limits = mutableListOf<Int>()
        val budgets = mutableListOf<Duration>()
        val device = device { args, timeout ->
            val command = args.last().replace("'\"'\"'", "'")
            val output = if ("hisysevent" in command) {
                assertThat(command).contains("'-l' '-s' '90' '-e' '110' '-g' 'FAULT'")
                assertThat(command).doesNotContain("'-o'")
                val limit = Regex("""'-m' '(\d+)'""").find(command)!!.groupValues[1].toInt()
                limits += limit
                budgets += timeout
                if (populated) (rows + other).joinToString("\n") else ""
            } else ""
            HdcCommandResult(0, "$output\n${marker(args)}0\n", "")
        }
        assertThat(device.flowFaults("demo.target", 90, 110, TIMEOUT).events).isEmpty()
        assertThat(limits).containsExactly(256)
        limits.clear()
        budgets.clear()
        populated = true
        val report = device.flowFaults("demo.target", 90, 110, TIMEOUT)
        assertThat(limits).containsExactly(256)
        assertThat(budgets.single()).isAtMost(TIMEOUT)
        assertThat(report.events.map { it.event }).containsExactlyElementsIn(names).inOrder()
    }

    @Test
    fun `fault query diagnostics never report invalid output or missing receipts as an empty history`() {
        val row = """{"name_":"JS_ERROR","PACKAGE_NAME":"demo.target","time_":100}"""
        fun read(output: String, stderr: String = "", receipt: Boolean = true) {
            val device = device { args, _ ->
                val isHistory = "hisysevent" in args.last()
                val body = if (isHistory) output else ""
                val status = if (!isHistory || receipt) "\n${marker(args)}0\n" else ""
                HdcCommandResult(0, body + status, if (isHistory) stderr else "")
            }
            assertThrows(HdcException::class.java) { device.flowFaults("demo.target", 90, 110, TIMEOUT) }
        }
        read("failed to query system event: permission denied")
        read("", stderr = "permission denied")
        read("", receipt = false)
        read("{broken")
        read("{}")
        read("$row garbage")
        read(List(257) { row }.joinToString("\n"))
    }

    @Test
    fun `a timed out fault query leaves no pending device operation`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("read-only-diagnostics").toPath()
        HarmonyOSRecoveryJournal.create(directory.resolve("journal.json"), "fixture-device", UUID.randomUUID().toString()).use { journal ->
            val client = HdcClient("fake", HdcCommandRunner { command, timeout ->
                assertThat(command.last()).doesNotContain("MAESTRO_RECOVERY")
                if ("hisysevent" in command.last()) throw HdcTimeoutException(timeout)
                HdcCommandResult(0, "1784272560000\n${marker(command)}0\n", "")
            }).withRecoveryJournal(journal)
            val device = HdcHarmonyOSDevice("fixture-device", client)
            assertThrows(HdcTimeoutException::class.java) {
                device.flowFaults("org.example.demo", 1, 2, TIMEOUT)
            }
            assertThat(journal.snapshot().pendingOperations).isEqualTo(0)
            journal.seal()
            assertThat(journal.snapshot().issues).isEmpty()
        }
    }

    @Test fun `a tracked remote proof does not hide a different host exit failure`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("tracked-host-failure-test").toPath()
        HarmonyOSRecoveryJournal.create(directory.resolve("journal.json"), "fixture-device", UUID.randomUUID().toString()).use { journal ->
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ ->
                val acknowledgement = Regex("MAESTRO_RECOVERY_[a-f0-9]+=").find(command.last())!!.value
                HdcCommandResult(9, "find 0 result\n${marker(command)}255\n\n${acknowledgement}255\n", "host failure")
            }).withRecoveryJournal(journal)
            val error = assertThrows(HdcCommandException::class.java) {
                HdcHarmonyOSDevice("fixture-device", hdc).execute(listOf("mediatool", "query", "example", "-u"), TIMEOUT)
            }
            assertThat(error.result.exitCode).isEqualTo(9)
            assertThat(error.confirmedRemoteExitCode).isEqualTo(255)
        }
    }

    @Test fun `tracked remote no-match exit retains its device meaning after completion proof`() = TempFileHandler().use { files ->
        val directory = files.createTempDirectory("tracked-media-query-test").toPath()
        HarmonyOSRecoveryJournal.create(directory.resolve("journal.json"), "fixture-device", UUID.randomUUID().toString()).use { journal ->
            val hdc = HdcClient("fake", HdcCommandRunner { command, _ ->
                val acknowledgement = Regex("MAESTRO_RECOVERY_[a-f0-9]+=").find(command.last())!!.value
                HdcCommandResult(0, "find 0 result\n${marker(command)}255\n\n${acknowledgement}255\n", "")
            }).withRecoveryJournal(journal)
            val error = assertThrows(HarmonyOSCommandException::class.java) {
                HdcHarmonyOSDevice("fixture-device", hdc).execute(
                    listOf("mediatool", "query", "maestro-recording-${UUID.randomUUID().toString().replace("-", "")}.mp4", "-u"), TIMEOUT)
            }
            assertThat(error.remoteExitCode).isEqualTo(255)
            assertThat(error.stdout).isEqualTo("find 0 result")
            assertThat((error.cause as HdcCommandException).confirmedRemoteExitCode).isEqualTo(255)
            assertThat(journal.snapshot().pendingOperations).isEqualTo(0)
        }
    }

    @Test
    fun `remote failure is detected even when HDC exits zero`() {
        var calls = 0
        val device = device { command, _ ->
            calls++
            HdcCommandResult(0, "injection denied\n${marker(command)}37\n", "device diagnostic")
        }
        val error = assertThrows(HarmonyOSCommandException::class.java) {
            device.execute(listOf("uitest", "uiInput", "text", "hello"), TIMEOUT)
        }
        assertThat(error.remoteExitCode).isEqualTo(37)
        assertThat(error.stdout).isEqualTo("injection denied")
        assertThat(error.stderr).isEqualTo("device diagnostic")
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `missing or misplaced marker cannot confirm successful execution`() {
        for (output in listOf("done", "MAESTRO_EXIT_deadbeef=0", "done\n%s0\nunexpected trailing output")) {
            val device = device { command, _ -> HdcCommandResult(0, output.replace("%s", marker(command)), "") }
            assertThrows(HarmonyOSCommandException::class.java) {
                device.execute(listOf("uitest", "uiInput", "keyEvent", "Back"), TIMEOUT)
            }
        }
    }

    @Test
    fun `successful remote status does not hide UITest failure output`() {
        val device = device { command, _ ->
            HdcCommandResult(0, "ScreenCap failed: permission denied\n${marker(command)}0\n", "")
        }
        val error = assertThrows(HarmonyOSCommandException::class.java) {
            device.execute(listOf("uitest", "screenCap", "-p", "/data/local/tmp/example.png"), TIMEOUT)
        }
        assertThat(error.message).contains("permission denied")
    }

    @Test
    fun `remote media failure is normalized through the real HDC text heuristic`() {
        val output = "Table Name: Photos\n[FAIL] open source media file failed."
        val device = device { command, _ -> HdcCommandResult(0, "$output\n\n${marker(command)}0\n", "") }
        val error = assertThrows(HarmonyOSCommandException::class.java) {
            device.execute(listOf("mediatool", "recv", "file://media/Photo/1/VID_1/example.mp4", "/data/local/tmp/example.mp4"), TIMEOUT)
        }
        assertThat(error.remoteExitCode).isEqualTo(0)
        assertThat(error.stdout).isEqualTo(output)
        assertThat(error.stderr).isEmpty()
        assertThat(error.cause).isInstanceOf(HdcCommandException::class.java)
        assertThat((error.cause as HdcCommandException).result.exitCode).isEqualTo(0)
    }

    @Test
    fun `normalized failure retains the remote exit and stderr diagnostics`() {
        val device = device { command, _ -> HdcCommandResult(0, "[FAIL] export denied\n${marker(command)}37\n", "permission detail") }
        val error = assertThrows(HarmonyOSCommandException::class.java) {
            device.execute(listOf("mediatool", "recv", "source", "/data/local/tmp/example.mp4"), TIMEOUT)
        }
        assertThat(error.remoteExitCode).isEqualTo(37)
        assertThat(error.stdout).isEqualTo("[FAIL] export denied")
        assertThat(error.stderr).isEqualTo("permission detail")
        assertThat(error.cause).isInstanceOf(HdcCommandException::class.java)
    }

    @Test
    fun `host failure or unconfirmed invocation retains the HDC exception`() {
        val cases = listOf(
            1 to "[FAIL] host disconnected\n%s0\n",
            0 to "[FAIL] disconnected\n",
            0 to "[FAIL] disconnected\nMAESTRO_EXIT_deadbeef=0\n",
            0 to "[FAIL] disconnected\n%sinvalid\n",
            0 to "[FAIL] disconnected\n%s0\nunexpected trailing output",
        )
        for ((hostExit, output) in cases) {
            var result: HdcCommandResult? = null
            val device = device { command, _ ->
                HdcCommandResult(hostExit, output.replace("%s", marker(command)), "").also { result = it }
            }
            val error = assertThrows(HdcCommandException::class.java) {
                device.execute(listOf("mediatool", "recv", "source", "/data/local/tmp/example.mp4"), TIMEOUT)
            }
            assertThat(error.result).isSameInstanceAs(result)
        }
    }

    @Test
    fun `normalizing HDC diagnostics never turns failure text into success`() {
        for (output in listOf("[FAIL] error", "[ error ] error", "error: rejected", "failed: rejected")) {
            val device = device { command, _ -> HdcCommandResult(0, "$output\n${marker(command)}0\n", "") }
            val error = assertThrows(HarmonyOSCommandException::class.java) {
                device.execute(listOf("mediatool", "recv", "source", "/data/local/tmp/example.mp4"), TIMEOUT)
            }
            assertThat(error.stdout).isEqualTo(output)
            assertThat(error.remoteExitCode).isEqualTo(0)
        }
        val device = device { command, _ -> HdcCommandResult(0, "${marker(command)}0\n", "[FAIL] stderr diagnostic") }
        val error = assertThrows(HarmonyOSCommandException::class.java) {
            device.execute(listOf("mediatool", "recv", "source", "/data/local/tmp/example.mp4"), TIMEOUT)
        }
        assertThat(error.stderr).isEqualTo("[FAIL] stderr diagnostic")
    }

    @Test
    fun `UITest API errors are detected when both host and device exits are zero`() {
        for (output in listOf("Initialization failed.", "This operation is not supported.", "", "No Error\nUnexpected diagnostic")) {
            var calls = 0
            val device = device { command, _ ->
                calls++
                HdcCommandResult(0, "$output\n${marker(command)}0\n", "")
            }
            val error = assertThrows(HarmonyOSCommandException::class.java) {
                device.execute(listOf("uitest", "uiInput", "text", "hello"), TIMEOUT)
            }
            assertThat(error.stdout).isEqualTo(output)
            assertThat(error.remoteExitCode).isEqualTo(0)
            assertThat(calls).isEqualTo(1)
        }
    }

    @Test
    fun `UITest successful input must provide the official success message without diagnostics`() {
        val success = device { command, _ -> HdcCommandResult(0, "No Error\r\n${marker(command)}0\r\n", "") }
        assertThat(success.execute(listOf("uitest", "uiInput", "keyEvent", "Back"), TIMEOUT)).isEqualTo("No Error")
        val diagnostic = device { command, _ -> HdcCommandResult(0, "No Error\n${marker(command)}0\n", "unexpected device diagnostic") }
        assertThrows(HarmonyOSCommandException::class.java) {
            diagnostic.execute(listOf("uitest", "uiInput", "keyEvent", "Back"), TIMEOUT)
        }
    }

    @Test
    fun `capture uses fresh path and cleans up both remote and local scratch`() {
        val runner = ScreenshotRunner()
        val device = device(runner)
        assertThat(device.screenshot(TIMEOUT)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(runner.captures).isEqualTo(1)
        assertThat(runner.cleanupCalls).isEqualTo(1)
        assertThat(Files.exists(runner.localPath!!)).isFalse()
        assertThat(Files.exists(runner.localPath!!.parent)).isFalse()
        assertThat(runner.timeouts).hasSize(3)
        assertThat(runner.timeouts.first().toMillis()).isGreaterThan(TIMEOUT.toMillis() * 2 / 3)
        assertThat(runner.timeouts.all { it <= TIMEOUT && !it.isZero && !it.isNegative }).isTrue()
        assertThat(runner.timeouts[1] <= runner.timeouts[0]).isTrue()
        assertThat(runner.timeouts[2].toMillis()).isEqualTo(2000L)
    }

    @Test
    fun `transfer failure is not replaced by cleanup failure`() {
        val runner = ScreenshotRunner().apply { transferFailure = HdcException("transfer interrupted"); cleanupFailure = true }
        val error = assertThrows(HdcException::class.java) { device(runner).screenshot(TIMEOUT) }
        assertThat(error).isSameInstanceAs(runner.transferFailure)
        assertThat(error.suppressed).hasLength(1)
        assertThat(error.suppressed[0].message).contains("exit 9")
        assertThat(runner.cleanupCalls).isEqualTo(1)
        assertThat(Files.exists(runner.localPath!!.parent)).isFalse()
    }

    @Test
    fun `successful transfer without creating file fails instead of returning stale screenshot`() {
        val runner = ScreenshotRunner().apply { createFile = false }
        val error = assertThrows(HdcException::class.java) { device(runner).screenshot(TIMEOUT) }
        assertThat(error.message).contains("did not create")
        assertThat(runner.cleanupCalls).isEqualTo(1)
    }

    @Test
    fun `cleanup interruption preserves cancellation`() {
        val runner = ScreenshotRunner().apply { interruptCleanup = true }
        try {
            assertThrows(InterruptedException::class.java) { device(runner).screenshot(TIMEOUT) }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(Files.exists(runner.localPath!!.parent)).isFalse()
        } finally {
            Thread.interrupted()
        }
    }

    private class ScreenshotRunner : HdcCommandRunner {
        var captures = 0
        var cleanupCalls = 0
        var localPath: Path? = null
        var transferFailure: Throwable? = null
        var cleanupFailure = false
        var interruptCleanup = false
        var createFile = true
        val timeouts = mutableListOf<Duration>()
        override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
            timeouts += timeout
            if (command.getOrNull(3) == "file") {
                localPath = Path.of(command.last())
                assertThat(Files.exists(localPath!!)).isFalse()
                transferFailure?.let { throw it }
                if (createFile) Files.write(localPath!!, byteArrayOf(1, 2, 3))
                return HdcCommandResult(0, "FileTransfer finish", "")
            }
            if ("screenCap" in command.last()) captures++ else {
                cleanupCalls++
                if (interruptCleanup) throw InterruptedException("cleanup interrupted")
                if (cleanupFailure) return HdcCommandResult(0, "${marker(command)}9\n", "")
            }
            return HdcCommandResult(0, "${marker(command)}0\n", "")
        }
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(3)
        private fun marker(command: List<String>): String = Regex("MAESTRO_EXIT_[a-f0-9]+=").find(command.last())!!.value
        private fun device(runner: HdcCommandRunner) = HdcHarmonyOSDevice("TEST-DEVICE", HdcClient("hdc", runner))
    }
}
