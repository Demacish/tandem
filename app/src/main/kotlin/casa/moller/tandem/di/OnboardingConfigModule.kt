package casa.moller.tandem.di

import casa.moller.tandem.BuildConfig
import casa.moller.tandem.feature.onboarding.OnboardingConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object OnboardingConfigModule {
    /**
     * DEFAULT_SERVER_URL is empty in every build unless the sideload build was made with the
     * Gradle property `tandem.defaultServerUrl` (see app/build.gradle.kts, plan 4.5).
     */
    @Provides
    fun provideOnboardingConfig(): OnboardingConfig = OnboardingConfig(defaultServerUrl = BuildConfig.DEFAULT_SERVER_URL)
}
