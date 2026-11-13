package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.reminder.DailyReminderKind
import casa.moller.tandem.core.sync.reminder.DailyReminderScheduler
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Fas 4 (skafferinotis lane): the morning expiry check exists only while Settings → "Notis när
 * något går ut" is on AND a real account is active (started by `SyncStarter`). Switching it off,
 * logging out or demo mode cancel it; switching it on plans it for the next morning. Fas 5 (planera
 * lane): at the chosen time (`AppSettings.pantryExpiryTime`); a new time, or a phone in another zone
 * at the next app start, plans it again ([DailyReminderScheduler.ensure]).
 */
@Singleton
internal class PantryExpiryReminders @Inject constructor(
    private val settings: SettingsStore,
    private val sources: ActiveSources,
    private val scheduler: DailyReminderScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val remote = sources.source.filter { it != ActiveSource.Loading }.map { it is ActiveSource.Remote }.distinctUntilChanged()
            val wanted = settings.settings.map { s -> if (s.pantryExpiryNotifications) s.pantryExpiryTime else null }.distinctUntilChanged()
            combine(remote, wanted) { account, time -> time.takeIf { account } }
                .distinctUntilChanged()
                .collect { time ->
                    val kind = DailyReminderKind.PANTRY_EXPIRY
                    if (time != null) scheduler.ensure(kind, time) else scheduler.cancel(kind)
                }
        }
    }
}
