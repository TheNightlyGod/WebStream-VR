package xyz.lukioff.webstreamvr.cast

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * XRSP packet framing as spoken by the headset's `tcpclient` transport.
 *
 * Header (8 bytes, little endian):
 *   u16 flags   bits 0-2 version, 3 alignment padding, 4 internal version, 8-13 topic
 *   u16 words   total packet length / 4 - 1
 *   u16 seq     per-topic sequence number
 *   u16 unused
 * When the padding bit is set, the last payload byte holds the padding length.
 */
class XrspPacket(val topic: Int, val payload: ByteArray)

object Xrsp {
    const val HEADER_BYTES = 8
    const val MAX_PACKET_BYTES = 0x40000
    const val TOPIC_MAGIC = 2

    private const val FLAG_PADDING = 0x08
    private const val FLAG_INTERNAL_VERSION = 0x10

    fun read(input: InputStream): XrspPacket {
        val header = ByteArray(HEADER_BYTES)
        readFully(input, header)
        val flags = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
        val words = (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
        val total = (words + 1) * 4
        if (words == 0 || total > MAX_PACKET_BYTES) throw IOException("Bad XRSP packet size $total")
        val body = ByteArray(total - HEADER_BYTES)
        readFully(input, body)
        var length = body.size
        if (flags and FLAG_PADDING != 0) {
            val padding = body[body.size - 1].toInt() and 0xFF
            if (padding == 0 || padding > body.size) throw IOException("Bad XRSP padding $padding")
            length -= padding
        }
        val topic = (flags shr 8) and 0x3F
        return XrspPacket(topic, if (length == body.size) body else body.copyOf(length))
    }

    /** Serializes one packet; [sequence] is the per-topic counter owned by the caller. */
    fun encode(topic: Int, sequence: Int, payload: ByteArray): ByteArray {
        val padding = (4 - (payload.size and 3)) and 3
        val total = HEADER_BYTES + payload.size + padding
        require(total <= MAX_PACKET_BYTES) { "XRSP payload too large: ${payload.size}" }
        var flags = FLAG_INTERNAL_VERSION or (topic shl 8)
        if (padding != 0) flags = flags or FLAG_PADDING
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(flags.toShort())
        out.putShort((total / 4 - 1).toShort())
        out.putShort(sequence.toShort())
        out.putShort(0)
        out.put(payload)
        if (padding != 0) out.put(total - 1, padding.toByte())
        return out.array()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException("XRSP stream closed")
            offset += n
        }
    }
}

/**
 * Writes XRSP packets with per-topic sequence numbers. Thread-safe.
 */
class XrspWriter(private val output: OutputStream) {
    private val sequences = IntArray(64)

    @Synchronized
    fun write(topic: Int, vararg payloads: ByteArray) {
        for (payload in payloads) {
            val seq = sequences[topic]
            sequences[topic] = (seq + 1) and 0xFFFF
            output.write(Xrsp.encode(topic, seq, payload))
        }
        output.flush()
    }
}

/**
 * Reassembles MagicIsland datagrams carried over an XRSP topic.
 *
 * A datagram is a packet with `"MGIK"` + u32 LE part count, followed by that many
 * packets. The first part starts with the 24-byte big-endian NetworkClient header
 * (appId, datagramId, messageId, index, total, qos); the remaining bytes of all
 * parts are a slice of the serialized message. Messages larger than one datagram
 * are split across several datagrams sharing the same messageId.
 */
class MagicDatagramAssembler {
    class Message(val appId: Int, val messageId: Int, val bytes: ByteArray)

    private var expectedParts = 0
    private val parts = ArrayList<ByteArray>()
    private val pendingChunks = HashMap<Int, Array<ByteArray?>>()

    /** Feeds one packet payload; returns a completed message or null. */
    fun accept(payload: ByteArray): Message? {
        if (expectedParts == 0) {
            if (payload.size < 8 || !isMagicHeader(payload)) return null
            expectedParts = ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            parts.clear()
            if (expectedParts <= 0 || expectedParts > 1024) expectedParts = 0
            return null
        }
        parts.add(payload)
        if (parts.size < expectedParts) return null
        expectedParts = 0
        return assemble()
    }

    private fun assemble(): Message? {
        val first = parts[0]
        if (first.size < HEADER_BYTES) return null
        val header = ByteBuffer.wrap(first, 0, HEADER_BYTES)
        val appId = header.int
        header.int // datagram id
        val messageId = header.int
        val index = header.int
        val total = header.int

        var size = first.size - HEADER_BYTES
        for (i in 1 until parts.size) size += parts[i].size
        val chunk = ByteArray(size)
        System.arraycopy(first, HEADER_BYTES, chunk, 0, first.size - HEADER_BYTES)
        var offset = first.size - HEADER_BYTES
        for (i in 1 until parts.size) {
            System.arraycopy(parts[i], 0, chunk, offset, parts[i].size)
            offset += parts[i].size
        }
        parts.clear()

        if (total <= 1) return Message(appId, messageId, chunk)
        if (index !in 0 until total || total > 4096) return null
        val chunks = pendingChunks.getOrPut(messageId) { arrayOfNulls(total) }
        if (chunks.size != total) {
            pendingChunks.remove(messageId)
            return null
        }
        chunks[index] = chunk
        if (chunks.any { it == null }) {
            // Drop stale partial messages so a lost chunk cannot leak memory.
            if (pendingChunks.size > 8) pendingChunks.keys.filter { it < messageId - 8 }.forEach { pendingChunks.remove(it) }
            return null
        }
        pendingChunks.remove(messageId)
        val out = ByteArray(chunks.sumOf { it!!.size })
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c!!, 0, out, pos, c.size)
            pos += c.size
        }
        return Message(appId, messageId, out)
    }

    private fun isMagicHeader(p: ByteArray) =
        p[0] == 'M'.code.toByte() && p[1] == 'G'.code.toByte() && p[2] == 'I'.code.toByte() && p[3] == 'K'.code.toByte()

    companion object {
        const val HEADER_BYTES = 24
        private const val QOS_HIGH = 2

        /** Builds the two packet payloads of a single-part outgoing datagram. */
        fun frame(appId: Int, message: ByteArray): Array<ByteArray> {
            val magic = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            magic.put('M'.code.toByte()).put('G'.code.toByte()).put('I'.code.toByte()).put('K'.code.toByte())
            magic.putInt(1)
            val body = ByteBuffer.allocate(HEADER_BYTES + message.size)
            body.putInt(appId).putInt(0).putInt(0).putInt(0).putInt(1).putInt(QOS_HIGH)
            body.put(message)
            return arrayOf(magic.array(), body.array())
        }
    }
}
