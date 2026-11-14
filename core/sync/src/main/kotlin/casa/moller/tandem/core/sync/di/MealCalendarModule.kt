package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.account.AccountRemovalHook
import casa.moller.tandem.core.data.mealplan.MealCalendarRepository
import casa.moller.tandem.core.sync.calendar.AndroidCalendarGateway
import casa.moller.tandem.core.sync.calendar.CalendarGateway
import casa.moller.tandem.core.sync.calendar.DefaultMealCalendarRepository
import casa.moller.tandem.core.sync.calendar.MealCalendarLinks
import casa.moller.tandem.core.sync.calendar.MealCalendarSchedule
import casa.moller.tandem.core.sync.work.MealCalendarWork
import casa.moller.tandem.core.sync.work.WorkManagerMealCalendarWork
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Fas 5 (kalender lane, plan §7 #36): "Matsedeln i kalendern" (docs/BYGGE.md "Matsedeln i kalendern"). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MealCalendarModule {
    @Binds
    abstract fun bindMealCalendarRepository(impl: DefaultMealCalendarRepository): MealCalendarRepository

    @Binds
    abstract fun bindCalendarGateway(impl: AndroidCalendarGateway): CalendarGateway

    @Binds
    abstract fun bindMealCalendarWork(impl: WorkManagerMealCalendarWork): MealCalendarWork

    // Fas 5 (integration-08): the link's `mealplan` token is revoked when its account leaves the phone.
    @Binds
    @IntoSet
    abstract fun bindLinkRemoval(impl: MealCalendarLinks): AccountRemovalHook

    // Fas 5 (integration-08): "Visa i telefonens kalender" goes off with the account it shows.
    @Binds
    @IntoSet
    abstract fun bindLocalCalendarRemoval(impl: MealCalendarSchedule): AccountRemovalHook
}
