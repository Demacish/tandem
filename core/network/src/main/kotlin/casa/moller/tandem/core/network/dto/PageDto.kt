package casa.moller.tandem.core.network.dto

import kotlinx.serialization.Serializable

/**
 * A DRF page (plan 3.4, FACTS "Fas 1 c"): `{count, next, previous, results}`, plus `timestamp`
 * on the small tables (not on `recipe/`).
 *
 * [next] and [previous] are absolute URLs that the server builds from the request's `Host` and
 * `X-Forwarded-Proto`, so behind a proxy they may name another host or `http://`. They are
 * never requested as they are: [casa.moller.tandem.core.network.paging.Paginator] only reads
 * the `page` number from [next] and asks the account's own base URL for it.
 */
@Serializable
data class PageDto<T>(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    /** Server time of the response; small tables only (not `recipe/`). Kept raw. */
    val timestamp: String? = null,
    val results: List<T> = emptyList(),
)
