package casa.moller.tandem.core.sync

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.AccountMirror
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.engine.SyncKeys
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Starts the data layer once per process (called from `TandemApplication.onCreate`):
 *
 * - installs the app's Coil [ImageLoader] as Coil's singleton (every `AsyncImage` uses the
 *   account's image client and the offline disk cache);
 * - starts the account mirror (server_account rows, logout clean-up, version notices);
 * - follows the active source: a logged-in account gets the periodic sync and its outbox sent;
 *   demo mode seeds the demo database; logging out or demo mode cancel the periodic sync;
 *   switching to another account asks for an ACCOUNT_CHANGED sync, except for an account that was
 *   just added and has never synced (Fas S rester: the intro's FIRST_SYNC makes its first sync, so
 *   adding an account while another was logged in synced twice; see [syncsOnAccountChange]);
 * - follows Settings → Sync → Interval (Phase 1b): the periodic sync gets the chosen period at
 *   once, and "manually" cancels it (start, resume and pull to refresh still sync);
 * - asks for a sync when the app comes to the foreground (APP_START once, then APP_RESUMED; the
 *   engine's throttles decide what is actually fetched);
 * - rewrites the demo data in the new language when the app language changes in demo mode (the
 *   per-app language recreates the activity without a trip to the background, plan 6.7).
 */
@Singleton
class SyncStarter @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val sources: ActiveSources,
    private val mirror: AccountMirror,
    private val demo: DemoSeeder,
    private val scheduler: SyncScheduler,
    private val guard: SyncGuard,
    private val syncRepository: SyncRepository,
    private val imageLoader: Provider<ImageLoader>,
    private val settings: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
    // Fas S (rester lane): whether the new account has synced before (no double sync after adding one).
    private val databases: TandemDatabaseFactory,
    private val clock: SyncClock,
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

    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (sources.source.value != ActiveSource.Demo) return
            // The seed key holds the language: an unchanged one costs a single read.
            val locale = newConfig.locales.takeIf { !it.isEmpty }?.get(0)
            scope.launch { if (locale != null) demo.ensureSeeded(locale) else demo.ensureSeeded() }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
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
        scope.launch { followSyncInterval() }
        context.registerComponentCallbacks(configuration)
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
                // The periodic sync follows the account and the interval (followSyncInterval).
                scheduler.scheduleOutbox()
                if (previous is ActiveSource.Remote && syncsOnAccountChange(current)) {
                    syncRepository.requestSync(SyncReason.ACCOUNT_CHANGED)
                }
            }

            ActiveSource.Demo -> demo.ensureSeeded()

            ActiveSource.None, ActiveSource.Loading -> Unit
        }
    }

    /** [syncsOnAccountChange] for [current], with its stored "last synced" time. */
    private suspend fun syncsOnAccountChange(current: ActiveSource.Remote): Boolean {
        val lastSuccess = databases.get(current.kind).syncStateDao().get(current.accountId, SyncKeys.LAST_SUCCESS)
        return syncsOnAccountChange(
            addedAtMillis = current.account.addedAt.toEpochMilliseconds(),
            lastSuccessMillis = lastSuccess?.lastFullRelistEpochMillis,
            nowMillis = clock.nowMillis(),
        )
    }

    /**
     * The periodic sync exists only for a logged-in account and an interval other than
     * [SyncInterval.MANUAL]; a changed interval re-schedules it (WorkManager's UPDATE keeps the
     * schedule and takes the new period). Logging out and demo mode cancel it.
     */
    private suspend fun followSyncInterval() {
        val loggedIn = sources.source
            .filter { it != ActiveSource.Loading }
            .map { it is ActiveSource.Remote }
            .distinctUntilChanged()
        val interval = settings.settings.map { it.syncInterval }.distinctUntilChanged()
        combine(loggedIn, interval) { remote, chosen -> if (remote) chosen else null }
            .distinctUntilChanged()
            .collect { chosen ->
                if (chosen == null || chosen.minutes == null) scheduler.cancelPeriodic() else scheduler.schedulePeriodic(chosen)
            }
    }

    internal companion object {
        /** How long after a login a never-synced account counts as "just added" (its intro is showing). */
        val JUST_ADDED: Duration = 10.minutes

        /**
         * Fas S (rester lane, Phase 2's open issue "två synkar i rad"): whether a switch from another
         * account to this one asks for an ACCOUNT_CHANGED sync. Logging in from the intro while
         * another account is active makes the new account active at once, and the intro's FIRST_SYNC
         * (started by "Continue") is its first full sync; an ACCOUNT_CHANGED sync at the login ran
         * the same full sync a second time. So it is skipped for an account that was added less than
         * [JUST_ADDED] ago and has never finished a sync ([lastSuccessMillis] null). A switch to an
         * account that has synced before, or one added long ago whose first sync never finished,
         * still syncs. A clock set back (added "in the future") counts as just added.
         */
        fun syncsOnAccountChange(addedAtMillis: Long, lastSuccessMillis: Long?, nowMillis: Long): Boolean {
            if (lastSuccessMillis != null) return true
            return nowMillis - addedAtMillis >= JUST_ADDED.inWholeMilliseconds
        }
    }
}
