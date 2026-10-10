package casa.moller.tandem.core.common.crash

import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The build and the device a crash happened on: the header of every crash log (plan 4.2 p7,
 * "Exportera fellogg"). Nothing here identifies the user or the server.
 */
data class CrashLogEnvironment(
    val versionName: String,
    val versionCode: Long,
    /** `debug`, `release` or `minified`. */
    val buildType: String,
    /** The product flavor: `sideload`, `play` or `fdroid`. */
    val distribution: String,
    /** `Build.VERSION.RELEASE`, e.g. `16`. */
    val androidRelease: String,
    val sdkInt: Int,
    /** `Build.MANUFACTURER` and `Build.MODEL`. */
    val manufacturer: String,
    val model: String,
)

/**
 * Turns an uncaught throwable into the text of a crash log: a header with app version, build
 * type, Android version and device model, then the stack trace.
 *
 * Privacy first: the stack trace keeps class names and code locations, but every exception
 * message (and the thread name) goes through [redact] first, which removes what could identify
 * the user or open their account: tokens, URLs (with or without credentials), e-mail addresses,
 * quoted values, JSON bodies and `key=value` secrets. A redacted message is cut to
 * [MAX_MESSAGE_LENGTH] characters.
 *
 * Never throws: a crash handler must not fail on a strange throwable (a cyclic cause chain, a
 * `getMessage()` that throws, a stack trace of thousands of frames).
 */
object CrashLogFormatter {
    /** Upper bound of one crash log; the stack trace is cut, the header is always kept. */
    const val MAX_LOG_LENGTH: Int = 24 * 1024

    const val MAX_MESSAGE_LENGTH: Int = 500
    private const val MAX_FRAMES_PER_THROWABLE = 60
    private const val MAX_NESTING = 8
    private const val MAX_THROWABLES = 24

    fun format(environment: CrashLogEnvironment, threadName: String, throwable: Throwable, timeMillis: Long): String {
        val header = buildString {
            appendLine("Tandem crash log")
            appendLine("Time (UTC): ${safe { Instant.ofEpochMilli(timeMillis).toString() } ?: timeMillis}")
            appendLine(
                "App: Tandem ${environment.versionName} (versionCode ${environment.versionCode}), " +
                    "${environment.buildType} build, ${environment.distribution}",
            )
            appendLine("Android: ${environment.androidRelease} (SDK ${environment.sdkInt})")
            appendLine("Device: ${environment.manufacturer} ${environment.model}")
            appendLine("Thread: ${redact(threadName)}")
            appendLine()
        }
        val trace = safe { stackTrace(throwable) } ?: "${safe { throwable.javaClass.name }}: (stack trace unavailable)\n"
        val text = header + trace
        return if (text.length <= MAX_LOG_LENGTH) text else text.take(MAX_LOG_LENGTH) + "\n… (cut)\n"
    }

    /** The stack trace like [Throwable.printStackTrace], with redacted messages and bounds. */
    internal fun stackTrace(throwable: Throwable): String {
        val out = StringBuilder()
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        appendThrowable(out, throwable, enclosing = emptyArray(), prefix = "", caption = "", depth = 0, seen = seen)
        return out.toString()
    }

    private fun appendThrowable(
        out: StringBuilder,
        throwable: Throwable,
        enclosing: Array<StackTraceElement>,
        prefix: String,
        caption: String,
        depth: Int,
        seen: MutableSet<Throwable>,
    ) {
        if (!seen.add(throwable)) {
            out.append(prefix).append(caption).append("[CIRCULAR REFERENCE: ").append(throwable.javaClass.name).append("]\n")
            return
        }
        if (depth > MAX_NESTING || seen.size > MAX_THROWABLES) {
            out.append(prefix).append(caption).append(throwable.javaClass.name).append(" (more causes left out)\n")
            return
        }
        out.append(prefix).append(caption).append(describe(throwable)).append('\n')

        val frames = safe { throwable.stackTrace } ?: emptyArray()
        // Frames shared with the enclosing trace are printed as "... n more", like the JVM does.
        var own = frames.size - 1
        var other = enclosing.size - 1
        while (own >= 0 && other >= 0 && frames[own] == enclosing[other]) {
            own--
            other--
        }
        val common = frames.size - 1 - own
        val shown = minOf(own + 1, MAX_FRAMES_PER_THROWABLE)
        for (i in 0 until shown) out.append(prefix).append("\tat ").append(frames[i]).append('\n')
        if (shown < own + 1) out.append(prefix).append("\t... ").append(own + 1 - shown).append(" frames left out\n")
        if (common > 0) out.append(prefix).append("\t... ").append(common).append(" more\n")

        for (suppressed in safe { throwable.suppressed } ?: emptyArray()) {
            appendThrowable(out, suppressed, frames, "$prefix\t", "Suppressed: ", depth + 1, seen)
        }
        safe { throwable.cause }?.let { appendThrowable(out, it, frames, prefix, "Caused by: ", depth + 1, seen) }
    }

    /** `class.Name: redacted message`; never calls a possibly overridden toString(). */
    private fun describe(throwable: Throwable): String {
        val name = throwable.javaClass.name
        val message = safe { throwable.message } ?: return name
        return "$name: ${redact(message)}"
    }

    /**
     * Removes anything that may identify the user or their account from [text] and cuts it to
     * [MAX_MESSAGE_LENGTH] characters.
     */
    fun redact(text: String): String {
        // Bounded first, so that the patterns never run over a huge message.
        var result = text.take(MAX_MESSAGE_LENGTH * 8)
        for ((pattern, replacement) in REDACTIONS) result = pattern.replace(result, replacement)
        return if (result.length <= MAX_MESSAGE_LENGTH) result else result.take(MAX_MESSAGE_LENGTH) + "…"
    }

    /**
     * Applied in order. The broad ones (whole input, bodies, URLs) run first, so that the later
     * ones never see half a URL.
     */
    private val REDACTIONS: List<Pair<Regex, String>> = listOf(
        // kotlinx.serialization quotes the input after "JSON input:".
        Regex("""(?is)JSON input:.*""") to "JSON input: <redacted>",
        // Request or response bodies.
        Regex("""(?s)\{.*\}""") to "{<redacted>}",
        // Any URL, with or without user:password@, including OkHttp thread names.
        Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S+""") to "<url>",
        // user:password@host without a scheme.
        Regex("""\S+:\S+@\S+""") to "<credentials>",
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "<email>",
        // Tandoor tokens and Authorization header values.
        Regex("""\btda_[A-Za-z0-9_-]*""") to "<token>",
        Regex("""(?i)\b(bearer|basic|token)\s+[A-Za-z0-9._~+/=-]{6,}""") to "$1 <redacted>",
        Regex(
            """(?i)\b(password|passwd|pwd|passphrase|token|secret|authorization|cookie|session|username|user|api[_-]?key)""" +
                """(["']?\s*[=:]\s*)\S+""",
        ) to "$1$2<redacted>",
        // Quoted values: user input, host names, JSON fields. Java's 'Class' quotes stay.
        Regex(""""[^"\n]*"""") to "\"<redacted>\"",
        Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""") to "<ip>",
        // Long opaque strings (tokens, session ids, hashes) whatever their prefix.
        Regex("""\b[A-Za-z0-9_-]{32,}\b""") to "<redacted>",
    )

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        // Even an Error: the caller is a crash handler, the process is going down anyway.
        null
    }
}
