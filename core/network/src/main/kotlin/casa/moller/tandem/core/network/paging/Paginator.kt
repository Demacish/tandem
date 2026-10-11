package casa.moller.tandem.core.network.paging

import casa.moller.tandem.core.network.dto.PageDto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Everything a paginated list returned.
 *
 * @property items all results, in server order.
 * @property count the server's `count` from the first page.
 * @property complete true when the last page was reached. False when [Paginator] stopped early
 *   ([Paginator.DEFAULT_MAX_PAGES], a `next` link without a usable page number, a page that did
 *   not advance). A sync must NOT treat missing items as deleted when this is false.
 * @property pages how many pages were requested.
 * @property timestamp the first page's `timestamp` (small tables only).
 */
data class PagedResult<T>(val items: List<T>, val count: Int, val complete: Boolean, val pages: Int, val timestamp: String?)

/**
 * Reads every page of a DRF list (plan 3.4, 4.1) without ever following a `next` URL as it is.
 *
 * Tandoor builds `next` from the request's `Host` and `X-Forwarded-Proto` (FACTS "Fas 1 c"):
 * behind a proxy it can name `http://`, an internal host, or (from a hostile or broken server)
 * any other origin. So only the `page` number is read from `next`, and the next page is requested
 * through the same service call, i.e. against the account's own base URL with the account's own
 * filters and page size. Nothing else from the link is used: its host, path and other query
 * parameters are ignored, and the token can never be sent anywhere else.
 *
 * Safety limits: at most [maxPages] requests; the page number must increase, so a server that
 * keeps answering with the same `next` cannot loop; the coroutine's cancellation is checked
 * before every request (and Retrofit cancels a running one). The shared client's limits (2
 * concurrent requests per host, ~5 per second) apply to every page.
 */
object Paginator {
    /** With 100-200 items per page this is 10 000 to 20 000 items. */
    const val DEFAULT_MAX_PAGES: Int = 100

    /**
     * @param fetchPage requests the 1-based page; page 1 may be sent without a `page` parameter.
     * @throws Exception whatever [fetchPage] throws (wrap the call in `apiCall`); cancellation
     *   is rethrown.
     */
    suspend fun <T> fetchAll(maxPages: Int = DEFAULT_MAX_PAGES, fetchPage: suspend (page: Int) -> PageDto<T>): PagedResult<T> {
        require(maxPages >= 1) { "maxPages must be >= 1" }
        val items = mutableListOf<T>()
        var page = 1
        var pages = 0
        var count = 0
        var timestamp: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = fetchPage(page)
            pages++
            if (pages == 1) {
                count = result.count
                timestamp = result.timestamp
            }
            items += result.results
            val next = result.next ?: return PagedResult(items, count, complete = true, pages = pages, timestamp = timestamp)
            val nextPage = pageNumberOf(next)
            if (nextPage == null || nextPage <= page || pages >= maxPages) {
                return PagedResult(items, count, complete = false, pages = pages, timestamp = timestamp)
            }
            page = nextPage
        }
    }

    /**
     * The `page` query parameter of a DRF `next` link (absolute or relative), or null when it has
     * none or it is not a positive number. DRF's link to page 1 has no `page` at all, which is
     * never a valid NEXT page.
     */
    fun pageNumberOf(link: String): Int? {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return null
        val url = trimmed.toHttpUrlOrNull() ?: RELATIVE_BASE.resolve(trimmed) ?: return null
        val values = url.queryParameterValues("page")
        if (values.size != 1) return null
        return values.single()?.trim()?.toIntOrNull()?.takeIf { it >= 1 }
    }

    /** Only used to parse relative links; never requested. */
    private val RELATIVE_BASE = "https://relative.invalid/".toHttpUrlOrNull()!!
}
