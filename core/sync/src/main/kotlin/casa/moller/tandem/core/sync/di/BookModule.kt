package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.book.RecipeBookRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.sync.book.BookNames
import casa.moller.tandem.core.sync.book.OfflineFirstRecipeBookRepository
import casa.moller.tandem.core.sync.book.RecipeBookEntryOutboxHandler
import casa.moller.tandem.core.sync.book.RecipeBookOutboxHandler
import casa.moller.tandem.core.sync.book.RecipeKeywordsOutboxHandler
import casa.moller.tandem.core.sync.book.ResourceBookNames
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.permissions.DefaultSpacePermissionsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Phase 2 (böcker lane): books, favourites and "Vill laga" (replaces the app's
 * `TemporaryFas2BookModule`) and the guest mode's role detection (`SpacePermissionsRepository`,
 * #42; taken out of `TemporaryFas2AccountModule`), with the outbox senders of `recipe_book`,
 * `recipe_book_entry` and the recipe's `RECIPE_KEYWORDS` (docs/BYGGE.md "Böcker, favoriter och
 * Vill laga").
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BookModule {
    @Binds
    abstract fun bindRecipeBookRepository(impl: OfflineFirstRecipeBookRepository): RecipeBookRepository

    @Binds
    abstract fun bindSpacePermissionsRepository(impl: DefaultSpacePermissionsRepository): SpacePermissionsRepository

    @Binds
    abstract fun bindBookNames(impl: ResourceBookNames): BookNames

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.RECIPE_BOOK)
    abstract fun bindRecipeBookHandler(impl: RecipeBookOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.RECIPE_BOOK_ENTRY)
    abstract fun bindRecipeBookEntryHandler(impl: RecipeBookEntryOutboxHandler): OutboxHandler

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.RECIPE)
    abstract fun bindRecipeKeywordsHandler(impl: RecipeKeywordsOutboxHandler): OutboxHandler
}
