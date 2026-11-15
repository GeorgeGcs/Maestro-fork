package maestro.harmonyos.layout

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

class LoopbackLayoutChannelTest {
    @Test
    fun `reads two adjacent bare frames without consuming the following response`() {
        val first = """{"result":{"text":"brace } and \"quoted\" 测试"}}""".toByteArray()
        val second = """{"result":{}}""".toByteArray()
        val wire = ByteArrayInputStream(first + second)
        assertArrayEquals(first, receiveLayoutFrame(wire::read))
        assertArrayEquals(second, receiveLayoutFrame(wire::read))
    }

    @Test
    fun `rejects truncated and oversized frames before parsing a layout`() {
        val frame = """{"result":{}}""".toByteArray()
        val header = ByteBuffer.allocate(4).putInt(frame.size).array()
        assertArrayEquals(frame, receiveLayoutFrame(ByteArrayInputStream(header + frame)::read))
        assertThrows(EOFException::class.java) {
            receiveLayoutFrame(ByteArrayInputStream(header + frame.dropLast(1).toByteArray())::read)
        }
        val oversize = ByteBuffer.allocate(4).putInt(8 * 1024 * 1024 + 1).array()
        assertThrows(IOException::class.java) { receiveLayoutFrame(ByteArrayInputStream(oversize)::read) }
    }
}
