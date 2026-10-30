package xyz.lukioff.webstreamvr.replay

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import xyz.lukioff.webstreamvr.web.H264
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/** A finished MP4 in the shared Movies folder. */
class SavedReplay(val name: String, val uri: Uri, val size: Long)

/** Turns a [ReplayBuffer.Snapshot] into an MP4 in Movies/Replays, where the headset gallery finds it. */
object ReplayWriter {
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss_SSS")

    fun save(resolver: ContentResolver, buffer: ReplayBuffer, snapshot: ReplayBuffer.Snapshot): SavedReplay {
        val name = "replay_${LocalDateTime.now().format(FILE_TIME)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Replays")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
        try {
            val size = resolver.openFileDescriptor(uri, "w")?.use { file ->
                mux(file.fileDescriptor, buffer, snapshot)
                file.statSize
            } ?: throw IOException("Cannot open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return SavedReplay(name, uri, size)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun mux(file: FileDescriptor, buffer: ReplayBuffer, snapshot: ReplayBuffer.Snapshot) {
        val muxer = MediaMuxer(file, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audio: AacTrack? = null
        try {
            val videoTrack = muxer.addTrack(videoFormat(snapshot.parameterSets))
            if (snapshot.samples.any { !it.video }) audio = AacTrack(muxer)
            muxer.start()
            val startUs = snapshot.samples.first().timestampUs
            val info = MediaCodec.BufferInfo()
            var videoUs = -1L
            for (sample in snapshot.samples) {
                if (sample.video) {
                    // MP4 needs rising timestamps; arrival times can tie.
                    videoUs = max(sample.timestampUs - startUs, videoUs + 1)
                    val flags = if (sample.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, sample.size, videoUs, flags)
                    muxer.writeSampleData(videoTrack, buffer.data(sample), info)
                } else {
                    audio?.encode(buffer.data(sample), sample.timestampUs - startUs)
                }
                buffer.advancePin(sample)
            }
            audio?.finish()
            muxer.stop()
        } finally {
            audio?.release()
            muxer.release()
        }
    }

    private fun videoFormat(sets: H264.ParameterSets): MediaFormat {
        val (width, height) = H264.frameSize(sets.sps)
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sets.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(sets.pps))
        }
    }

    /**
     * Encodes the stored PCM (48 kHz stereo, 16-bit big endian) to AAC during the save,
     * so an idle buffer costs no CPU. The headset only sends raw audio.
     */
    private class AacTrack(private val muxer: MediaMuxer) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val clock = AudioClock()
        private val info = MediaCodec.BufferInfo()
        private val track: Int
        private var lastUs = 0L

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            // The muxer wants its tracks up front, before the encoder has produced anything.
            track = muxer.addTrack(MediaFormat(format).apply { setByteBuffer("csd-0", ByteBuffer.wrap(AUDIO_SPECIFIC_CONFIG)) })
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun encode(pcm: ByteBuffer, arrivalUs: Long) {
            val durationUs = pcm.remaining() / BYTES_PER_FRAME * 1_000_000L / SAMPLE_RATE
            var timeUs = clock.place(arrivalUs, durationUs)
            pcm.order(ByteOrder.BIG_ENDIAN)
            while (pcm.remaining() >= BYTES_PER_FRAME) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    drain(0)
                    continue
                }
                val input = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                val frames = min(input.remaining(), pcm.remaining()) / BYTES_PER_FRAME
                repeat(frames * CHANNELS) { input.putShort(pcm.short) }
                codec.queueInputBuffer(index, 0, frames * BYTES_PER_FRAME, timeUs, 0)
                timeUs += frames * 1_000_000L / SAMPLE_RATE
                drain(0)
            }
        }

        fun finish() {
            var index = codec.dequeueInputBuffer(TIMEOUT_US)
            while (index < 0) {
                drain(0)
                index = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            codec.queueInputBuffer(index, 0, 0, lastUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var attempts = 0
            while (!drain(TIMEOUT_US)) {
                if (++attempts > MAX_FINISH_ATTEMPTS) throw IOException("AAC encoder did not finish")
            }
        }

        fun release() {
            runCatching { codec.stop() }
            codec.release()
        }

        /** Moves finished AAC frames to the muxer; true once the end of stream came out. */
        private fun drain(timeoutUs: Long): Boolean {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, timeoutUs)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
                if (index < 0) continue // format change; the track is already set up
                val isEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    lastUs = max(lastUs, info.presentationTimeUs)
                    info.presentationTimeUs = lastUs
                    muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (isEnd) return true
            }
        }

        private companion object {
            const val SAMPLE_RATE = 48000
            const val CHANNELS = 2
            const val BYTES_PER_FRAME = 2 * CHANNELS
            const val BIT_RATE = 192_000
            const val TIMEOUT_US = 10_000L
            const val MAX_FINISH_ATTEMPTS = 500
            // AAC LC, 48 kHz, stereo.
            val AUDIO_SPECIFIC_CONFIG = byteArrayOf(0x11, 0x90.toByte())
        }
    }
}
