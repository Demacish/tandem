package casa.moller.tandem.core.timer.di

import android.content.Context
import androidx.datastore.dataStoreFile
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.timer.alarm.AlarmScheduler
import casa.moller.tandem.core.timer.alarm.AndroidAlarmScheduler
import casa.moller.tandem.core.timer.engine.DefaultTimerController
import casa.moller.tandem.core.timer.engine.MonotonicClock
import casa.moller.tandem.core.timer.engine.SystemMonotonicClock
import casa.moller.tandem.core.timer.engine.TimerEngineConfig
import casa.moller.tandem.core.timer.notification.AndroidTimerNotifier
import casa.moller.tandem.core.timer.notification.TimerChannelInstaller
import casa.moller.tandem.core.timer.notification.TimerNotifier
import casa.moller.tandem.core.timer.platform.AndroidTimerSystem
import casa.moller.tandem.core.timer.platform.TimerSystem
import casa.moller.tandem.core.timer.ring.AndroidRinger
import casa.moller.tandem.core.timer.ring.Ringer
import casa.moller.tandem.core.timer.store.DataStoreTimerStore
import casa.moller.tandem.core.timer.store.TimerStore
import casa.moller.tandem.core.timer.store.timerDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * The real timers (Phase 1b timer lane): binds core:data's [TimerController] to
 * [DefaultTimerController] and its Android parts. Replaces the grund lane's
 * `TemporaryFas1bTimerModule` in the app.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TimerModule {
    @Binds
    abstract fun bindTimerController(impl: DefaultTimerController): TimerController

    @Binds
    abstract fun bindAlarmScheduler(impl: AndroidAlarmScheduler): AlarmScheduler

    @Binds
    abstract fun bindTimerNotifier(impl: AndroidTimerNotifier): TimerNotifier

    @Binds
    abstract fun bindRinger(impl: AndroidRinger): Ringer

    @Binds
    abstract fun bindTimerSystem(impl: AndroidTimerSystem): TimerSystem

    @Binds
    abstract fun bindMonotonicClock(impl: SystemMonotonicClock): MonotonicClock

    /** timer_alarm and timer_ongoing, created by the app at startup (Phase 1b integration). */
    @Binds
    @IntoSet
    abstract fun bindChannelInstaller(impl: TimerChannelInstaller): NotificationChannelInstaller
}

@Module
@InstallIn(SingletonComponent::class)
internal object TimerStoreModule {
    /** `files/datastore/cook_timers.json`; an unreadable file is replaced by an empty one (see [TimerStore]). */
    @Provides
    @Singleton
    fun provideTimerStore(@ApplicationContext context: Context): TimerStore =
        DataStoreTimerStore(timerDataStore { context.dataStoreFile(DataStoreTimerStore.FILE_NAME) })

    @Provides
    fun provideTimerEngineConfig(): TimerEngineConfig = TimerEngineConfig()
}

/** For the receivers, the ringing service and the alarm activity, which Hilt does not construct. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TimerEntryPoint {
    fun engine(): DefaultTimerController

    fun notifier(): AndroidTimerNotifier

    fun system(): TimerSystem

    @ApplicationScope
    fun scope(): CoroutineScope

    companion object {
        fun from(context: Context): TimerEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, TimerEntryPoint::class.java)
    }
}
