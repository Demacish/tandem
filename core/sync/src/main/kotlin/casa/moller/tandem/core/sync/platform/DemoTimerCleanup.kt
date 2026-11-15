package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.model.timer.TimerSource
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Enhetstestet B17: a finished demo timer ("Potatis · 40 s, klar") showed up in cook mode after
 * logging in to a real server. Timers are stored on the device and outlive the source they were
 * started in, so demo mode's timers (`TimerSource.DEMO`) are removed whenever the active source is
 * anything but demo mode: a real account or none (the intro). Running, ringing and finished ones,
 * with their alarms and notifications (`TimerController.cancelSource`); the timer list, the
 * widgets and the watch follow the controller. Unlike [AccountNotificationCleanup] this also runs
 * for the first source of a process, so a demo timer stored before the process ended (the app was
 * closed in demo mode, then a login came after a restart) goes too. Timers of real accounts stay:
 * they belong to the device (TimerController).
 */
@Singleton
internal class DemoTimerCleanup @Inject constructor(
    private val sources: ActiveSources,
    private val timers: TimerController,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            sources.source
                .filter { it != ActiveSource.Loading }
                .distinctUntilChangedBy { it.identity }
                .collect { current ->
                    if (current != ActiveSource.Demo) runCatching { timers.cancelSource(TimerSource.DEMO) }
                }
        }
    }
}
