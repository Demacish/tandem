package casa.moller.tandem.core.network.probe

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.di.TandoorHttpClient
import casa.moller.tandem.core.network.dto.ServerSettingsDto
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.http.await
import casa.moller.tandem.core.network.json.TandoorJson
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A server that answered the probe like Tandoor. Input for login. */
data class TandoorServer(
    /** Normalized base URL with trailing slash, e.g. `https://example.com/tandoor/`. */
    val baseUrl: String,
    /** `version` exactly as the server reported it. */
    val version: String,
    val compatibility: Compatibility,
    val settings: ServerSettingsDto,
)

/**
 * Finds out whether an address is a Tandoor server and which version it runs (plan 3.6, 4.2):
 * `GET <base>api/server-settings/current/`, which needs no login (FACTS a). The probe never sends
 * a token, follows HTTPS redirects itself (the shared client does not), and uses at most
 * [MAX_REQUESTS] requests in total (plan 4.1: capability probes are capped at 3).
 *
 * When the typed path contains a Tandoor route segment, there are two candidate bases
 * ([ServerUrlNormalizer.candidates]): the whole typed path first (a sub-path install named like
 * a route, e.g. `/books/`), then the path cut before the route (a pasted deep link). The next
 * candidate is only tried when the previous one answered "not Tandoor" (HTML, 404, an
 * unrelated redirect, ...); DNS, TLS, timeouts, blocks, throttling and 5xx end the probe.
 */
class ServerProbe internal constructor(
    private val client: OkHttpClient,
    private val classifier: ErrorClassifier,
    /** Where the response body is read: blocking socket I/O, never the caller's (main) thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(@TandoorHttpClient client: OkHttpClient) : this(client, ErrorClassifier.Default)

    suspend fun probe(input: String): Outcome<TandoorServer> {
        val candidates = when (val c = ServerUrlNormalizer.candidates(input)) {
            is Outcome.Failure -> return Outcome.Failure(c.error)
            is Outcome.Success -> c.value
        }

        var requests = 0
        var result: Outcome<TandoorServer> = Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.TOO_MANY_REDIRECTS))
        for ((index, candidate) in candidates.withIndex()) {
            if (requests >= MAX_REQUESTS) break
            var base = candidate.toHttpUrl()
            var done = false
            while (!done) {
                if (requests >= MAX_REQUESTS) {
                    return Outcome.Failure(TandemError.NotTandoor(NotTandoorReason.TOO_MANY_REDIRECTS))
                }
                requests++
                when (val step = request(base)) {
                    is Step.Follow -> base = step.newBase

                    is Step.Done -> {
                        result = step.outcome
                        val tryNext = index < candidates.lastIndex &&
                            (step.outcome as? Outcome.Failure)?.error is TandemError.NotTandoor
                        if (!tryNext) return step.outcome
                        done = true
                    }
                }
            }
        }
        return result
    }

    /** One request to the settings endpoint below [base]. */
    private suspend fun request(base: HttpUrl): Step {
        val url = base.resolve(SETTINGS_PATH)!!
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return try {
            // await() returns as soon as the headers are there; reading the body is blocking
            // socket I/O. The probe is called from viewModelScope (the main thread), where Android
            // throws NetworkOnMainThreadException unless the body already sits in OkHttp's buffer.
            // It usually does with a real server (headers and a small body in one TLS record), so
            // reading it on the main thread only failed sometimes, as "not JSON" (runCatching in
            // evaluate). The release smoke test's fake server always sends them separately.
            client.newCall(request).await().use { response -> withContext(ioDispatcher) { evaluate(base, url, response) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(classifier.classify(e))
        }
    }

    private sealed interface Step {
        data class Done(val outcome: Outcome<TandoorServer>) : Step
        data class Follow(val newBase: HttpUrl) : Step
    }

    private fun evaluate(base: HttpUrl, url: HttpUrl, response: okhttp3.Response): Step {
        val code = response.code
        if (code in REDIRECT_CODES) {
            val target = response.header("Location")?.let { url.resolve(it) }
                ?: return fail(TandemError.NotTandoor(NotTandoorReason.REDIRECT, code))
            if (!target.isHttps) return fail(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED))
            val targetPath = target.encodedPath
            // Only a redirect of the probe path itself moves the base (other domain or sub-path).
            // Anything else, e.g. Tandoor's 302 to /accounts/login/ for an unknown path or an SSO
            // portal, means this is not the API.
            if (!targetPath.endsWith("/$SETTINGS_PATH")) {
                return fail(classifier.classifyHttp(code, response.headers, null, null)!!)
            }
            val newBase = target.newBuilder()
                .encodedPath(targetPath.removeSuffix(SETTINGS_PATH))
                .query(null)
                .fragment(null)
                .build()
            return Step.Follow(newBase)
        }

        val body = runCatching { response.body.string() }.getOrNull()
        val error = classifier.classifyHttp(code, response.headers, response.body.contentType(), body)
        if (error != null) return fail(probeError(error))
        if (code != 200) return fail(TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, code))

        val json = ErrorClassifier.parseJsonOrNull(body) as? JsonObject
            ?: return fail(TandemError.NotTandoor(NotTandoorReason.NOT_JSON, code))
        val settings = try {
            TandoorJson.decodeFromJsonElement(ServerSettingsDto.serializer(), json)
        } catch (e: SerializationException) {
            return fail(TandemError.NotTandoor(NotTandoorReason.NOT_JSON, code))
        } catch (e: IllegalArgumentException) {
            return fail(TandemError.NotTandoor(NotTandoorReason.NOT_JSON, code))
        }
        val version = settings.version?.trim()
        if (version.isNullOrEmpty()) return fail(TandemError.NotTandoor(NotTandoorReason.MISSING_VERSION, code))

        return Step.Done(
            Outcome.Success(
                TandoorServer(
                    baseUrl = base.toString(),
                    version = version,
                    compatibility = TandoorCompatibility.classify(version),
                    settings = settings,
                ),
            ),
        )
    }

    /**
     * On the public settings endpoint, 400/404 and other unexpected answers mean "not Tandoor at
     * this address". Blocked, Unauthorized (an SSO proxy), throttling and 5xx keep their meaning.
     */
    private fun probeError(error: TandemError): TandemError = when (error) {
        is TandemError.NotFound, is TandemError.BadRequest, is TandemError.Unknown ->
            TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS)

        else -> error
    }

    private fun fail(error: TandemError): Step = Step.Done(Outcome.Failure(error))

    companion object {
        /** Plan 4.1: at most 3 probe requests per attempt, across candidates and redirects. */
        const val MAX_REQUESTS: Int = 3
        internal const val SETTINGS_PATH = "api/server-settings/current/"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
