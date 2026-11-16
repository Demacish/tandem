package casa.moller.tandem.contract

/**
 * The server's release, read from `GET /api/server-settings/current/` `version` (the image tag
 * `latest` names none), for expectations that differ between releases (komplettering-09:
 * Tandoor 2.7.3, api-schema/fixtures/2.7.3/FACTS.md).
 */
internal object TandoorRelease {
    private val NUMBERS = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")

    /** `2.7.3` -> [2, 7, 3]; null for a development build or anything unreadable. */
    fun parse(version: String?): List<Int>? = NUMBERS.find(version.orEmpty())?.groupValues?.drop(1)?.map { it.toInt() }

    /** True when [version] is [minimum] or later; false when it cannot be read. */
    fun atLeast(version: String?, vararg minimum: Int): Boolean {
        val parts = parse(version) ?: return false
        val first = minimum.indices.firstOrNull { parts[it] != minimum[it] } ?: return true
        return parts[first] > minimum[first]
    }
}
