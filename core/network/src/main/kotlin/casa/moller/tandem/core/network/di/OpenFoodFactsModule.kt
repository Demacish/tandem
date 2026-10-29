package casa.moller.tandem.core.network.di

import casa.moller.tandem.core.common.AppBuildInfo
import casa.moller.tandem.core.network.openfoodfacts.OpenFoodFactsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
    fun provideOpenFoodFactsClient(buildInfo: AppBuildInfo): OpenFoodFactsClient =
        OpenFoodFactsClient(OpenFoodFactsClient.httpClient(OpenFoodFactsClient.userAgent(buildInfo.versionName)))
}
