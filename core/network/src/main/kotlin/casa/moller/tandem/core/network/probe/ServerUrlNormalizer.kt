package casa.moller.tandem.core.network.probe

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns what the user typed or pasted into the server's base URL: `https://host[:port]/[sub/path/]`
 * with a trailing slash, no query, fragment or credentials.
 *
 * - No scheme: `https://` is assumed. `http://` is refused ([InvalidUrlReason.CLEARTEXT_NOT_ALLOWED]).
 * - Host names are lower-cased and IDNs converted to punycode (by OkHttp's [HttpUrl]).
 * - Pasted deep links are cut before the first known Tandoor route segment, so
 *   `https://x/tandoor/recipe/12/` and `https://x/accounts/login/?next=/` give the base.
 * - Everything before that is kept as the sub-path of a sub-path install, e.g. `/recipes/`
 *   (the example in Tandoor's own docs for `SCRIPT_NAME`).
 * - Because a sub-path may be named like a route, [candidates] also returns the uncut path, and
 *   [ServerProbe] tries both.
 *
 * Nothing in here knows any particular server (plan 4.5).
 */
object ServerUrlNormalizer {

    /**
     * First path segments of Tandoor 2.6.13's routes. A segment in this set ends the base path.
     *
     * Checked against 2.6.13's Django URLconf (cookbook/urls.py, recipes/urls.py) and the Vue
     * router (vue3/src/apps/tandoor/main.ts). Words that are no route there (`recipes`, `start`,
     * `new`, ...) are deliberately missing, and so are `test` and `system`: they are plausible
     * names for a sub-path install. A sub-path named like a route still works, because the
     * probe also tries the uncut path ([candidates]).
     */
    internal val TANDOOR_ROUTE_SEGMENTS: Set<String> = setOf(
        // Django
        "admin", "accounts", "_allauth", "i18n", "jsi18n", "media", "static", "setup", "no-group",
        "invite", "plugin", "abuse", "export-file", "view-recipe-pdf", "view", "api", "api-auth",
        "api-token-auth", "telegram", "docs", "openapi", "offline", "service-worker.js",
        "manifest.json",
        // Vue router
        "search", "welcome", "household-setup", "help", "settings", "advanced-search", "shopping",
        "mealplan", "books", "book", "recipe", "list", "edit", "delete", "database", "inventory",
        "ingredient-editor", "property-editor", "pantry", "space-setup",
    )

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    /** The base URL for [input]: the path is cut before the first Tandoor route segment. */
    fun normalize(input: String): Outcome<String> = parse(input).map { baseOf(it).toString() }

    /**
     * Base URLs to probe for [input], best first, without duplicates: the whole typed path (a
     * sub-path install may be named like a route, e.g. `/books/`), then the path cut before the
     * first Tandoor route segment (a pasted deep link). Usually both are the same.
     */
    fun candidates(input: String): Outcome<List<String>> = parse(input).map { url ->
        listOf(withPath(url, url.encodedPathSegments.filter { it.isNotEmpty() }), baseOf(url))
            .map(HttpUrl::toString)
            .distinct()
    }

    private fun parse(input: String): Outcome<HttpUrl> {
        val text = input.trim()
        if (text.isEmpty()) return invalid(InvalidUrlReason.EMPTY)
        if (text.any { it.isWhitespace() }) return invalid(InvalidUrlReason.MALFORMED)

        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        val withScheme = when (scheme) {
            null -> "https://$text"
            "https" -> text
            "http" -> return invalid(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED)
            else -> return invalid(InvalidUrlReason.UNSUPPORTED_SCHEME)
        }
        val url = withScheme.toHttpUrlOrNull() ?: return invalid(InvalidUrlReason.MALFORMED)
        if (url.host.isEmpty()) return invalid(InvalidUrlReason.MALFORMED)
        return Outcome.Success(url)
    }

    /** The base URL of [url]: same origin, path up to the first Tandoor route segment. */
    internal fun baseOf(url: HttpUrl): HttpUrl = withPath(
        url,
        url.encodedPathSegments
            .filter { it.isNotEmpty() }
            .takeWhile { it.lowercase() !in TANDOOR_ROUTE_SEGMENTS },
    )

    /** [url] with only [segments] as its path (trailing slash), no credentials, query or fragment. */
    private fun withPath(url: HttpUrl, segments: List<String>): HttpUrl {
        val path = if (segments.isEmpty()) "/" else segments.joinToString(separator = "/", prefix = "/", postfix = "/")
        return url.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .encodedPath(path)
            .build()
    }

    private fun <T> invalid(reason: InvalidUrlReason): Outcome<T> = Outcome.Failure(TandemError.InvalidUrl(reason))
}
