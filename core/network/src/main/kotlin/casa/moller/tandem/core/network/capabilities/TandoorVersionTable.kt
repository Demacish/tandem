package casa.moller.tandem.core.network.capabilities

import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.network.probe.TandoorVersion

/**
 * How the app treats one server version (plan 3.6 point 3 and 4).
 *
 * @property rawVersion the version exactly as the server reported it.
 * @property version the parsed version; null for an unparseable string (e.g. a development build).
 * @property compatibility [ServerCompatibility] for the rest of the app.
 * @property isTested the version is in [TandoorVersionTable.TESTED].
 * @property inSupportedRange inside [TandoorVersionTable.SUPPORTED_FROM] ..< [TandoorVersionTable.SUPPORTED_UNTIL].
 * @property writePolicy SEND for tested versions; QUEUE_PAUSED (safe mode) for untested versions
 *   the app still talks to; DISABLED beyond the supported range (plan 3.6 point 4: "outside the
 *   interval writes are switched off until a new app version exists") and for [ServerCompatibility.TOO_OLD].
 *   This is stricter than `ServerCompatibility.writePolicy`, which cannot tell 2.7 from 2.8.
 */
data class VersionAssessment(
    val rawVersion: String?,
    val version: TandoorVersion?,
    val compatibility: ServerCompatibility,
    val isTested: Boolean,
    val inSupportedRange: Boolean,
    val writePolicy: WritePolicy,
)

/**
 * The versions this app build was tested against, and the range it supports (plan 3.6).
 *
 * - **Tested**: 2.6.0 to 2.6.15, and 2.7.1. The contract tests run against 2.6.13 (the baseline)
 *   and the nightly watch against 2.6.15, the newest release of every line and `latest`; 2.6.0 to
 *   2.6.15 changed no field the app uses (plan 3.6). 2.7.1: the password login goes through
 *   allauth (`TandoorAuthenticator`), `CookLog.servings` is a decimal, and every other difference
 *   is reviewed in `contract-tests/watch/reviewed-changes.json`. **2.7.0 is not tested on
 *   purpose**: it refuses every write with a token ("CSRF Failed", fixed in 2.7.1), so it runs in
 *   safe mode. Add a version here when the nightly run against it is green.
 * - **Supported range**: `>= 2.6.0 < 2.8.0`. An untested version inside it runs in safe mode.
 * - Older 2.x (2.0.0 to 2.5.x) may log in and runs in safe mode (plan 4.5: any Tandoor >= 2.0);
 *   1.x is refused. Newer than the range: reading works, writes are disabled.
 */
object TandoorVersionTable {
    val TESTED: List<TandoorVersion> = (0..15).map { TandoorVersion(2, 6, it) } + listOf(TandoorVersion(2, 7, 1))

    /** First version of the supported range (inclusive). */
    val SUPPORTED_FROM: TandoorVersion = TandoorVersion(2, 6, 0)

    /** End of the supported range (exclusive). */
    val SUPPORTED_UNTIL: TandoorVersion = TandoorVersion(2, 8, 0)

    /** Oldest version the app logs in to at all. */
    val MIN_LOGIN: TandoorVersion = TandoorVersion(2, 0, 0)

    fun assess(rawVersion: String?): VersionAssessment {
        val version = TandoorVersion.parse(rawVersion)
        val tested = version != null && version in TESTED
        val inRange = version != null && version >= SUPPORTED_FROM && version < SUPPORTED_UNTIL
        val compatibility = when {
            version == null -> ServerCompatibility.UNRECOGNIZED
            version < MIN_LOGIN -> ServerCompatibility.TOO_OLD
            tested -> ServerCompatibility.SUPPORTED
            version < SUPPORTED_FROM -> ServerCompatibility.UNTESTED_OLDER
            else -> ServerCompatibility.UNTESTED_NEWER
        }
        val writePolicy = when {
            compatibility == ServerCompatibility.TOO_OLD -> WritePolicy.DISABLED
            tested -> WritePolicy.SEND
            version != null && version >= SUPPORTED_UNTIL -> WritePolicy.DISABLED
            else -> WritePolicy.QUEUE_PAUSED
        }
        return VersionAssessment(
            rawVersion = rawVersion,
            version = version,
            compatibility = compatibility,
            isTested = tested,
            inSupportedRange = inRange,
            writePolicy = writePolicy,
        )
    }
}
