package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.mealplan.MealCalendarRepository
import casa.moller.tandem.core.sync.calendar.AndroidCalendarGateway
import casa.moller.tandem.core.sync.calendar.CalendarGateway
import casa.moller.tandem.core.sync.calendar.DefaultMealCalendarRepository
import casa.moller.tandem.core.sync.work.MealCalendarWork
import casa.moller.tandem.core.sync.work.WorkManagerMealCalendarWork
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

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
}
