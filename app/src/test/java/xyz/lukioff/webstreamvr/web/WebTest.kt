package xyz.lukioff.webstreamvr.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class WebTest {

    @Test
    fun acceptKeyMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun frameHeaderLengths() {
        assertEquals(2 + 5, WebSocket.frame(WebSocket.OPCODE_BINARY, null, ByteArray(5)).size)
        val medium = WebSocket.frame(WebSocket.OPCODE_BINARY, ByteArray(16), ByteArray(300))
        assertEquals(126, medium[1].toInt())
        assertEquals(316, ((medium[2].toInt() and 0xFF) shl 8) or (medium[3].toInt() and 0xFF))
        val large = WebSocket.frame(WebSocket.OPCODE_BINARY, null, ByteArray(70000))
        assertEquals(127, large[1].toInt())
        assertEquals(10 + 70000, large.size)
    }

    @Test
    fun readsMaskedClientFrame() {
        // "Hello" masked with 37 fa 21 3d (RFC 6455, section 5.7).
        val bytes = byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xfa.toByte(), 0x21, 0x3d, 0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58)
        val frame = WebSocket.read(ByteArrayInputStream(bytes))
        assertEquals(WebSocket.OPCODE_TEXT, frame.opcode)
        assertArrayEquals("Hello".toByteArray(), frame.payload)
    }

    @Test
    fun scansNalTypes() {
        val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0, 0, 1, 0x68, 0x11)
        val units = H264.scan(config, 0, config.size)
        assertTrue(units.hasParameterSets)
        assertFalse(units.hasSlice)

        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0, 0, 1, 0x06, 0x01)
        val frame = H264.scan(idr, 0, idr.size)
        assertTrue(frame.isKeyframe)
        assertTrue(frame.has(6))

        val p = byteArrayOf(9, 9, 0, 0, 0, 1, 0x41, 0x22)
        val slice = H264.scan(p, 2, p.size - 2)
        assertTrue(slice.hasSlice)
        assertFalse(slice.isKeyframe)
    }
}
