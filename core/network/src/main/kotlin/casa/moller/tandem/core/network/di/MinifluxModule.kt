package casa.moller.tandem.core.network.di

import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.network.miniflux.MinifluxClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fas 5 (rss lane, plan §7 #35): the recipe inbox's Miniflux client with its OWN OkHttp client
 * (never the [TandoorHttpClient] or one derived from it), so no Tandoor interceptor, token or cookie
 * can reach the user's Miniflux server and its requests never use Tandoor's slots or rate budget.
 * The server address is the user's own (Mer → Inkorg för recept), never built in.
 */
@Module
@InstallIn(SingletonComponent::class)
object MinifluxModule {
    @Provides
    @Singleton
    fun provideMinifluxClient(buildInfo: AppBuildInfo): MinifluxClient =
        MinifluxClient(MinifluxClient.httpClient(MinifluxClient.userAgent(buildInfo.versionName)))
}
