package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.assistant.AssistantActions
import casa.moller.tandem.core.data.assistant.AssistantLockCheck
import casa.moller.tandem.core.data.security.AppLock
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
         * Mer → Lås appen (las lane): the assistant gets an answer while the lock is off, or within
         * the lock's timeout after the user unlocked; asked again at every request (docs/BYGGE.md
         * "App Functions", "Lås appen").
         */
        @Provides
        fun provideAssistantLockCheck(lock: AppLock): AssistantLockCheck = AssistantLockCheck { lock.unlockedWithinTimeout() }
    }
}
