package casa.moller.tandem.core.network.image

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns the image values Tandoor sends into URLs the image client can load (FACTS "Fas 1 b, e",
 * "konsekvenser" 2):
 *
 * - `recipe/flat` and the image upload give a RELATIVE path (`/media/recipes/<uuid>_<id>.png`):
 *   it is resolved against the account's base URL. For a sub-path install the path either
 *   already starts with the base path (`/tandoor/media/...`) or is placed below it.
 * - Lists, details, meal plans and books give an ABSOLUTE URL built from the proxy's `Host` and
 *   `X-Forwarded-Proto`. An `https` URL is used as it is (it may also be an external storage
 *   host; the token is never sent there, see [TandoorImageClientFactory]). An `http` URL (a
 *   proxy without `X-Forwarded-Proto`) can never be loaded (no cleartext, plan 5.5), so its
 *   `media/...` part is moved onto the base URL; without a `/media/` segment it is dropped.
 * - Anything else (other schemes, unparseable values, blank) gives null.
 *
 * File names contain a UUID that changes on every upload, so the result can be cached for a long
 * time and used as a cache key.
 */
object MediaUrls {
    private const val MEDIA_SEGMENT = "/media/"

    /** @param baseUrl the account's normalized base URL (trailing slash). */
    fun resolve(baseUrl: String, image: String?): String? {
        val raw = image?.trim()
        if (raw.isNullOrEmpty()) return null
        val base = baseUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        val absolute = raw.toHttpUrlOrNull()
        return when {
            absolute == null && SCHEME.containsMatchIn(raw) -> null
            absolute == null -> resolveRelative(base, raw)
            absolute.isHttps -> absolute.toString()
            else -> moveMediaOntoBase(base, absolute)
        }
    }

    private fun resolveRelative(base: HttpUrl, raw: String): String? {
        val basePath = base.encodedPath
        val resolved = when {
            raw.startsWith("//") -> null
            raw.startsWith(basePath) -> base.resolve(raw)
            raw.startsWith("/") -> base.resolve(raw.removePrefix("/"))
            else -> base.resolve(raw)
        }
        return resolved?.takeIf { it.isHttps && it.host == base.host && it.port == base.port }?.toString()
    }

    private fun moveMediaOntoBase(base: HttpUrl, url: HttpUrl): String? {
        val path = url.encodedPath
        val index = path.indexOf(MEDIA_SEGMENT)
        if (index < 0) return null
        val relative = path.substring(index + 1) + (url.encodedQuery?.let { "?$it" } ?: "")
        return base.resolve(relative)?.toString()
    }

    /** `scheme:` at the start, e.g. `data:` or `content:` (which this client never loads). */
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
}
