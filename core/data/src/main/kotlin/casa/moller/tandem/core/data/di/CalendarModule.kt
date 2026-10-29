package casa.moller.tandem.core.data.di

import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.data.time.SystemCalendarDays
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fas S (hemma-ui lane): the device's day for expiry dates (core:data `CalendarDays`). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CalendarModule {
    @Binds
    abstract fun bindCalendarDays(impl: SystemCalendarDays): CalendarDays
}
