package xyz.lukioff.webstreamvr.replay

import android.content.ContentResolver
import android.os.Process
import android.os.SharedMemory
import android.system.ErrnoException
import android.util.Log
import java.nio.ByteBuffer

/**
 * Owns the memory of a [ReplayBuffer] and runs saves on their own thread, one at a time.
 *
 * The block comes from [SharedMemory] because ByteBuffer.allocateDirect on Android
 * lives in the Java heap and would hit the app's heap limit. Pages are only committed
 * once written, so the footprint grows up to the budget during the first minutes.
 */
class ReplayRecorder private constructor(
    private val resolver: ContentResolver,
    private val memory: SharedMemory,
    private val mapped: ByteBuffer,
    windowUs: Long,
) {
    enum class SaveStart { STARTED, BUSY, EMPTY }

    val buffer = ReplayBuffer(mapped, windowUs)
    private val lock = Any()
    private var saving = false
    private var closed = false

    /** Writes the newest [durationUs] to a file; [done] gets it, or null on failure, on the saving thread. */
    fun save(durationUs: Long, done: (SavedReplay?) -> Unit): SaveStart {
        val snapshot = synchronized(lock) {
            if (saving) return SaveStart.BUSY
            if (closed) return SaveStart.EMPTY
            val snapshot = buffer.snapshot(durationUs) ?: return SaveStart.EMPTY
            saving = true
            snapshot
        }
        Thread({
            // The game keeps the CPU; a save may take a little longer instead.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val saved = try {
                ReplayWriter.save(resolver, buffer, snapshot)
            } catch (e: Exception) {
                Log.e(TAG, "Replay save failed", e)
                null
            }
            buffer.unpin()
            synchronized(lock) {
                saving = false
                if (closed) release()
            }
            done(saved)
        }, "ReplaySave").start()
        return SaveStart.STARTED
    }

    /** Frees the memory now, or after the running save: a reader must never touch unmapped pages. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            buffer.close()
            if (!saving) release()
        }
    }

    private fun release() {
        SharedMemory.unmap(mapped)
        memory.close()
    }

    companion object {
        private const val TAG = "ReplayRecorder"

        /** Null when the system refuses the memory; the stream then runs without replay. */
        fun create(resolver: ContentResolver, seconds: Int, bytes: Int): ReplayRecorder? {
            val memory = try {
                SharedMemory.create("replay", bytes)
            } catch (e: ErrnoException) {
                Log.e(TAG, "No memory for the replay buffer", e)
                return null
            }
            return try {
                ReplayRecorder(resolver, memory, memory.mapReadWrite(), seconds * 1_000_000L)
            } catch (e: ErrnoException) {
                Log.e(TAG, "Cannot map the replay buffer", e)
                memory.close()
                null
            }
        }
    }
}
