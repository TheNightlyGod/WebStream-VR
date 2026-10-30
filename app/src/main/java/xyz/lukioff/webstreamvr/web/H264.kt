package xyz.lukioff.webstreamvr.web

/** Annex-B scanning helpers for the encoder output the headset sends. */
object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    class Units(val types: Int) {
        fun has(type: Int) = types and (1 shl type) != 0
        val isKeyframe get() = has(NAL_IDR)
        val hasSlice get() = has(NAL_IDR) || has(NAL_SLICE)
        val hasParameterSets get() = has(NAL_SPS) && has(NAL_PPS)
    }

    /** Collects NAL unit types (as a bit mask) found after 3- or 4-byte start codes. */
    fun scan(data: ByteArray, offset: Int, length: Int): Units {
        var mask = 0
        val end = offset + length
        var i = offset
        while (i + 3 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    mask = mask or (1 shl (data[i + 3].toInt() and 0x1F))
                    i += 4
                    continue
                }
                if (data[i + 2].toInt() == 0 && i + 4 < end && data[i + 3].toInt() == 1) {
                    mask = mask or (1 shl (data[i + 4].toInt() and 0x1F))
                    i += 5
                    continue
                }
            }
            i++
        }
        return Units(mask)
    }

    /** SPS and PPS, each with a 4-byte start code: the form MediaMuxer expects in csd-0 and csd-1. */
    class ParameterSets(val sps: ByteArray, val pps: ByteArray) {
        fun sameAs(other: ParameterSets) = sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
    }

    /** Pulls the first SPS and PPS out of an Annex-B buffer; null when either is missing. */
    fun parameterSets(data: ByteArray, offset: Int, length: Int): ParameterSets? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val end = offset + length
        var unit = nextUnit(data, offset, end)
        while (unit >= 0 && (sps == null || pps == null)) {
            val next = nextUnit(data, unit, end)
            var unitEnd = if (next >= 0) next - 3 else end
            while (unitEnd > unit && data[unitEnd - 1].toInt() == 0) unitEnd--
            val type = data[unit].toInt() and 0x1F
            if (type == NAL_SPS && sps == null) sps = START_CODE + data.copyOfRange(unit, unitEnd)
            if (type == NAL_PPS && pps == null) pps = START_CODE + data.copyOfRange(unit, unitEnd)
            unit = next
        }
        return if (sps != null && pps != null) ParameterSets(sps, pps) else null
    }

    /** Index of the first NAL byte after the next start code, or -1. */
    private fun nextUnit(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 3 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i + 3
            i++
        }
        return -1
    }

    /** Display size from an SPS with its start code; a port of parseSps in assets/web/mp4.js. */
    fun frameSize(sps: ByteArray): Pair<Int, Int> {
        val r = BitReader(sps, START_CODE.size)
        r.bits(8) // NAL header
        val profile = r.bits(8)
        r.bits(16) // constraint flags and level
        r.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bit()
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.bit() // qpprime_y_zero_transform_bypass_flag
            if (r.bit() == 1) {
                val lists = if (chromaFormat == 3) 12 else 8
                for (i in 0 until lists) {
                    if (r.bit() == 0) continue
                    var last = 8
                    var next = 8
                    repeat(if (i < 6) 16 else 64) {
                        if (next != 0) next = (last + r.se() + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> {
                r.bit()
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.bit() // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bit()
        if (frameMbsOnly == 0) r.bit()
        r.bit() // direct_8x8_inference_flag
        var cropX = 0
        var cropY = 0
        if (r.bit() == 1) {
            cropX = r.ue() + r.ue()
            cropY = r.ue() + r.ue()
        }
        val unitX = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
        val unitY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
        return (widthMbs * 16 - cropX * unitX) to ((2 - frameMbsOnly) * heightMapUnits * 16 - cropY * unitY)
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Reads exp-Golomb fields from a NAL unit, skipping emulation prevention bytes. */
    private class BitReader(private val data: ByteArray, private var byte: Int) {
        private var bit = 0
        private var zeros = 0

        fun bit(): Int {
            if (bit == 0 && zeros >= 2 && byte < data.size && data[byte].toInt() == 3) {
                byte++
                zeros = 0
            }
            val value = data[byte].toInt() and 0xFF
            val result = (value shr (7 - bit)) and 1
            if (++bit == 8) {
                bit = 0
                byte++
                zeros = if (value == 0) zeros + 1 else 0
            }
            return result
        }

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }

        fun ue(): Int {
            var leadingZeros = 0
            while (bit() == 0) leadingZeros++
            return (1 shl leadingZeros) - 1 + bits(leadingZeros)
        }

        fun se(): Int {
            val code = ue()
            return if (code and 1 == 1) (code + 1) / 2 else -code / 2
        }
    }
}
