package casa.moller.tandem.di

import casa.moller.tandem.core.network.di.OpenFoodFactsBaseUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Fas S (integration): ONLY in the `minified` build type (the release smoke test's package
 * casa.moller.tandem.minified, never shipped): Open Food Facts is the smoke test's fake on
 * localhost, served with the test CA this build type alone trusts (docs/BYGGE.md
 * "Release-röktest"). The real release build has no such binding and calls Open Food Facts.
 */
@Module
@InstallIn(SingletonComponent::class)
object SmokeEndpointsModule {
    /** Must match `FakeOpenFoodFacts.PORT` in `:smoke`. */
    private const val FAKE_OPEN_FOOD_FACTS = "https://localhost:47654/"

    @Provides
    @OpenFoodFactsBaseUrl
    fun openFoodFactsBaseUrl(): String = FAKE_OPEN_FOOD_FACTS
}
