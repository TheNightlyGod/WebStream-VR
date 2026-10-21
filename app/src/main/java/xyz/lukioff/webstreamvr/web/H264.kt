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
}
