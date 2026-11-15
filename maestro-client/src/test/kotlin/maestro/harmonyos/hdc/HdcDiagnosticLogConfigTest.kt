package maestro.harmonyos.hdc

import com.google.common.truth.Truth.assertThat
import maestro.debuglog.LogConfig
import maestro.debuglog.ScopedLogCapture
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HdcDiagnosticLogConfigTest {
    @TempDir lateinit var directory: Path

    @Test fun `verbose console excludes HDC diagnostics while file and scoped capture retain them`() {
        // LogConfig reconfigures a global context. Exercise it in a real child JVM so this
        // test cannot stop/restart the appenders used by other tests in the Gradle worker.
        val file = directory.resolve("global.log")
        val scoped = directory.resolve("scoped.log")
        val stdout = directory.resolve("stdout.log")
        val stderr = directory.resolve("stderr.log")
        val java = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val urls = generateSequence(HdcLogConfigProbe::class.java.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.getURLs().asSequence() }
            .filter { it.protocol == "file" }
            .map { Path.of(it.toURI()).toString() }.toList()
        val ownClasses = listOf(HdcLogConfigProbe::class.java, LogConfig::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        val classpath = (urls + ownClasses + System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct().joinToString(File.pathSeparator)
        // Java's argument file avoids Windows' command-line limit for the full test runtime.
        val arguments = listOf("-Dfile.encoding=UTF-8", "-cp", classpath,
            HdcLogConfigProbe::class.java.name, file.toString(), scoped.toString())
        val argumentFile = directory.resolve("java.args")
        Files.writeString(argumentFile, arguments.joinToString("\n", postfix = "\n") { argument ->
            require(argument.none { it == '\n' || it == '\r' || it == '\u0000' })
            "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        })
        val builder = ProcessBuilder(java.toString(), "@${argumentFile.toAbsolutePath()}")
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        builder.environment().remove("MAESTRO_CLI_LOG_PATTERN_CONSOLE")
        builder.environment().remove("MAESTRO_CLI_LOG_PATTERN_FILE")
        val process = builder.start()
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue()
            assertThat(process.exitValue()).isEqualTo(0)
            val console = Files.readString(stdout)
            assertThat(console).doesNotContain("MAESTRO_HDC_CALL")
            assertThat(Files.readString(stderr)).doesNotContain("MAESTRO_HDC_CALL")
            assertThat(console).contains("ordinary-log-visible")
            assertThat(console).contains("other-marker-visible")
            for (path in listOf(file, scoped)) {
                val content = Files.readString(path)
                assertThat(content).contains("MAESTRO_HDC_CALL")
                assertThat(content).contains("\"category\":\"DUMP_LAYOUT\"")
                assertThat(content).contains("\"phase\":\"FINISH\"")
                assertThat(content).contains("ordinary-log-visible")
            }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
}

/** Fake transport only; the process exercises the actual logging backend without HDC or a device. */
object HdcLogConfigProbe {
    @JvmStatic fun main(arguments: Array<String>) {
        LogConfig.configure(logFileName = arguments[0], printToConsole = true)
        try {
            ScopedLogCapture.start(File(arguments[1])).use {
                HdcClient("unused-hdc", HdcCommandRunner { _, _ -> HdcCommandResult(0, "", "") })
                    .shell("fixture-target", listOf("uitest", "dumpLayout"))
                val logger = LoggerFactory.getLogger("maestro.hdc.log-probe")
                logger.info("ordinary-log-visible")
                logger.info(MarkerFactory.getMarker("UNRELATED_TEST_MARKER"), "other-marker-visible")
            }
        } finally { LogManager.shutdown() }
    }
}
