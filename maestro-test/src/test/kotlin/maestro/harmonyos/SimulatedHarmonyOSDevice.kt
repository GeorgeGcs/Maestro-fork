package maestro.harmonyos

import com.fasterxml.jackson.databind.ObjectMapper
import maestro.Point
import maestro.harmonyos.hdc.HdcCommandResult
import maestro.harmonyos.hdc.HdcCommandRunner
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO

/** In-process HDC protocol fixture. It does not launch a shell or represent a real phone. */
internal class SimulatedHarmonyOSDevice(
    private val rejectTap: Boolean = false,
    private val tapFailureOutput: String? = null,
) : HdcCommandRunner {
    enum class State { HOME, FORM, SCROLL_FORM, RESULT }

    data class Swipe(val start: Point, val end: Point, val velocity: Int)

    var state = State.HOME
        private set
    var tapCount = 0
        private set
    val inputValues = mutableListOf<String>()
    val inputTargets = mutableListOf<String>()
    val tapPoints = mutableListOf<Point>()
    val swipes = mutableListOf<Swipe>()
    val observedScrollPositions = mutableSetOf<Int>()
    val launchedApps = mutableListOf<String>()
    val stoppedApps = mutableListOf<String>()
    val remoteFiles = mutableMapOf<String, ByteArray>()

    private var inputFocused = false
    private var enteredName = ""
    private var scrollPosition = 0
    private val mapper = ObjectMapper()

    @Synchronized
    override fun run(command: List<String>, timeout: Duration): HdcCommandResult {
        require(!timeout.isNegative && !timeout.isZero)
        if (command.drop(1) == listOf("list", "targets")) return hostSuccess("$DEVICE_ID\n")
        check(command.size >= 5 && command[1] == "-t" && command[2] == DEVICE_ID) {
            "Unexpected HDC invocation: $command"
        }
        return when (command[3]) {
            "file" -> {
                check(command.size == 7 && command[4] == "recv") { "Unexpected file transfer: $command" }
                val bytes = checkNotNull(remoteFiles[command[5]]) { "Remote file does not exist: ${command[5]}" }
                Files.write(Path.of(command[6]), bytes)
                hostSuccess("FileTransfer finish\n")
            }
            "shell" -> {
                check(command.size == 5)
                val arguments = decodeQuotedArguments(command[4])
                if (arguments.take(2) == listOf("sh", "-c")) {
                    check(arguments.size == 3)
                    val script = arguments[2]
                    val boundary = script.lastIndexOf("; __maestro_status=")
                    check(boundary >= 0) { "Missing device exit-status wrapper" }
                    val marker = checkNotNull(Regex("MAESTRO_EXIT_[a-f0-9]+=").find(script.substring(boundary))).value
                    val result = execute(decodeQuotedArguments(script.substring(0, boundary)))
                    // HDC can report host success even when the command on the device failed.
                    hostSuccess("${result.stdout}\n$marker${result.exitCode}\n")
                } else {
                    execute(arguments)
                }
            }
            else -> error("Unexpected HDC invocation: $command")
        }
    }

    private fun execute(arguments: List<String>): HdcCommandResult = when {
        arguments.take(2) == listOf("aa", "force-stop") -> {
            check(arguments.size == 3)
            stoppedApps += arguments[2]
            state = State.HOME
            inputFocused = false
            enteredName = ""
            scrollPosition = 0
            hostSuccess("force stop process successfully.\n")
        }
        arguments.take(2) == listOf("aa", "start") -> {
            check(arguments.size == 6 && arguments[2] == "-b" && arguments[4] == "-a")
            check(arguments[5] == "EntryAbility")
            launchedApps += arguments[3]
            state = State.HOME
            inputFocused = false
            enteredName = ""
            scrollPosition = 0
            hostSuccess("start ability successfully.\n")
        }
        arguments.take(3) == listOf("uitest", "dumpLayout", "-p") -> {
            check(arguments.size == 4)
            remoteFiles[arguments[3]] = mapper.writeValueAsBytes(layout())
            hostSuccess("Dump layout saved\n")
        }
        arguments.take(3) == listOf("uitest", "screenCap", "-p") -> {
            check(arguments.size == 4)
            remoteFiles[arguments[3]] = screenshot()
            hostSuccess("Screen capture saved\n")
        }
        arguments.take(3) == listOf("uitest", "uiInput", "click") -> {
            check(arguments.size == 5)
            tap(arguments[3].toInt(), arguments[4].toInt())
        }
        arguments.take(3) == listOf("uitest", "uiInput", "text") -> {
            check(arguments.size == 4 && state in listOf(State.FORM, State.SCROLL_FORM) && inputFocused) {
                "Text input requires the form's input field to be focused"
            }
            inputValues += arguments[3]
            inputTargets += if (state == State.SCROLL_FORM) "scroll-name-input" else "name-input"
            enteredName += arguments[3]
            hostSuccess("No Error")
        }
        arguments.take(3) == listOf("uitest", "uiInput", "swipe") -> {
            check(arguments.size == 8 && state == State.SCROLL_FORM)
            val start = Point(arguments[3].toInt(), arguments[4].toInt())
            val end = Point(arguments[5].toInt(), arguments[6].toInt())
            val velocity = arguments[7].toInt()
            check(start.x == end.x && start.y != end.y && velocity in 200..40000)
            swipes += Swipe(start, end, velocity)
            scrollPosition = (scrollPosition + if (start.y > end.y) 1 else -1).coerceIn(0, 2)
            inputFocused = false
            hostSuccess("No Error")
        }
        arguments.take(2) == listOf("rm", "-f") -> {
            check(arguments.size == 3)
            remoteFiles.remove(arguments[2])
            hostSuccess()
        }
        else -> error("Unexpected device command: $arguments")
    }

    private fun tap(x: Int, y: Int): HdcCommandResult {
        tapCount++
        tapPoints += Point(x, y)
        if (rejectTap) return HdcCommandResult(37, "tap rejected by simulated device", "")
        tapFailureOutput?.let { return hostSuccess(it) }
        when {
            state == State.HOME && x in 40..359 && y in 120..199 -> state = State.FORM
            state == State.HOME && x in 40..359 && y in 440..519 -> state = State.SCROLL_FORM
            state == State.FORM && x in 40..359 && y in 120..199 -> inputFocused = true
            state == State.SCROLL_FORM && scrollPosition == 2 && x in 40..359 && y in 240..319 -> inputFocused = true
            state == State.FORM && x in 40..359 && y in 300..379 -> {
                check(enteredName.isNotEmpty())
                state = State.RESULT
            }
            else -> error("Unexpected tap at ($x, $y) on $state")
        }
        return hostSuccess("No Error")
    }

    private fun layout(): Map<String, Any> {
        val children = when (state) {
            State.HOME -> listOf(
                node(text = "欢迎", bounds = "[40,40][360,100]"),
                node(id = "open-form", text = "填写表单", bounds = "[40,120][360,200]", clickable = true),
                node(id = "open-scroll-form", text = "打开滚动表单", bounds = "[40,440][360,520]", clickable = true),
            )
            State.FORM -> listOf(
                node(text = "姓名", bounds = "[40,40][360,100]"),
                node(id = "name-input", text = enteredName, bounds = "[40,120][360,200]", clickable = true, focused = inputFocused),
                node(id = "submit", text = "提交", bounds = "[40,300][360,380]", clickable = true),
            )
            State.RESULT -> listOf(
                node(text = "已提交", bounds = "[40,40][360,100]"),
                node(text = enteredName, bounds = "[40,120][360,200]"),
            )
            State.SCROLL_FORM -> {
                observedScrollPositions += scrollPosition
                // The id stays the same while scrolling changes its bounds. At position 1
                // only half the field is on screen, so visibilityPercentage: 100 must continue.
                val inputTop = when (scrollPosition) {
                    0 -> 920
                    1 -> 760
                    else -> 240
                }
                listOf(
                    node(text = "滚动表单", bounds = "[40,40][360,100]"),
                    node(
                        id = "scroll-name-input", text = enteredName,
                        bounds = "[40,$inputTop][360,${inputTop + 80}]",
                        clickable = true, focused = inputFocused,
                    ),
                )
            }
        }
        return node(bounds = "[0,0][$WIDTH,$HEIGHT]", children = children)
    }

    private fun node(
        id: String = "",
        text: String = "",
        bounds: String,
        clickable: Boolean = false,
        focused: Boolean = false,
        children: List<Map<String, Any>> = emptyList(),
    ): Map<String, Any> = mapOf(
        "attributes" to mapOf(
            "id" to id,
            "text" to text,
            "type" to if (clickable) "Button" else "Text",
            "bounds" to bounds,
            "clickable" to clickable.toString(),
            "focused" to focused.toString(),
            "enabled" to "true",
            "visible" to "true",
        ),
        "children" to children,
    )

    private fun screenshot(): ByteArray {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = when (state) {
                State.HOME -> Color.WHITE
                State.FORM -> if (inputFocused) Color.LIGHT_GRAY else Color.GRAY
                State.SCROLL_FORM -> Color(60, 80 + scrollPosition * 50, if (inputFocused) 220 else 160)
                State.RESULT -> Color.GREEN
            }
            graphics.fillRect(0, 0, WIDTH, HEIGHT)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    /** Decodes only the quoted argument form emitted by HdcClient; never evaluates shell syntax. */
    private fun decodeQuotedArguments(command: String): List<String> {
        val arguments = mutableListOf<String>()
        var position = 0
        while (position < command.length) {
            while (position < command.length && command[position].isWhitespace()) position++
            if (position == command.length) break
            val argument = StringBuilder()
            while (position < command.length && !command[position].isWhitespace()) {
                val quote = command[position++]
                check(quote == '\'' || quote == '"') { "Expected a quoted argument: $command" }
                val end = command.indexOf(quote, position)
                check(end >= 0) { "Unterminated argument: $command" }
                argument.append(command, position, end)
                position = end + 1
            }
            arguments += argument.toString()
        }
        return arguments
    }

    private fun hostSuccess(stdout: String = "") = HdcCommandResult(0, stdout, "")

    companion object {
        const val DEVICE_ID = "simulated-harmonyos-device"
        const val WIDTH = 400
        const val HEIGHT = 800
    }
}
