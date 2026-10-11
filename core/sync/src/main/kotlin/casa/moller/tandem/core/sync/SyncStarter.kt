package casa.moller.tandem.core.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.AccountMirror
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.platform.SyncScheduler
import coil3.ImageLoader
import coil3.SingletonImageLoader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Starts the data layer once per process (called from `TandemApplication.onCreate`):
 *
 * - installs the app's Coil [ImageLoader] as Coil's singleton (every `AsyncImage` uses the
 *   account's image client and the offline disk cache);
 * - starts the account mirror (server_account rows, logout clean-up, version notices);
 * - follows the active source: a logged-in account gets the periodic sync and its outbox sent;
 *   demo mode seeds the demo database; logging out or demo mode cancel the periodic sync;
 *   switching to another account asks for an ACCOUNT_CHANGED sync;
 * - asks for a sync when the app comes to the foreground (APP_START once, then APP_RESUMED; the
 *   engine's throttles decide what is actually fetched).
 */
@Singleton
class SyncStarter @Inject internal constructor(
    private val sources: ActiveSources,
    private val mirror: AccountMirror,
    private val demo: DemoSeeder,
    private val scheduler: SyncScheduler,
    private val guard: SyncGuard,
    private val syncRepository: SyncRepository,
    private val imageLoader: Provider<ImageLoader>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var foregroundBefore = false

    private val foreground = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            val reason = if (foregroundBefore) SyncReason.APP_RESUMED else SyncReason.APP_START
            foregroundBefore = true
            syncRepository.requestSync(reason)
        }
    }

    /** Starts everything; later calls do nothing. Call on the main thread. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        SingletonImageLoader.setSafe { imageLoader.get() }
        mirror.start()
        scope.launch {
            var previous: ActiveSource? = null
            sources.source
                .filter { it != ActiveSource.Loading }
                .distinctUntilChangedBy { it.identity }
                .collect { current ->
                    onSourceChanged(previous, current)
                    previous = current
                }
        }
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(foreground)
        } catch (e: IllegalStateException) {
            // Without the process lifecycle (never expected) the periodic sync still runs.
        }
    }

    private suspend fun onSourceChanged(previous: ActiveSource?, current: ActiveSource) {
        if (previous != null) guard.reset()
        when (current) {
            is ActiveSource.Remote -> {
                scheduler.schedulePeriodic()
                scheduler.scheduleOutbox()
                if (previous is ActiveSource.Remote) syncRepository.requestSync(SyncReason.ACCOUNT_CHANGED)
            }

            ActiveSource.Demo -> {
                scheduler.cancelPeriodic()
                demo.ensureSeeded()
            }

            ActiveSource.None -> scheduler.cancelPeriodic()

            ActiveSource.Loading -> Unit
        }
    }
}
