package casa.moller.tandem.core.network.di

import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.network.http.TandemHttpClients
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Provides the shared OkHttp clients. [AppBuildInfo] comes from `:app`, so the User-Agent carries
 * the app's version and debug logging follows the app's build type.
 * `TandoorApiFactory`, `ServerProbe` and `TandoorAuthenticator` have `@Inject` constructors.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @TandoorHttpClient
    fun provideTandoorHttpClient(buildInfo: AppBuildInfo): OkHttpClient =
        TandemHttpClients.create(userAgent = buildInfo.userAgent, debugLogging = buildInfo.isDebug)

    @Provides
    @Singleton
    @TandoorLongRunningHttpClient
    fun provideTandoorLongRunningHttpClient(@TandoorHttpClient base: OkHttpClient): OkHttpClient = TandemHttpClients.longRunning(base)
}
