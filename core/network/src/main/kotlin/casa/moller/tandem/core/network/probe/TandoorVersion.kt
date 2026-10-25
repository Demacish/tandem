package casa.moller.tandem.core.network.probe

import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.network.capabilities.TandoorVersionTable

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

/**
 * How the app treats a server version (plan 3.6). The login flow's view of
 * [ServerCompatibility]; both come from [TandoorVersionTable].
 */
enum class Compatibility {
    /** A tested version (2.6.0 to 2.6.15, 2.7.1): everything enabled. */
    SUPPORTED,

    /**
     * Newer than the tested versions (2.6.16 and later): works, in safe mode (outbox paused).
     * Beyond the supported range (2.8.0 and later) writes are disabled (`VersionAssessment.writePolicy`).
     */
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

/** Classifies versions with [TandoorVersionTable] (the tested versions and the supported range). */
object TandoorCompatibility {
    fun classify(version: TandoorVersion?): Compatibility = classify(version?.toString())

    fun classify(rawVersion: String?): Compatibility = when (TandoorVersionTable.assess(rawVersion).compatibility) {
        ServerCompatibility.SUPPORTED -> Compatibility.SUPPORTED
        ServerCompatibility.UNTESTED_NEWER -> Compatibility.UNTESTED_NEWER
        ServerCompatibility.UNTESTED_OLDER -> Compatibility.UNTESTED_OLDER
        ServerCompatibility.TOO_OLD -> Compatibility.TOO_OLD
        ServerCompatibility.UNRECOGNIZED -> Compatibility.UNRECOGNIZED
    }
}
