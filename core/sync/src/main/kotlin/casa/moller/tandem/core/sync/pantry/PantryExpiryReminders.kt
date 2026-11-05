package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.work.PantryExpiryScheduler
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
 * logging out or demo mode cancel it; switching it on schedules it for the next morning.
 */
@Singleton
internal class PantryExpiryReminders @Inject constructor(
    private val settings: SettingsStore,
    private val sources: ActiveSources,
    private val scheduler: PantryExpiryScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val remote = sources.source.filter { it != ActiveSource.Loading }.map { it is ActiveSource.Remote }.distinctUntilChanged()
            val enabled = settings.settings.map { it.pantryExpiryNotifications }.distinctUntilChanged()
            combine(remote, enabled) { account, on -> account && on }
                .distinctUntilChanged()
                .collect { wanted -> if (wanted) scheduler.schedule() else scheduler.cancel() }
        }
    }
}
