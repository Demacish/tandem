package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.sync.platform.AndroidPantryExpiryNotifier
import casa.moller.tandem.core.sync.platform.MealRemindersChannelInstaller
import casa.moller.tandem.core.sync.platform.PantryExpiryNotifier
import casa.moller.tandem.core.sync.platform.SystemWallClock
import casa.moller.tandem.core.sync.platform.WallClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Fas 4 (skafferinotis lane): the optional morning notification "Grädde går ut i morgon – 3 recept"
 * (plan §8.8 "Använd upp", §8.7 "Måltidspåminnelser"; docs/BYGGE.md "Notis när något går ut"). Its
 * own module, so the sync's module stays as it was.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PantryExpiryModule {
    @Binds
    abstract fun bindPantryExpiryNotifier(impl: AndroidPantryExpiryNotifier): PantryExpiryNotifier

    @Binds
    abstract fun bindWallClock(impl: SystemWallClock): WallClock

    /** The channel "Måltidspåminnelser" exists from the first start (plan 8.7), like the other channels. */
    @Binds
    @IntoSet
    abstract fun bindMealRemindersChannelInstaller(impl: MealRemindersChannelInstaller): NotificationChannelInstaller
}
