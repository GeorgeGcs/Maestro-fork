package maestro.cli.harmonyos

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcCommandRunner
import maestro.harmonyos.hdc.ProcessHdcCommandRunner
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Timeout(60)
class HarmonyOsPreflightProcessTest {
    @Test fun `missing executable still returns discovery unknown after describe and EOF`() {
        scenario(HarmonyOsPreflightProcessFixture.Mode.MISSING)
    }

    @Test fun `native nonzero discovery still returns preflight after immediate EOF`() {
        scenario(HarmonyOsPreflightProcessFixture.Mode.NONZERO)
    }

    @Test fun `native malformed target output remains discovery unknown after EOF`() {
        scenario(HarmonyOsPreflightProcessFixture.Mode.MALFORMED)
    }

    @Test fun `native empty target output confirms absence rather than discovery failure`() {
        scenario(HarmonyOsPreflightProcessFixture.Mode.EMPTY)
    }

    private fun scenario(mode: HarmonyOsPreflightProcessFixture.Mode) {
        TempFileHandler().use { temporary ->
            val root = temporary.createTempDirectory("harmonyos-preflight-process-").toPath().toAbsolutePath().normalize()
            val child = start(root, mode)
            val owned = linkedMapOf(child.identity.pid to child.identity)
            var exited = false
            try {
                // The reported two requests use the default v1 describe params, then immediate EOF.
                child.process.outputStream.bufferedWriter(Charsets.UTF_8).use { input ->
                    input.write("""{"id":1,"method":"agent.describe","params":{}}""")
                    input.newLine()
                    input.write("""{"id":2,"method":"agent.preflight","params":{"deviceId":"b1-no-such-device","commands":["launchApp"]}}""")
                    input.newLine()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (System.nanoTime() < deadline) {
                    child.process.descendants().use { descendants ->
                        descendants.forEach { handle ->
                            handle.info().startInstant().orElse(null)?.let { owned[handle.pid()] = Identity(handle.pid(), it) }
                        }
                    }
                    if (child.process.waitFor(25, TimeUnit.MILLISECONDS)) { exited = true; break }
                }
            } finally {
                // The native fixture also records its exact identity, including when its parent exits first.
                val nativeIdentity = root.resolve("native-identity.json")
                if (Files.exists(nativeIdentity)) {
                    val value = json(nativeIdentity)
                    check(value["parentPid"].longValue() == child.identity.pid)
                    val identity = Identity(value["pid"].longValue(), Instant.parse(value["startedAt"].asText()))
                    owned[identity.pid] = identity
                }
                owned.values.toList().asReversed().forEach(::terminateOwned)
            }

            val diagnostic = Files.readString(child.stderr)
            assertWithMessage(diagnostic).that(exited).isTrue()
            assertWithMessage(diagnostic).that(child.process.exitValue()).isEqualTo(0)
            val responses = Files.readAllLines(child.stdout).filter(String::isNotBlank).map(mapper::readTree)
            assertWithMessage(diagnostic).that(responses).hasSize(2)
            assertThat(responses.map { it["id"].asInt() }).containsExactly(1, 2).inOrder()
            assertThat(responses.all { it.has("result") && !it.has("error") }).isTrue()
            assertThat(responses[0]["result"]["kind"].asText()).isEqualTo("capabilities")
            assertThat(responses[0]["result"]["schemaVersion"].asInt()).isEqualTo(1)
            val document = responses[1]["result"]
            assertThat(document["protocol"].asText()).isEqualTo("maestro.harmonyos.agent")
            assertThat(document["schemaVersion"].asInt()).isEqualTo(1)
            assertThat(document["kind"].asText()).isEqualTo("preflight")
            val preflight = document["data"]
            assertThat(preflight["readiness"].asText()).isEqualTo("blocked")
            val checks = preflight["checks"].toList()
            assertThat(checks.map { it["code"].asText() }).containsAtLeast("APP_ID_REQUIRED", "ABILITY_REQUIRED")
            val device = checks.single { it["name"].asText() == "device" }
            if (mode == HarmonyOsPreflightProcessFixture.Mode.EMPTY) {
                assertThat(device["status"].asText()).isEqualTo("failed")
                assertThat(device["code"].asText()).isEqualTo("DEVICE_NOT_FOUND")
                assertThat(checks.map { it["code"].asText() }).doesNotContain("HDC_DISCOVERY_FAILED")
            } else {
                assertThat(device["status"].asText()).isEqualTo("unknown")
                assertThat(device["code"].asText()).isEqualTo("HDC_DISCOVERY_FAILED")
                assertThat(checks.map { it["code"].asText() }).doesNotContain("DEVICE_NOT_FOUND")
            }
            assertThat(Files.exists(root.resolve("unexpected-backend-call"))).isFalse()
            if (mode == HarmonyOsPreflightProcessFixture.Mode.MISSING) {
                assertThat(Files.exists(root.resolve("missing-hdc-executable"))).isFalse()
                assertThat(Files.exists(root.resolve("native-identity.json"))).isFalse()
            } else {
                val calls = Files.readAllLines(root.resolve("transport-calls.jsonl")).map(mapper::readTree)
                assertThat(calls).hasSize(1)
                assertThat(calls.single().map(JsonNode::asText)).containsExactly("fixture-hdc", "list", "targets").inOrder()
                val native = json(root.resolve("native-identity.json"))
                assertThat(native["parentPid"].longValue()).isEqualTo(child.identity.pid)
                assertThat(native["pid"].longValue()).isNotEqualTo(child.identity.pid)
                val result = json(root.resolve("transport-result.json"))
                assertThat(result["exitCode"].asInt()).isEqualTo(if (mode == HarmonyOsPreflightProcessFixture.Mode.NONZERO) 7 else 0)
                when (mode) {
                    HarmonyOsPreflightProcessFixture.Mode.NONZERO -> assertThat(result["stderr"].asText()).contains("fixture discovery failure")
                    HarmonyOsPreflightProcessFixture.Mode.MALFORMED -> assertThat(result["stdout"].asText().trim()).isEqualTo("not a valid target")
                    HarmonyOsPreflightProcessFixture.Mode.EMPTY -> assertThat(result["stdout"].asText().trim()).isEqualTo("[Empty]")
                    else -> error("Unexpected mode")
                }
            }
        }
    }

    private data class Identity(val pid: Long, val startedAt: Instant)
    private data class Child(val process: Process, val identity: Identity, val stdout: Path, val stderr: Path)

    private fun start(root: Path, mode: HarmonyOsPreflightProcessFixture.Mode): Child {
        val paths = linkedSetOf<String>()
        var loader: ClassLoader? = HarmonyOsPreflightProcessFixture::class.java.classLoader
        while (loader != null) {
            (loader as? URLClassLoader)?.urLs?.filter { it.protocol == "file" }?.forEach { paths += Path.of(it.toURI()).toString() }
            loader = loader.parent
        }
        paths += System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotBlank)
        paths += Path.of(HarmonyOsPreflightProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val home = Files.createDirectories(root.resolve("home"))
        val scratch = Files.createDirectories(root.resolve("scratch"))
        fun argument(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        for (action in listOf("bridge", "native")) {
            Files.writeString(root.resolve("$action-java.args"), listOf("-Dfile.encoding=UTF-8", "-Duser.home=$home",
                "-Djava.io.tmpdir=$scratch", "-cp", paths.joinToString(File.pathSeparator),
                HarmonyOsPreflightProcessFixture::class.java.name, action, root.toString(), mode.name)
                .joinToString("\n", transform = ::argument))
        }
        val stdout = root.resolve("bridge.stdout.jsonl")
        val stderr = root.resolve("bridge.stderr.log")
        val process = ProcessBuilder(HarmonyOsPreflightProcessFixture.javaExecutable(), "@${root.resolve("bridge-java.args")}")
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        val started = process.info().startInstant()
        if (started.isEmpty) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Own bridge child could not be stopped" }
            error("Own bridge child did not expose a start time")
        }
        return Child(process, Identity(process.pid(), started.get()), stdout, stderr)
    }

    private fun terminateOwned(identity: Identity) {
        val process = ProcessHandle.of(identity.pid).orElse(null) ?: return
        if (!process.isAlive || process.info().startInstant().orElse(null) != identity.startedAt) return
        process.destroyForcibly()
        process.onExit().get(5, TimeUnit.SECONDS)
    }

    private fun json(path: Path): JsonNode = mapper.readTree(path.toFile())
    private val mapper = jacksonObjectMapper()
}

/** Test-only entry point: production bridge and native runner, with no real HDC executable. */
object HarmonyOsPreflightProcessFixture {
    enum class Mode { MISSING, NONZERO, MALFORMED, EMPTY }
    private val mapper = jacksonObjectMapper()

    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 3)
        val root = Path.of(arguments[1]).toAbsolutePath().normalize()
        val mode = Mode.valueOf(arguments[2])
        when (arguments[0]) {
            "bridge" -> bridge(root, mode)
            "native" -> native(root, mode)
            else -> error("Unknown preflight fixture action")
        }
    }

    private fun bridge(root: Path, mode: Mode) {
        HarmonyOsBridgeStdout.claim()
        val nativeRunner = ProcessHdcCommandRunner()
        val hdc = if (mode == Mode.MISSING) {
            HdcClient(root.resolve("missing-hdc-executable").toString(), nativeRunner, Duration.ofSeconds(5))
        } else {
            HdcClient("fixture-hdc", HdcCommandRunner { command, timeout ->
                check(command == listOf("fixture-hdc", "list", "targets")) { "Only device discovery is allowed in this fixture" }
                Files.writeString(root.resolve("transport-calls.jsonl"), mapper.writeValueAsString(command) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                nativeRunner.run(listOf(javaExecutable(), "@${root.resolve("native-java.args")}"), timeout).also { result ->
                    Files.writeString(root.resolve("transport-result.json"), mapper.writeValueAsString(result))
                }
            }, Duration.ofSeconds(5))
        }
        val backend = object : RecorderBackend {
            override fun devices(): List<String> = unexpected()
            override fun withSession(config: RecorderConfig, block: (RecorderConnection) -> Unit): Unit = unexpected()
            private fun unexpected(): Nothing {
                Files.writeString(root.resolve("unexpected-backend-call"), "No session or legacy discovery is allowed")
                error("Preflight must not open a session or issue an action")
            }
        }
        val capabilities = HarmonyOsAgentCapabilities(HdcAgentEnvironmentProbe(hdc), recordingEnabled = false)
        exitProcess(HarmonyOsBridgeServer(System.`in`, HarmonyOsBridgeStdout.output, System.err, backend, capabilities).run())
    }

    private fun native(root: Path, mode: Mode) {
        check(mode != Mode.MISSING)
        val process = ProcessHandle.current()
        Files.writeString(root.resolve("native-identity.json"), mapper.writeValueAsString(mapOf(
            "pid" to process.pid(), "parentPid" to process.parent().orElseThrow().pid(),
            "startedAt" to process.info().startInstant().orElseThrow().toString(),
        )))
        // EOF arrives while a real discovery subprocess is outstanding, not after a mocked result.
        Thread.sleep(300)
        when (mode) {
            Mode.NONZERO -> { System.err.println("fixture discovery failure"); exitProcess(7) }
            Mode.MALFORMED -> println("not a valid target")
            Mode.EMPTY -> println("[Empty]")
            else -> error("Unexpected native fixture mode")
        }
    }

    fun javaExecutable(): String = Path.of(System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
}
