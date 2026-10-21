package xyz.lukioff.webstreamvr.web

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** Minimal RFC 6455 helpers for a server that mostly pushes data. */
object WebSocket {
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_CLIENT_FRAME = 64 * 1024

    fun acceptKey(clientKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest((clientKey.trim() + GUID).toByteArray())
        return Base64.getEncoder().encodeToString(sha1)
    }

    /** Builds an unmasked server frame: header followed by [prefix] and [payload] slice. */
    fun frame(
        opcode: Int,
        prefix: ByteArray?,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
    ): ByteArray {
        val bodySize = (prefix?.size ?: 0) + length
        val headerSize = when {
            bodySize < 126 -> 2
            bodySize < 65536 -> 4
            else -> 10
        }
        val out = ByteArray(headerSize + bodySize)
        out[0] = (0x80 or opcode).toByte()
        when (headerSize) {
            2 -> out[1] = bodySize.toByte()
            4 -> {
                out[1] = 126
                out[2] = (bodySize shr 8).toByte()
                out[3] = bodySize.toByte()
            }
            else -> {
                out[1] = 127
                val size = bodySize.toLong()
                for (i in 0 until 8) out[2 + i] = (size shr (56 - 8 * i)).toByte()
            }
        }
        var pos = headerSize
        if (prefix != null) {
            System.arraycopy(prefix, 0, out, pos, prefix.size)
            pos += prefix.size
        }
        System.arraycopy(payload, offset, out, pos, length)
        return out
    }

    fun textFrame(text: String) = frame(OPCODE_TEXT, null, text.toByteArray())

    class Incoming(val opcode: Int, val payload: ByteArray)

    /** Reads one client frame (always masked). Fragmented messages are not supported. */
    fun read(input: InputStream): Incoming {
        val b0 = readByte(input)
        val b1 = readByte(input)
        val opcode = b0 and 0x0F
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        if (length > MAX_CLIENT_FRAME) throw IOException("WebSocket frame too large: $length")
        val masked = b1 and 0x80 != 0
        val mask = ByteArray(4)
        if (masked) readFully(input, mask)
        val payload = ByteArray(length.toInt())
        readFully(input, payload)
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Incoming(opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
    }
}
