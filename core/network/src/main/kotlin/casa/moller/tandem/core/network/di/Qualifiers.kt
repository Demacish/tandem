package casa.moller.tandem.core.network.di

import javax.inject.Qualifier

/** The shared OkHttpClient for Tandoor: limits, UA, HTTPS only, no redirects, 30 s timeouts. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TandoorHttpClient

/**
 * The base client for images: own dispatcher and rate limit, no token. Per-account clients come
 * from `TandoorImageClientFactory`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TandoorImageHttpClient
