package xyz.lukioff.webstreamvr.diag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Persistent rolling logger + uncaught crash recorder.
 *
 * Writes simultaneously to Android logcat (when running on Android) and a
 * size-bounded file pair (`webstream.log` / `webstream.prev.log`) inside
 * `filesDir/logs/`, while keeping a recent ring buffer in memory for fast UI preview.
 */
object AppLog {
    private const val MAX_BUFFER_LINES = 1500
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_REPORT_LOG_CHARS = 300_000
    private const val CURRENT_LOG_NAME = "webstream.log"
    private const val PREV_LOG_NAME = "webstream.prev.log"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val EXPORT_FILE_NAME = "webstream-vr-logs.txt"

    private val lock = Any()
    private val ring = ArrayDeque<String>(MAX_BUFFER_LINES)
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile private var initialized = false
    @Volatile private var androidLogAvailable = true
    @Volatile private var logDir: File? = null
    @Volatile private var appContext: Context? = null

    private val _hasCrashReport = MutableStateFlow(false)
    val hasCrashReport: StateFlow<Boolean> = _hasCrashReport.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (initialized) return
            initialized = true
            appContext = ctx
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            logDir = dir
            loadTailIntoRing(File(dir, CURRENT_LOG_NAME))
            _hasCrashReport.value = File(dir, CRASH_FILE_NAME).let { it.exists() && it.length() > 0L }
            installCrashHandler(ctx)
        }
        i("AppLog", "=== Session started ===")
        val header = runCatching { Diagnostics.collectHeader(ctx) }
            .getOrElse { "Diagnostics collection failed: ${it.stackTraceToString()}" }
        appendRawBlock(header)
    }

    fun d(tag: String, message: String) = write(Log.DEBUG, "D", tag, message, null)
    fun i(tag: String, message: String) = write(Log.INFO, "I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = write(Log.WARN, "W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = write(Log.ERROR, "E", tag, message, throwable)

    fun recentText(maxLines: Int = 400): String {
        val crash = readCrashReport()
        val lines = synchronized(lock) {
            val total = ring.size
            val skip = (total - maxLines).coerceAtLeast(0)
            ring.drop(skip).joinToString("\n")
        }
        return buildString {
            if (!crash.isNullOrBlank()) {
                appendLine("=== LAST CRASH ===")
                appendLine(crash.trimEnd())
                appendLine()
                appendLine("=== RECENT LOGS ===")
            }
            append(lines)
        }
    }

    fun readCrashReport(): String? {
        val dir = logDir ?: return null
        val file = File(dir, CRASH_FILE_NAME)
        return runCatching {
            if (file.exists() && file.length() > 0L) file.readText() else null
        }.getOrNull()
    }

    fun dismissCrashBanner() {
        _hasCrashReport.value = false
    }

    fun clear(context: Context) {
        val dir = logDir ?: File(context.applicationContext.filesDir, "logs")
        synchronized(lock) {
            ring.clear()
            runCatching { File(dir, CURRENT_LOG_NAME).delete() }
            runCatching { File(dir, PREV_LOG_NAME).delete() }
            runCatching { File(dir, CRASH_FILE_NAME).delete() }
            _hasCrashReport.value = false
            _revision.value = _revision.value + 1
        }
        i("AppLog", "Logs cleared by user")
        val header = runCatching { Diagnostics.collectHeader(context.applicationContext) }.getOrNull()
        if (header != null) appendRawBlock(header)
    }

    fun buildReport(context: Context): String {
        val ctx = context.applicationContext
        val dir = logDir ?: File(ctx.filesDir, "logs")
        val generatedAt = formatNow()
        val header = runCatching { Diagnostics.collectHeader(ctx) }
            .getOrElse { "Diagnostics error: ${it.stackTraceToString()}" }

        val (crashText, prevText, currentText) = synchronized(lock) {
            val crash = runCatching {
                File(dir, CRASH_FILE_NAME).takeIf { it.exists() && it.length() > 0L }?.readText()
            }.getOrNull()
            val prev = runCatching {
                File(dir, PREV_LOG_NAME).takeIf { it.exists() && it.length() > 0L }?.readText()
            }.getOrNull()
            val curr = runCatching {
                File(dir, CURRENT_LOG_NAME).takeIf { it.exists() && it.length() > 0L }?.readText()
            }.getOrElse { ring.joinToString("\n") }
            Triple(crash, prev, curr)
        }

        val combinedLogs = buildString {
            if (!prevText.isNullOrBlank()) {
                appendLine("--- Previous log ---")
                appendLine(prevText.trimEnd())
            }
            if (!currentText.isNullOrBlank()) {
                appendLine("--- Current log ---")
                append(currentText.trimEnd())
            }
        }
        val trimmedLogs = if (combinedLogs.length > MAX_REPORT_LOG_CHARS) {
            "... (truncated earlier lines) ...\n" + combinedLogs.takeLast(MAX_REPORT_LOG_CHARS)
        } else {
            combinedLogs
        }

        return buildString {
            appendLine("=== WebStream-VR Diagnostic Report ===")
            appendLine("generated_at=$generatedAt")
            appendLine()
            appendLine(header)
            if (!crashText.isNullOrBlank()) {
                appendLine()
                appendLine("=== Last Uncaught Crash ===")
                appendLine(crashText.trimEnd())
            }
            appendLine()
            appendLine("=== Logs ===")
            appendLine(trimmedLogs.ifBlank { "(empty)" })
        }
    }

    fun exportReportToCache(context: Context): File {
        val file = File(context.cacheDir, EXPORT_FILE_NAME)
        file.writeText(buildReport(context))
        return file
    }

    fun hexPreview(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, maxBytes: Int = 32): String {
        if (offset < 0 || length <= 0 || offset >= bytes.size) return ""
        val available = minOf(length, bytes.size - offset)
        val count = minOf(available, maxBytes)
        val sb = StringBuilder(count * 3 + 8)
        for (i in 0 until count) {
            if (i > 0) sb.append(' ')
            sb.append(String.format(Locale.US, "%02x", bytes[offset + i].toInt() and 0xFF))
        }
        if (available > maxBytes) sb.append(" ...(+${available - maxBytes}B)")
        return sb.toString()
    }

    private fun write(priority: Int, level: String, tag: String, message: String, throwable: Throwable?) {
        val logcatText = if (throwable != null) "$message\n${stackTraceOf(throwable)}" else message
        logToAndroid(priority, tag, logcatText)

        val time = formatNow()
        val threadName = Thread.currentThread().name
        val entry = buildString {
            append(time).append(' ').append(level).append('/').append(tag)
                .append(" [").append(threadName).append("]: ").append(message)
            if (throwable != null) {
                append('\n').append(stackTraceOf(throwable).trimEnd())
            }
        }
        synchronized(lock) {
            for (line in entry.lineSequence()) {
                pushRingLocked(line)
            }
            appendFileLocked(entry)
            _revision.value = _revision.value + 1
        }
    }

    private fun appendRawBlock(block: String) {
        if (block.isBlank()) return
        synchronized(lock) {
            for (line in block.lineSequence()) {
                pushRingLocked(line)
            }
            appendFileLocked(block)
            _revision.value = _revision.value + 1
        }
    }

    private fun pushRingLocked(line: String) {
        if (ring.size >= MAX_BUFFER_LINES) ring.removeFirst()
        ring.addLast(line)
    }

    private fun appendFileLocked(text: String) {
        val dir = logDir ?: return
        val file = File(dir, CURRENT_LOG_NAME)
        runCatching {
            if (file.exists() && file.length() >= MAX_FILE_BYTES) {
                val prev = File(dir, PREV_LOG_NAME)
                if (prev.exists()) prev.delete()
                file.renameTo(prev)
            }
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(text)
                writer.newLine()
            }
        }
    }

    private fun loadTailIntoRing(file: File) {
        if (!file.exists() || file.length() == 0L) return
        runCatching {
            val allLines = file.readLines(Charsets.UTF_8)
            val start = (allLines.size - MAX_BUFFER_LINES).coerceAtLeast(0)
            for (idx in start until allLines.size) {
                pushRingLocked(allLines[idx])
            }
        }
    }

    private fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = logDir ?: File(context.filesDir, "logs").apply { mkdirs() }
        val time = formatNow()
        val trace = stackTraceOf(throwable)
        val diag = runCatching { Diagnostics.collectHeader(context) }
            .getOrElse { "Diagnostics error during crash: ${it.javaClass.simpleName}: ${it.message}" }
        val recent = synchronized(lock) {
            val skip = (ring.size - 150).coerceAtLeast(0)
            ring.drop(skip).joinToString("\n")
        }
        val crashReport = buildString {
            appendLine("time=$time")
            @Suppress("DEPRECATION")
            appendLine("thread=${thread.name} (id=${thread.id}, state=${thread.state})")
            appendLine("exception=${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine(trace.trimEnd())
            appendLine()
            appendLine(diag)
            if (recent.isNotBlank()) {
                appendLine()
                appendLine("--- Preceding Log Tail ---")
                appendLine(recent)
            }
        }
        synchronized(lock) {
            runCatching { File(dir, CRASH_FILE_NAME).writeText(crashReport, Charsets.UTF_8) }
            val fatalEntry = "$time F/UncaughtException [${thread.name}]: ${throwable.javaClass.name}: ${throwable.message}\n${trace.trimEnd()}"
            for (line in fatalEntry.lineSequence()) pushRingLocked(line)
            appendFileLocked(fatalEntry)
            _hasCrashReport.value = true
        }
    }

    private fun logToAndroid(priority: Int, tag: String, msg: String) {
        if (!androidLogAvailable) return
        try {
            Log.println(priority, tag, msg)
        } catch (_: RuntimeException) {
            // JVM unit tests do not mock android.util.Log.
            androidLogAvailable = false
        }
    }

    private fun formatNow(): String = timestampFormat.get()!!.format(Date())

    private fun stackTraceOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
