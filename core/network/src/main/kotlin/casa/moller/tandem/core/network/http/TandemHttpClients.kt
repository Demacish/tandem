package casa.moller.tandem.core.network.http

import casa.moller.tandem.core.network.image.ImageErrorBreakerInterceptor
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Builds Tandem's OkHttp clients (plan 4.1, 5.4, 5.5). Every client derived with `newBuilder()`
 * shares the dispatcher, the connection pool and the rate limiter, so the limits are global:
 *
 * - **At most 2 concurrent requests per host**: a small Tandoor (e.g. 1 gunicorn worker with 2
 *   threads) must stay responsive for the rest of the household.
 *   (OkHttp's dispatcher limits asynchronous calls; Retrofit's suspend functions and
 *   [await] use those.)
 * - **About 5 requests per second** via [TokenBucket].
 * - **Timeouts**: connect 10 s, read/write 30 s; the long-running client for AI and URL imports
 *   uses 120 s. The call timeout is a safety net above those.
 * - **No redirects**: Tandoor answers unknown `/api/` paths with a 302 to the login page, which
 *   OkHttp would otherwise turn into a 200 HTML page (FACTS g). The probe follows HTTPS redirects
 *   itself.
 * - **HTTPS only** ([HttpsOnlyInterceptor]) and a fixed User-Agent ([UserAgentInterceptor]).
 * - **Logging only in debug builds**, headers only (never bodies, which carry tokens and
 *   passwords), with `Authorization` and cookies redacted.
 */
object TandemHttpClients {
    const val CONNECT_TIMEOUT_SECONDS: Long = 10
    const val READ_WRITE_TIMEOUT_SECONDS: Long = 30
    const val CALL_TIMEOUT_SECONDS: Long = 60
    const val LONG_READ_WRITE_TIMEOUT_SECONDS: Long = 120
    const val LONG_CALL_TIMEOUT_SECONDS: Long = 150
    const val MAX_REQUESTS_PER_HOST: Int = 2

    /** Image requests per host at once, on their own dispatcher ([images]). */
    const val IMAGE_MAX_REQUESTS_PER_HOST: Int = 3

    /** Image requests at once over all hosts. */
    const val IMAGE_MAX_REQUESTS: Int = 6

    /** Image requests per second (own token bucket; burst of the same size). */
    const val IMAGE_PERMITS_PER_SECOND: Int = 8

    /**
     * @param userAgent `AppBuildInfo.userAgent` from `:app` (never this module's BuildConfig).
     * @param debugLogging true only for debug builds.
     */
    fun create(
        userAgent: String,
        debugLogging: Boolean,
        rateLimiter: TokenBucket = TokenBucket(),
        logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(HttpsOnlyInterceptor())
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(RateLimitInterceptor(rateLimiter))
        if (debugLogging) {
            // A network interceptor runs after the application interceptors (including the
            // per-server AuthInterceptor), so it sees the final, redacted headers.
            builder.addNetworkInterceptor(
                HttpLoggingInterceptor(logger).apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader(HEADER_AUTHORIZATION)
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                },
            )
        }
        return builder.build()
    }

    /** Same pipeline and limits as [base], with the 120 s timeouts for AI and URL imports. */
    fun longRunning(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .readTimeout(LONG_READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(LONG_READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(LONG_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * The base client for images (plan 3.4, 4.1): the same pipeline as [base] (HTTPS only,
     * User-Agent, timeouts, connection pool, debug logging), but with
     * - its own [Dispatcher] ([IMAGE_MAX_REQUESTS_PER_HOST], [IMAGE_MAX_REQUESTS]) and its own
     *   [TokenBucket] ([IMAGE_PERMITS_PER_SECOND]) instead of the API's, so images never use up
     *   the two API slots or the API's rate budget;
     * - an [ImageErrorBreakerInterceptor] in front of the rate limit;
     * - redirects followed (external media storage), but never from https to http.
     *
     * Per-account clients with the token come from `TandoorImageClientFactory`.
     */
    fun images(
        base: OkHttpClient,
        rateLimiter: TokenBucket = TokenBucket(IMAGE_PERMITS_PER_SECOND, IMAGE_PERMITS_PER_SECOND),
        breaker: ImageErrorBreakerInterceptor = ImageErrorBreakerInterceptor(),
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = IMAGE_MAX_REQUESTS
            maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST
        }
        val builder = base.newBuilder()
            .dispatcher(dispatcher)
            .followRedirects(true)
            .followSslRedirects(false)
        builder.interceptors().removeAll { it is RateLimitInterceptor }
        builder.addInterceptor(breaker)
        builder.addInterceptor(RateLimitInterceptor(rateLimiter))
        return builder.build()
    }
}
