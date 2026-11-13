package social.tbone.core

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal platform-neutral logger.
 *
 * Upstream T-Bone logged via Timber (Android). The desktop port keeps the same
 * call sites and intent — structured, tag-prefixed diagnostics — without
 * pulling in an Android dependency. Output goes to stderr and, when
 * [initFileLogging] has been called, also to a rotating log file.
 */
object Log {

    @Volatile private var logFile: File? = null
    @Volatile var verbose: Boolean = System.getenv("TBONE_VERBOSE") == "1"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /** Enable file logging in [dir] (created if missing). Returns the log file. */
    fun initFileLogging(dir: File): File {
        dir.mkdirs()
        val f = File(dir, "t-bone-desktop.log")
        // Cap the log at ~2 MB so long sessions cannot grow without bound.
        if (f.isFile && f.length() > 2_000_000) {
            val old = File(dir, "t-bone-desktop.log.1")
            old.delete()
            f.renameTo(old)
        }
        logFile = f
        return f
    }

    // Timber-compatible single-argument / (Throwable, message) overloads, so call
    // sites ported from upstream keep working with the same shapes.
    private const val DEFAULT_TAG = "T-Bone"

    fun d(msg: String) = d(DEFAULT_TAG, msg)
    fun i(msg: String) = i(DEFAULT_TAG, msg)
    fun w(msg: String) = w(DEFAULT_TAG, msg, null)
    fun e(msg: String) = e(DEFAULT_TAG, msg, null)
    fun w(t: Throwable?, msg: String) = w(DEFAULT_TAG, msg, t)
    fun e(t: Throwable?, msg: String) = e(DEFAULT_TAG, msg, t)

    fun d(tag: String, msg: String) { write('D', tag, msg, null, verbose) }
    fun i(tag: String, msg: String) { write('I', tag, msg, null, true) }
    fun w(tag: String, msg: String, t: Throwable? = null) { write('W', tag, msg, t, true) }
    fun e(tag: String, msg: String, t: Throwable? = null) { write('E', tag, msg, t, true) }

    private fun write(level: Char, tag: String, msg: String, t: Throwable?, print: Boolean) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(lock) {
            if (print) System.err.println(line)
            t?.let { if (print) it.printStackTrace() }
            logFile?.let { f ->
                runCatching {
                    // UTF-8 explicitly: FileWriter defaults to the platform charset,
                    // which mangles any non-ASCII text (an ellipsis in a truncated id,
                    // a note's Japanese content) into '?' in the file the user is asked
                    // to attach to a bug report.
                    PrintWriter(
                        Files.newBufferedWriter(
                            f.toPath(),
                            Charsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                        )
                    ).use { pw ->
                        pw.println(line)
                        t?.printStackTrace(pw)
                    }
                }
            }
        }
    }
}
