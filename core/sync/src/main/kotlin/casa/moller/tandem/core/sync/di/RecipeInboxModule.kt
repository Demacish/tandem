package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.account.AccountRemovalHook
import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import casa.moller.tandem.core.sync.inbox.DefaultRecipeInboxRepository
import casa.moller.tandem.core.sync.inbox.RecipeInboxSchedule
import casa.moller.tandem.core.sync.work.RecipeInboxWork
import casa.moller.tandem.core.sync.work.WorkManagerRecipeInboxWork
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Fas 5 (rss lane, plan §7 #35): "Inkorg för recept" (docs/BYGGE.md "Inkorgen för recept"). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecipeInboxModule {
    @Binds
    abstract fun bindRecipeInboxRepository(impl: DefaultRecipeInboxRepository): RecipeInboxRepository

    @Binds
    abstract fun bindRecipeInboxWork(impl: WorkManagerRecipeInboxWork): RecipeInboxWork

    // The inbox's key, settings and posts go with the account that leaves the phone.
    @Binds
    @IntoSet
    abstract fun bindInboxRemoval(impl: RecipeInboxSchedule): AccountRemovalHook
}
