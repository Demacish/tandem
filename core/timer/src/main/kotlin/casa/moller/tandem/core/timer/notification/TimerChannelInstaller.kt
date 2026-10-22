package casa.moller.tandem.core.timer.notification

import casa.moller.tandem.core.data.notification.NotificationChannelInstaller
import javax.inject.Inject

/** The two timer channels (`timer_alarm`, `timer_ongoing`), for the app's startup (plan 8.7). */
internal class TimerChannelInstaller @Inject constructor(private val notifier: AndroidTimerNotifier) : NotificationChannelInstaller {
    override fun createChannels() = notifier.ensureChannels()
}
