package casa.moller.tandem.core.ai.di

import casa.moller.tandem.core.ai.ClaudeFridgeVisionClient
import casa.moller.tandem.core.ai.DefaultFridgeVision
import casa.moller.tandem.core.ai.FridgeVisionClient
import casa.moller.tandem.core.ai.GeminiFridgeVisionClient
import casa.moller.tandem.core.ai.OpenAiFridgeVisionClient
import casa.moller.tandem.core.data.ai.FridgeVision
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Fas S: core:ai binds [FridgeVision] and one client per provider (decision B22). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiModule {
    @Binds
    abstract fun bindFridgeVision(impl: DefaultFridgeVision): FridgeVision

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
