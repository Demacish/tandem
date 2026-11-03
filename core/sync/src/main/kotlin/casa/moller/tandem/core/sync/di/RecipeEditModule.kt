package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.recipe.RecipeEditRepository
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.recipeedit.OfflineFirstRecipeEditRepository
import casa.moller.tandem.core.sync.recipeedit.RecipeEditOutboxHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Fas 3 (redigerare lane): the recipe editor's repository and the sender of the `recipe_edit`
 * rows (CREATE, PATCH, DELETE and `RECIPE_IMAGE`). docs/BYGGE.md "Receptredigeraren".
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecipeEditModule {
    @Binds
    abstract fun bindRecipeEditRepository(impl: OfflineFirstRecipeEditRepository): RecipeEditRepository

    @Binds
    @IntoMap
    @StringKey(OutboxEntities.RECIPE_EDIT)
    abstract fun bindRecipeEditHandler(impl: RecipeEditOutboxHandler): OutboxHandler
}
