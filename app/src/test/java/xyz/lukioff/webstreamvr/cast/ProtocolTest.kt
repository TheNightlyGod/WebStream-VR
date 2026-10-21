package xyz.lukioff.webstreamvr.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolTest {

    @Test
    fun xrspPacketRoundTripsWithPadding() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = Xrsp.encode(topic = 2, sequence = 7, payload = payload)
        assertEquals(16, packet.size)
        // internal-version + padding flags, topic in the high byte
        assertEquals(0x18, packet[0].toInt())
        assertEquals(2, packet[1].toInt())
        assertEquals(3, packet[2].toInt()) // 16 / 4 - 1
        assertEquals(7, packet[4].toInt())
        assertEquals(3, packet.last().toInt()) // padding length

        val decoded = Xrsp.read(ByteArrayInputStream(packet))
        assertEquals(2, decoded.topic)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun xrspParsesCapturedHandshakeHeader() {
        // First packet the headset sent in a live capture: "MGIK" + 2 parts.
        val bytes = hex("10020200000000004d47494b02000000")
        val packet = Xrsp.read(ByteArrayInputStream(bytes))
        assertEquals(2, packet.topic)
        assertEquals("MGIK", String(packet.payload, 0, 4))
    }

    @Test
    fun writerKeepsPerTopicSequences() {
        val out = ByteArrayOutputStream()
        val writer = XrspWriter(out)
        writer.write(2, ByteArray(4), ByteArray(4))
        writer.write(1, ByteArray(4))
        val input = ByteArrayInputStream(out.toByteArray())
        val sequences = (0 until 3).map {
            val header = ByteArray(8).also { input.read(it) }
            input.skip(4)
            (header[1].toInt() to header[4].toInt())
        }
        assertEquals(listOf(2 to 0, 2 to 1, 1 to 0), sequences)
    }

    @Test
    fun assemblerJoinsPartsOfOneDatagram() {
        val assembler = MagicDatagramAssembler()
        assertNull(assembler.accept(magic(3)))
        assertNull(assembler.accept(header(appId = 42, messageId = 5, index = 0, total = 1)))
        assertNull(assembler.accept(byteArrayOf(0, 0, 0, 100)))
        val message = assembler.accept(byteArrayOf(9, 9))!!
        assertEquals(42, message.appId)
        assertArrayEquals(byteArrayOf(0, 0, 0, 100, 9, 9), message.bytes)
    }

    @Test
    fun assemblerJoinsMessagesSplitAcrossDatagrams() {
        val assembler = MagicDatagramAssembler()
        // Second half arrives first; an unrelated message is interleaved.
        assembler.accept(magic(1))
        assertNull(assembler.accept(header(1, messageId = 9, index = 1, total = 2) + byteArrayOf(3, 4)))
        assembler.accept(magic(1))
        val other = assembler.accept(header(1, messageId = 10, index = 0, total = 1) + byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), other!!.bytes)
        assembler.accept(magic(1))
        val joined = assembler.accept(header(1, messageId = 9, index = 0, total = 2) + byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), joined!!.bytes)
    }

    @Test
    fun outgoingDatagramFraming() {
        val (magicPart, body) = MagicDatagramAssembler.frame(appId = 0x01020304, message = MagicMessages.resyncFrame(0))
        assertArrayEquals(hex("4d47494b01000000"), magicPart)
        assertArrayEquals(
            hex("01020304" + "00000000" + "00000000" + "00000000" + "00000001" + "00000002" + "00000065" + "00000000"),
            body,
        )
    }

    @Test
    fun startCastingLayout() {
        val config = CastConfig(1920, 1080, 0f, 1, 30, stabilization = true)
        val bytes = MagicMessages.startCasting(config, "abcde")
        val b = ByteBuffer.wrap(bytes)
        assertEquals(7, b.int)
        assertEquals(1920, b.int)
        assertEquals(1080, b.int)
        assertEquals(0f, b.float)
        assertEquals(1, b.int)
        assertEquals(30, b.int)
        assertEquals(1, b.int)
        assertEquals(5, b.int)
        assertEquals("abcde", String(bytes, b.position(), 5))
        assertEquals(0, bytes.size % 4)
    }

    @Test
    fun pongAndSetPropertyArePadded() {
        assertArrayEquals(hex("00000004000000c9" + "00000000" + "00000000"), MagicMessages.pong(201))
        val property = MagicMessages.setProperty("ab", "")
        assertArrayEquals(hex("0000000a" + "00000002" + "61620000" + "00000000"), property)
    }

    @Test
    fun parsesCapturedLayerConfiguration() {
        val bytes = hex(
            "0000012c" + "00000000" + "00000780" + "00000438" + "00000000" + "00000000" + "40000000" + "00000001" +
                "00000007" + "43617374696e6700" + "0000000a" + "6579655f62756666657200" + "00" +
                "00000007" + "756e6b6e6f776e00" + "00000004" + "61626364",
        )
        val message = IncomingMagic.parse(bytes) as IncomingMagic.LayerConfiguration
        assertEquals(0, message.layerId)
        assertEquals(1920, message.width)
        assertEquals(1080, message.height)
        assertEquals("eye_buffer", message.layerName)
        assertEquals("abcd", message.packageName)
    }

    @Test
    fun parsesVideoSegmentHeader() {
        val bytes = hex("00000064" + "00000000" + "00000002" + "00000001" + "0000000165")
        val message = IncomingMagic.parse(bytes) as IncomingMagic.VideoSegment
        assertEquals(2, message.gopIndex)
        assertEquals(1, message.canonicalIndex)
        assertEquals(16, message.offset)
        assertTrue(message.data.size - message.offset == 5)
    }

    private fun magic(parts: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("MGIK".toByteArray()).putInt(parts).array()

    private fun header(appId: Int, messageId: Int, index: Int, total: Int): ByteArray =
        ByteBuffer.allocate(24).putInt(appId).putInt(0).putInt(messageId).putInt(index).putInt(total).putInt(2).array()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
