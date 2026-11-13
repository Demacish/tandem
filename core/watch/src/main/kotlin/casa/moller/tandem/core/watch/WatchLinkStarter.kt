package casa.moller.tandem.core.watch

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.datastore.SecureAccountStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Started once per process by the app (`TandemApplication`): the timer mirror and the pairing's
 * upkeep (renewal, a logged-out account's watch token). Without a pairing it does nothing but
 * read one small file; without Google Play services every Data Layer call fails quietly.
 */
@Singleton
class WatchLinkStarter @Inject internal constructor(
    private val repository: DefaultWatchRepository,
    private val mirror: WatchTimerMirror,
    private val accounts: SecureAccountStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        mirror.start(scope)
        scope.launch {
            // Not during the app's first frames; then again whenever an account is added or removed.
            delay(START_DELAY_MILLIS)
            accounts.accounts.map { list -> list.map { it.id }.toSet() }.distinctUntilChanged().collect { repository.maintain() }
        }
    }

    private companion object {
        const val START_DELAY_MILLIS = 10_000L
    }
}
