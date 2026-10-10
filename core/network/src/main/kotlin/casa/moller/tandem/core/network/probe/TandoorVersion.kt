package casa.moller.tandem.core.network.probe

/** A Tandoor release version such as `2.6.13` (a `v` prefix and suffixes like `-rc1` are ignored). */
data class TandoorVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<TandoorVersion> {

    override fun compareTo(other: TandoorVersion): Int =
        compareValuesBy(this, other, TandoorVersion::major, TandoorVersion::minor, TandoorVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d{1,4})\.(\d{1,4})(?:\.(\d{1,6}))?(?:[-+.~ ].*)?$""")

        fun parse(raw: String?): TandoorVersion? {
            val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val (major, minor, patch) = match.destructured
            return TandoorVersion(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())
        }
    }
}

/** How the app treats a server version (plan 3.6). */
enum class Compatibility {
    /** Inside the tested line (2.6.x): everything enabled. */
    SUPPORTED,

    /** Newer than the tested line: works, but later phases run it in safe mode (outbox paused). */
    UNTESTED_NEWER,

    /**
     * Tandoor 2.0.0 to 2.5.x: older than the tested line but the same 2.x API generation (plan
     * 4.5: "any Tandoor >= 2.0"). Login is allowed; the app runs in safe mode like [UNTESTED_NEWER].
     */
    UNTESTED_OLDER,

    /** Older than 2.0.0 (Tandoor 1.x, a different API): login is refused. */
    TOO_OLD,

    /** The version string could not be parsed (e.g. a development build); treat like [UNTESTED_NEWER]. */
    UNRECOGNIZED,
}

/**
 * The tested range. Contract tests ran against 2.6.13 (FACTS); 2.6.0 to 2.6.15 changed no fields
 * the app uses (plan 3.6), so the whole 2.6 line counts as supported. Other 2.x versions may log
 * in and run in safe mode; 1.x is refused (plan 4.5 and 6.2: "too old" means < 2.0).
 */
object TandoorCompatibility {
    /** Oldest version the app logs in to at all (in safe mode below [MIN_SUPPORTED]). */
    val MIN_LOGIN: TandoorVersion = TandoorVersion(2, 0, 0)

    val MIN_SUPPORTED: TandoorVersion = TandoorVersion(2, 6, 0)

    /** First version that is no longer in the tested line. */
    val FIRST_UNTESTED: TandoorVersion = TandoorVersion(2, 7, 0)

    fun classify(version: TandoorVersion?): Compatibility = when {
        version == null -> Compatibility.UNRECOGNIZED
        version < MIN_LOGIN -> Compatibility.TOO_OLD
        version < MIN_SUPPORTED -> Compatibility.UNTESTED_OLDER
        version < FIRST_UNTESTED -> Compatibility.SUPPORTED
        else -> Compatibility.UNTESTED_NEWER
    }

    fun classify(rawVersion: String?): Compatibility = classify(TandoorVersion.parse(rawVersion))
}
