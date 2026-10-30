package casa.moller.tandem.core.network.di

import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.network.openfoodfacts.OpenFoodFactsClient
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Fas S (integration): another Open Food Facts address. Bound ONLY by `:app`'s `minified` build
 * type (the release smoke test's own package, `app/src/minified/kotlin`), where it points at the
 * smoke test's fake on `localhost`; every other build (debug, release, play) has no binding and
 * talks to [OpenFoodFactsClient.DEFAULT_BASE_URL].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenFoodFactsBaseUrl

/**
 * Fas S (hemma-data lane): the Open Food Facts client with its OWN OkHttp client (never the
 * [TandoorHttpClient] or one derived from it), so no Tandoor interceptor, token or cookie can reach
 * it (plan §8.8). The User-Agent is `Tandem/<versionName> (+https://github.com/Demacish/tandem)`.
 */
@Module
@InstallIn(SingletonComponent::class)
object OpenFoodFactsModule {
    @Provides
    @Singleton
    fun provideOpenFoodFactsClient(buildInfo: AppBuildInfo, @OpenFoodFactsBaseUrl baseUrl: Optional<String>): OpenFoodFactsClient =
        OpenFoodFactsClient(
            OpenFoodFactsClient.httpClient(OpenFoodFactsClient.userAgent(buildInfo.versionName)),
            baseUrl.orElse(OpenFoodFactsClient.DEFAULT_BASE_URL),
        )
}

/** Declares [OpenFoodFactsBaseUrl] optional: absent in every build but the smoke test's. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OpenFoodFactsBaseUrlModule {
    @BindsOptionalOf
    @OpenFoodFactsBaseUrl
    abstract fun baseUrl(): String
}
