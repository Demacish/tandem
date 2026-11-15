package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.assistant.AssistantActions
import casa.moller.tandem.core.data.assistant.AssistantLockCheck
import casa.moller.tandem.core.sync.assistant.DefaultAssistantActions
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Fas 5 (funktioner lane, plan §8.7): what the App Functions in :app call (docs/BYGGE.md "App
 * Functions").
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AssistantModule {
    @Binds
    abstract fun bindAssistantActions(impl: DefaultAssistantActions): AssistantActions

    companion object {
        /**
         * The app lock (Mer → Lås appen, las lane) is not on main yet: until it is, nothing locks
         * the app, so the assistant is never refused for a lock. The integration binds this to the
         * lock's `unlockedWithinTimeout()` (docs/BYGGE.md "App Functions").
         */
        @Provides
        fun provideAssistantLockCheck(): AssistantLockCheck = AssistantLockCheck { true }
    }
}
