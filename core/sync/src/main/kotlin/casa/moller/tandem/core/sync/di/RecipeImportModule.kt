package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.recipe.RecipeImportRepository
import casa.moller.tandem.core.sync.recipeimport.RemoteRecipeImportRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fas 3 (import lane): the server-side recipe import. docs/BYGGE.md "Fas 3 – import". */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecipeImportModule {
    @Binds
    abstract fun bindRecipeImportRepository(impl: RemoteRecipeImportRepository): RecipeImportRepository
}
