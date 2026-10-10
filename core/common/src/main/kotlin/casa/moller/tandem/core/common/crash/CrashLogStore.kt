package casa.moller.tandem.core.common.crash

import java.io.File

/** One stored crash log. */
data class CrashLog(val fileName: String, val timeMillis: Long, val text: String)

/**
 * The crash logs on this device: small text files in [directory] (the app passes a folder in
 * `noBackupFilesDir`, so they never leave the phone with a backup). Only the newest [maxLogs]
 * are kept. Nothing is ever sent anywhere; the user can view, copy, share or delete them.
 *
 * [directory] is resolved when it is needed, not when the store is created: the crash handler
 * is installed before anything else runs and must not do I/O then.
 *
 * Every method swallows exceptions (I/O, security): a crash log is a convenience, never a
 * reason to fail. [CrashLogHandler] catches anything else.
 */
class CrashLogStore(private val directory: () -> File, private val maxLogs: Int = DEFAULT_MAX_LOGS) {
    init {
        require(maxLogs >= 1) { "maxLogs must be at least 1" }
    }

    /**
     * Writes [text] as the newest log and deletes the oldest beyond [maxLogs]. Returns the file,
     * or null if it could not be written.
     */
    fun write(text: String, timeMillis: Long): File? = guarded {
        val dir = directory()
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@guarded null
        var target = File(dir, fileName(timeMillis, 0))
        var n = 1
        while (target.exists() && n < MAX_SAME_MILLIS) target = File(dir, fileName(timeMillis, n++))
        // Written next to the target and renamed, so a half-written log is never shown.
        val partial = File(dir, target.name + PARTIAL_SUFFIX)
        partial.writeText(text.take(CrashLogFormatter.MAX_LOG_LENGTH + TRUNCATION_SLACK), Charsets.UTF_8)
        val written = if (partial.renameTo(target)) target else null
        partial.delete()
        rotate(dir)
        written
    }

    /** The stored logs, newest first; unreadable files are skipped. */
    fun read(): List<CrashLog> = guarded {
        logFiles(directory()).mapNotNull { file ->
            val time = timeOf(file.name) ?: return@mapNotNull null
            val text = guarded { file.readText(Charsets.UTF_8).take(CrashLogFormatter.MAX_LOG_LENGTH + TRUNCATION_SLACK) }
            text?.let { CrashLog(file.name, time, it) }
        }
    } ?: emptyList()

    /** Deletes every crash log (and leftovers of an interrupted write). */
    fun deleteAll() {
        guarded {
            directory().listFiles()?.filter { it.isFile && it.name.startsWith(PREFIX) }?.forEach { it.delete() }
        }
    }

    private fun rotate(dir: File) {
        logFiles(dir).drop(maxLogs).forEach { it.delete() }
    }

    /** Log files of [dir], newest first (the names sort by time). */
    private fun logFiles(dir: File): List<File> = dir.listFiles()
        ?.filter { it.isFile && timeOf(it.name) != null }
        ?.sortedByDescending { it.name }
        .orEmpty()

    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Plan 4.2 p7: keep only the last three. */
        const val DEFAULT_MAX_LOGS: Int = 3

        /** Folder name inside `noBackupFilesDir`. */
        const val DIRECTORY_NAME: String = "crash-logs"

        private const val PREFIX = "crash-"
        private const val SUFFIX = ".txt"
        private const val PARTIAL_SUFFIX = ".partial"
        private const val MAX_SAME_MILLIS = 100
        private const val TRUNCATION_SLACK = 64
        private const val MAX_TIME = 9_999_999_999_999L
        private val NAME = Regex("""^crash-(\d{13})-(\d{2})\.txt$""")

        /** `crash-<13-digit epoch millis>-<nn>.txt`: sorts by time as plain text. */
        internal fun fileName(timeMillis: Long, n: Int): String {
            val time = timeMillis.coerceIn(0, MAX_TIME).toString().padStart(13, '0')
            return PREFIX + time + "-" + n.toString().padStart(2, '0') + SUFFIX
        }

        internal fun timeOf(name: String): Long? = NAME.find(name)?.groupValues?.get(1)?.toLongOrNull()
    }
}
