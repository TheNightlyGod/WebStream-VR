package xyz.lukioff.webstreamvr.replay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.lukioff.webstreamvr.web.H264

/** Runs the whole save path on a device: real H.264 into the buffer, an MP4 out of MediaStore. */
@RunWith(AndroidJUnit4::class)
class ReplayWriterTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Test
    fun writesPlayableMp4WithVideoAndAudio() {
        val recorder = ReplayRecorder.create(resolver, seconds = 60, bytes = 32 * 1024 * 1024)!!
        feedVideo(recorder.buffer, frames = 90)
        // 3 s of PCM in ~85 ms chunks, arriving next to the frames.
        for (chunk in 0 until 35) {
            val pcm = ByteArray(16320)
            recorder.buffer.onAudio(pcm, 0, pcm.size, 85_000L * (chunk + 1) + 3_000_000)
        }

        var saved: SavedReplay? = null
        val done = Object()
        synchronized(done) {
            assertEquals(ReplayRecorder.SaveStart.STARTED, recorder.save(60_000_000) { result ->
                synchronized(done) {
                    saved = result
                    done.notifyAll()
                }
            })
            done.wait(30_000)
        }
        assertNotNull("save failed or timed out", saved)
        val replay = saved!!
        try {
            assertTrue(replay.size > 0)
            val extractor = MediaExtractor()
            resolver.openAssetFileDescriptor(replay.uri, "r")!!.use { extractor.setDataSource(it) }
            assertEquals(2, extractor.trackCount)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.first { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
            assertEquals(WIDTH, video.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(HEIGHT, video.getInteger(MediaFormat.KEY_HEIGHT))
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC })

            // The file has to open at a keyframe, or a player shows nothing.
            extractor.selectTrack(formats.indexOf(video))
            assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            extractor.release()
        } finally {
            resolver.delete(replay.uri, null, null)
            recorder.close()
        }
    }

    private fun feedVideo(buffer: ReplayBuffer, frames: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var sent = 0
        var finished = false
        while (!finished) {
            if (sent <= frames) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val timeUs = sent * 33_333L
                    if (sent == frames) {
                        codec.queueInputBuffer(input, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val image = codec.getInputImage(input)!!
                        for (plane in image.planes) {
                            val bytes = ByteArray(plane.buffer.remaining()) { (sent * 3).toByte() }
                            plane.buffer.put(bytes)
                        }
                        codec.queueInputBuffer(input, 0, WIDTH * HEIGHT * 3 / 2, timeUs, 0)
                    }
                    sent++
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            if (output >= 0) {
                val bytes = ByteArray(info.size)
                codec.getOutputBuffer(output)!!.get(bytes)
                if (bytes.isNotEmpty()) {
                    assertTrue("encoder output is not Annex-B", H264.scan(bytes, 0, bytes.size).types != 0)
                    buffer.onVideo(bytes, 0, bytes.size, info.presentationTimeUs + TIME_BASE_US)
                }
                finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(output, false)
            }
        }
        codec.stop()
        codec.release()
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val TIME_BASE_US = 3_000_000L
    }
}
