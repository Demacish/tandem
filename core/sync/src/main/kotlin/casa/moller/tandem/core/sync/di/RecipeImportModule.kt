package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.data.recipe.RecipeImportRepository
import casa.moller.tandem.core.sync.platform.AndroidImportDoneNotifier
import casa.moller.tandem.core.sync.platform.ImportDoneChannelInstaller
import casa.moller.tandem.core.sync.platform.ImportDoneNotifier
import casa.moller.tandem.core.sync.recipeimport.ImportKeepAlive
import casa.moller.tandem.core.sync.recipeimport.KeptImportCleaner
import casa.moller.tandem.core.sync.recipeimport.KeptImportStore
import casa.moller.tandem.core.sync.recipeimport.RemoteRecipeImportRepository
import casa.moller.tandem.core.sync.work.WorkManagerImportKeepAlive
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Fas 3 (import lane): the server-side recipe import. docs/BYGGE.md "Fas 3 – import". */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecipeImportModule {
    @Binds
    abstract fun bindRecipeImportRepository(impl: RemoteRecipeImportRepository): RecipeImportRepository

    @Binds
    abstract fun bindImportDoneNotifier(impl: AndroidImportDoneNotifier): ImportDoneNotifier

    @Binds
    abstract fun bindImportKeepAlive(impl: WorkManagerImportKeepAlive): ImportKeepAlive

    @Binds
    abstract fun bindKeptImportCleaner(impl: KeptImportStore): KeptImportCleaner

    /** The channel "Import klar" exists from the first start (plan 8.7), like the other channels. */
    @Binds
    @IntoSet
    abstract fun bindImportDoneChannelInstaller(impl: ImportDoneChannelInstaller): NotificationChannelInstaller
}
