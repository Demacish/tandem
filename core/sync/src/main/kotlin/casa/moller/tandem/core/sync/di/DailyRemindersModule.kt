package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.sync.platform.AndroidMealReminderNotifier
import casa.moller.tandem.core.sync.platform.MealReminderNotifier
import casa.moller.tandem.core.sync.reminder.DailyReminderScheduler
import casa.moller.tandem.core.sync.reminder.WorkManagerDailyReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Fas 5 (planera lane): the daily reminders at a chosen wall-clock time (the morning pantry check
 * and the evening "Tina ut kycklingen", plan §8.3, §8.8; docs/BYGGE.md "Påminnelser kvällen innan
 * och på morgonen"). Its own module, so the sync's module stays as it was.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DailyRemindersModule {
    @Binds
    abstract fun bindDailyReminderScheduler(impl: WorkManagerDailyReminderScheduler): DailyReminderScheduler

    @Binds
    abstract fun bindMealReminderNotifier(impl: AndroidMealReminderNotifier): MealReminderNotifier
}
