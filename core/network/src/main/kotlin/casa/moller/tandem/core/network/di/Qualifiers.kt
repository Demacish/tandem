package casa.moller.tandem.core.network.di

import javax.inject.Qualifier

/** The shared OkHttpClient for Tandoor: limits, UA, HTTPS only, no redirects, 30 s timeouts. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TandoorHttpClient

/** Same pipeline with 120 s timeouts, for AI and URL imports (plan 5.4). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TandoorLongRunningHttpClient

/**
 * The base client for images: own dispatcher and rate limit, no token. Per-account clients come
 * from `TandoorImageClientFactory`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TandoorImageHttpClient
