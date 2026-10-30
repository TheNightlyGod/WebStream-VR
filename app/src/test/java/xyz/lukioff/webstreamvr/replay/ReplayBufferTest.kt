package xyz.lukioff.webstreamvr.replay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.lukioff.webstreamvr.web.H264
import java.nio.ByteBuffer

class ReplayBufferTest {

    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0, 0, 1, 0x68, 0x11)
    private val otherConfig = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x11)

    private fun frame(keyframe: Boolean, size: Int = 16, fill: Byte = 7): ByteArray {
        val bytes = ByteArray(size) { fill }
        bytes[0] = 0
        bytes[1] = 0
        bytes[2] = 0
        bytes[3] = 1
        bytes[4] = if (keyframe) 0x65 else 0x41
        return bytes
    }

    private fun buffer(capacity: Int = 4096, windowUs: Long = 10_000_000) =
        ReplayBuffer(ByteBuffer.allocate(capacity), windowUs).apply { onVideo(config, 0, config.size, 0) }

    private fun ReplayBuffer.video(keyframe: Boolean, timestampUs: Long, size: Int = 16, fill: Byte = 7) {
        val data = frame(keyframe, size, fill)
        onVideo(data, 0, data.size, timestampUs)
    }

    private fun ReplayBuffer.audio(timestampUs: Long, size: Int = 8) = onAudio(ByteArray(size), 0, size, timestampUs)

    @Test
    fun startsAtFirstKeyframe() {
        val replay = buffer()
        replay.audio(1)
        replay.video(false, 2)
        replay.video(true, 3)
        replay.audio(4)
        val samples = replay.snapshot(Long.MAX_VALUE / 2)!!.samples
        assertEquals(listOf(3L, 4L), samples.map { it.timestampUs })
    }

    @Test
    fun dropsWholeGopsOutsideWindow() {
        val replay = buffer(windowUs = 3_000_000)
        for (second in 0L..7L) {
            val t = second * 1_000_000
            replay.video(second % 2 == 0L, t)
            replay.audio(t + 1)
        }
        // At 7 s the window opens at 4 s, and 4 s is a keyframe.
        val samples = replay.snapshot(Long.MAX_VALUE / 2)!!.samples
        assertEquals(4_000_000L, samples.first().timestampUs)
        assertTrue(samples.first().keyframe)
        assertEquals(3, replay.bufferedSeconds())
    }

    @Test
    fun snapshotRoundsBackToKeyframe() {
        val replay = buffer()
        for (second in 0L..6L) replay.video(second % 2 == 0L, second * 1_000_000)
        replay.video(true, 7_000_000)
        val samples = replay.snapshot(3_000_000)!!.samples
        assertEquals(4_000_000L, samples.first().timestampUs)
    }

    @Test
    fun evictsWhenMemoryRunsOut() {
        val replay = buffer(capacity = 256)
        for (i in 0L until 100L) replay.video(i % 4 == 0L, i, size = 32)
        val samples = replay.snapshot(Long.MAX_VALUE / 2)!!.samples
        assertTrue(samples.first().keyframe)
        assertEquals(99L, samples.last().timestampUs)
        assertTrue(samples.sumOf { it.size } <= 256)
    }

    @Test
    fun sampleBytesSurviveWrapAround() {
        val replay = buffer(capacity = 256)
        for (i in 0 until 40) replay.video(i % 4 == 0, i.toLong(), size = 40, fill = i.toByte())
        for (sample in replay.snapshot(Long.MAX_VALUE / 2)!!.samples) {
            val data = replay.data(sample)
            assertEquals(40, data.remaining())
            assertEquals(sample.timestampUs.toByte(), data.get(39))
        }
    }

    @Test
    fun pinProtectsDataWhileWritingContinues() {
        val replay = buffer(capacity = 256)
        replay.video(true, 0, size = 40, fill = 1)
        replay.video(false, 1, size = 40, fill = 2)
        val snapshot = replay.snapshot(Long.MAX_VALUE / 2)!!
        assertNull("only one snapshot at a time", replay.snapshot(Long.MAX_VALUE / 2))

        for (i in 2L until 50L) replay.video(i % 4 == 0L, i, size = 40, fill = 9)
        assertEquals(1.toByte(), replay.data(snapshot.samples[0]).get(39))
        assertEquals(2.toByte(), replay.data(snapshot.samples[1]).get(39))

        // Once the pin is gone the stream takes the block over from the next keyframe.
        replay.unpin()
        for (i in 50L until 100L) replay.video(i % 4 == 0L, i, size = 40, fill = 9)
        val after = replay.snapshot(Long.MAX_VALUE / 2)!!.samples
        assertTrue(after.first().keyframe)
        assertEquals(99L, after.last().timestampUs)
    }

    @Test
    fun skipsToNextKeyframeWhenPinFillsMemory() {
        val replay = buffer(capacity = 128)
        replay.video(true, 0, size = 40)
        replay.video(false, 1, size = 40)
        replay.video(false, 2, size = 40)
        assertNotNull(replay.snapshot(Long.MAX_VALUE / 2))
        replay.video(false, 3, size = 40) // no room: the frame chain is broken
        replay.unpin()
        replay.video(false, 4, size = 40)
        replay.video(true, 5, size = 40)
        val times = replay.snapshot(Long.MAX_VALUE / 2)!!.samples.map { it.timestampUs }
        assertEquals(listOf(5L), times)
    }

    @Test
    fun newParameterSetsStartAFreshBuffer() {
        val replay = buffer()
        replay.video(true, 1)
        replay.onVideo(config, 0, config.size, 2)
        assertEquals(1, replay.snapshot(1)!!.samples.size)
        replay.unpin()

        replay.onVideo(otherConfig, 0, otherConfig.size, 3)
        assertNull(replay.snapshot(1))
        replay.video(true, 4)
        val snapshot = replay.snapshot(1)!!
        assertEquals(listOf(4L), snapshot.samples.map { it.timestampUs })
        assertArrayEquals(H264.parameterSets(otherConfig, 0, otherConfig.size)!!.sps, snapshot.parameterSets.sps)
    }

    @Test
    fun restartWaitsForKeyframeButKeepsHistory() {
        val replay = buffer()
        replay.video(true, 1)
        replay.onStreamRestart()
        replay.video(false, 2)
        replay.audio(3)
        replay.video(true, 4)
        assertEquals(listOf(1L, 4L), replay.snapshot(Long.MAX_VALUE / 2)!!.samples.map { it.timestampUs })
    }

    @Test
    fun ignoresDataAfterClose() {
        val replay = buffer()
        replay.video(true, 1)
        replay.close()
        replay.video(true, 2)
        assertNull(replay.snapshot(1))
    }

    @Test
    fun audioClockIsGaplessUnlessAudioPaused() {
        val clock = AudioClock(maxGapUs = 100)
        assertEquals(0L, clock.place(arrivalUs = 1000, durationUs = 1000))
        // Late and early arrivals both stay on the timeline.
        assertEquals(1000L, clock.place(arrivalUs = 2050, durationUs = 1000))
        assertEquals(2000L, clock.place(arrivalUs = 2950, durationUs = 1000))
        // A real pause leaves a hole.
        assertEquals(9000L, clock.place(arrivalUs = 10000, durationUs = 1000))
    }
}
