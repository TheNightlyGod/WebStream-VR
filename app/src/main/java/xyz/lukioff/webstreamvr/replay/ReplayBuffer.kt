package xyz.lukioff.webstreamvr.replay

import xyz.lukioff.webstreamvr.web.H264
import java.nio.ByteBuffer

/**
 * The last stretch of the stream, kept compressed in one fixed block of memory.
 *
 * Video and audio share the block in arrival order. The oldest data leaves one GOP
 * at a time, so what is kept always starts at a keyframe. Writing goes on during a
 * save: a [Snapshot] pins its data, and the pin moves forward as the saver reads.
 */
class ReplayBuffer(private val storage: ByteBuffer, private val windowUs: Long) {

    class Sample(
        val video: Boolean,
        val position: Long,
        val size: Int,
        val timestampUs: Long,
        val keyframe: Boolean,
    )

    class Snapshot(val parameterSets: H264.ParameterSets, val samples: List<Sample>)

    private val capacity = storage.capacity()
    private val writer = storage.duplicate()
    private val samples = ArrayDeque<Sample>()
    private val keyframeTimes = ArrayDeque<Long>()
    private var parameterSets: H264.ParameterSets? = null
    private var writePosition = 0L
    private var pinned = -1L
    private var waitingForKeyframe = true
    private var closed = false

    @Synchronized
    fun onVideo(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        if (closed) return
        val units = H264.scan(data, offset, length)
        if (units.hasParameterSets) H264.parameterSets(data, offset, length)?.let(::setParameterSets)
        if (!units.hasSlice || parameterSets == null) return
        val position = reserve(length, units.isKeyframe) ?: return
        waitingForKeyframe = false
        store(data, offset, length, position)
        samples.addLast(Sample(true, position, length, timestampUs, units.isKeyframe))
        if (units.isKeyframe) keyframeTimes.addLast(timestampUs)
        while (keyframeTimes.size >= 2 && keyframeTimes[1] <= timestampUs - windowUs) dropOldestGop()
    }

    @Synchronized
    fun onAudio(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {
        if (closed) return
        val position = reserve(length, keyframe = false) ?: return
        store(data, offset, length, position)
        samples.addLast(Sample(false, position, length, timestampUs, false))
    }

    /** The headset connection restarted; what follows can only be decoded from a keyframe. */
    @Synchronized
    fun onStreamRestart() {
        waitingForKeyframe = true
    }

    /** Seconds of stream currently held. */
    @Synchronized
    fun bufferedSeconds(): Int =
        if (samples.isEmpty()) 0 else ((samples.last().timestampUs - samples.first().timestampUs) / 1_000_000).toInt()

    /**
     * Pins the newest [durationUs] of the stream, rounded back to a keyframe.
     * Null when nothing is buffered or another snapshot is still pinned.
     */
    @Synchronized
    fun snapshot(durationUs: Long): Snapshot? {
        val sets = parameterSets
        if (closed || sets == null || samples.isEmpty() || pinned >= 0) return null
        val cut = samples.last().timestampUs - durationUs
        var start = 0
        samples.forEachIndexed { index, sample ->
            if (sample.keyframe && sample.timestampUs <= cut) start = index
        }
        val selected = samples.subList(start, samples.size).toList()
        pinned = selected.first().position
        return Snapshot(sets, selected)
    }

    /** The bytes of a pinned [sample]. Valid until [advancePin] moves past it. */
    fun data(sample: Sample): ByteBuffer {
        val offset = (sample.position % capacity).toInt()
        val view = storage.duplicate()
        view.limit(offset + sample.size)
        view.position(offset)
        return view.slice()
    }

    /** Frees everything before [sample]; call once earlier samples are written out. */
    @Synchronized
    fun advancePin(sample: Sample) {
        if (pinned >= 0) pinned = sample.position
    }

    @Synchronized
    fun unpin() {
        pinned = -1
    }

    /** Stops accepting data. After this the storage is touched only by a pinned reader. */
    @Synchronized
    fun close() {
        closed = true
    }

    private fun setParameterSets(new: H264.ParameterSets) {
        if (parameterSets?.sameAs(new) == true) return
        // One MP4 track has one SPS, so the older stretch cannot go into the same file.
        clear()
        parameterSets = new
    }

    /** Finds room for [size] bytes, dropping old data; null when the sample must be skipped. */
    private fun reserve(size: Int, keyframe: Boolean): Long? {
        if (waitingForKeyframe && !keyframe) return null
        if (size > capacity) {
            clear()
            return null
        }
        var position = writePosition
        val offset = (position % capacity).toInt()
        // A sample is never split across the end of the block.
        if (offset + size > capacity) position += capacity - offset
        if (pinned >= 0 && position + size - pinned > capacity) {
            // A running save holds this space, and dropping history would not free it.
            // The chain of frames breaks here.
            waitingForKeyframe = true
            return null
        }
        while (samples.isNotEmpty() && position + size - samples.first().position > capacity) dropOldestGop()
        return if (waitingForKeyframe && !keyframe) null else position
    }

    private fun store(data: ByteArray, offset: Int, length: Int, position: Long) {
        writer.limit(capacity)
        writer.position((position % capacity).toInt())
        writer.put(data, offset, length)
        writePosition = position + length
    }

    private fun dropOldestGop() {
        samples.removeFirstOrNull()
        keyframeTimes.removeFirstOrNull()
        while (samples.isNotEmpty() && !samples.first().keyframe) samples.removeFirst()
        if (samples.isEmpty()) waitingForKeyframe = true
    }

    private fun clear() {
        samples.clear()
        keyframeTimes.clear()
        waitingForKeyframe = true
    }
}

/**
 * Places PCM chunks on a gapless timeline. Chunk arrival times jitter and AAC needs
 * rising timestamps, so chunks simply follow each other unless audio really paused.
 */
class AudioClock(private val maxGapUs: Long = 100_000) {
    private var nextUs = 0L

    /** Start time for a chunk of [durationUs] that finished arriving at [arrivalUs]. */
    fun place(arrivalUs: Long, durationUs: Long): Long {
        val ideal = arrivalUs - durationUs
        val start = if (ideal > nextUs + maxGapUs) ideal else nextUs
        nextUs = start + durationUs
        return start
    }
}
