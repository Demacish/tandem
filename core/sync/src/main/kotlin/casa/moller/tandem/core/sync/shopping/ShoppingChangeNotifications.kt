package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.platform.ShoppingChangeNotifier
import casa.moller.tandem.core.sync.platform.ShoppingNotificationPolicy
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fas 4 "Notiser när någon i hushållet ändrar listan": turns the changes the reads notice
 * ([ShoppingChangeBus]) into one notification per batch while the app is not on screen and the
 * setting is on. A read emits its changes one by one, so they are gathered for [BATCH_WINDOW]
 * before one notification sums them up; a later batch replaces it. On screen, the list's own
 * snackbars tell instead. Started once by `SyncStarter`.
 */
@Singleton
internal class ShoppingChangeNotifications @Inject constructor(
    private val bus: ShoppingChangeBus,
    private val settings: SettingsStore,
    private val visibility: AppVisibility,
    private val notifier: ShoppingChangeNotifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lock = Mutex()
    private val pending = mutableListOf<ShoppingChange>()
    private var flush: Job? = null

    /** Starts listening; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            bus.changes.collect { change ->
                val enabled = settings.settings.first().shoppingChangeNotifications
                if (!ShoppingNotificationPolicy.shouldPost(enabled = enabled, appVisible = visibility.isVisible())) return@collect
                lock.withLock {
                    pending += change.change
                    if (flush == null) flush = launch { flushAfter(BATCH_WINDOW) }
                }
            }
        }
    }

    /** The account was left: what is gathered from it is never posted (Fas 4, integration). */
    suspend fun dropPending() {
        lock.withLock {
            flush?.cancel()
            flush = null
            pending.clear()
        }
    }

    private suspend fun flushAfter(window: Duration) {
        delay(window)
        val batch = lock.withLock {
            flush = null
            pending.toList().also { pending.clear() }
        }
        // Checked again: the app may have come back on screen meanwhile.
        if (visibility.isVisible()) return
        notifier.post(ShoppingNotificationPolicy.summarize(batch))
    }

    companion object {
        /** How long changes of one read are gathered before the notification. */
        val BATCH_WINDOW: Duration = 3.seconds
    }
}
