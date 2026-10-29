package casa.moller.tandem.core.ai.di

import casa.moller.tandem.core.ai.ClaudeFridgeVisionClient
import casa.moller.tandem.core.ai.DefaultAiSettingsRepository
import casa.moller.tandem.core.ai.DefaultFridgeVision
import casa.moller.tandem.core.ai.FridgeVisionClient
import casa.moller.tandem.core.ai.GeminiFridgeVisionClient
import casa.moller.tandem.core.ai.OpenAiFridgeVisionClient
import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.data.ai.FridgeVision
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Fas S (B22): core:ai binds [FridgeVision], Settings → AI ([AiSettingsRepository]) and one client per
 * provider. The key store itself (`AiKeyStore`) is bound by core:datastore.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiModule {
    @Binds
    abstract fun bindFridgeVision(impl: DefaultFridgeVision): FridgeVision

    @Binds
    abstract fun bindAiSettings(impl: DefaultAiSettingsRepository): AiSettingsRepository

    @Binds
    @IntoSet
    abstract fun bindClaude(impl: ClaudeFridgeVisionClient): FridgeVisionClient

    @Binds
    @IntoSet
    abstract fun bindGemini(impl: GeminiFridgeVisionClient): FridgeVisionClient

    @Binds
    @IntoSet
    abstract fun bindOpenAi(impl: OpenAiFridgeVisionClient): FridgeVisionClient
}
