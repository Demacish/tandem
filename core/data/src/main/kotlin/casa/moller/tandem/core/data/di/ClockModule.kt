package casa.moller.tandem.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlin.time.Clock

/**
 * The wall clock for Phase 1b (timers, cook log, diagnostics): inject `kotlin.time.Clock`
 * instead of calling `Clock.System`, so tests use core:testing's `TestClock`. Timers store
 * wall-clock end times (see `CookTimer`); a monotonic clock, where needed, stays inside core:timer.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @Provides
    fun provideClock(): Clock = Clock.System
}
