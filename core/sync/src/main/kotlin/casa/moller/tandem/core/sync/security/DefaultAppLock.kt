package casa.moller.tandem.core.sync.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.domain.security.AppLockPolicy
import casa.moller.tandem.core.domain.security.AppLockState
import casa.moller.tandem.core.domain.security.AppSurface
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.model.settings.AppLockTimeout
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.sync.platform.SyncClock
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Fas 5 (las lane, plan §5.5): [AppLock] for the process, on core:domain's [AppLockPolicy]
 * (docs/BYGGE.md "Lås appen"). [start] (from `TandemApplication.onCreate`) follows the stored
 * setting and the process lifecycle: ON_STOP (no screen of the app visible, the screen off
 * included) starts the background time, ON_START looks at it. The time is measured on
 * [SyncClock.elapsedMillis] (`elapsedRealtime`: runs in deep sleep, does not move with the phone's
 * clock). While in the background a timer locks when the timeout has passed, so the app is
 * already locked when it comes back; a phone asleep runs that timer late, so ON_START and
 * [unlockedWithinTimeout] look at the clock again.
 *
 * The setting comes from [SettingsStore.storedSettings], never from the defaults a read error
 * gives for a moment, and only [setEnabled] in this process can turn the lock on without locking
 * (fix-09): a lock that turns on any other way locks.
 *
 * Nothing else in the process asks it: timers, the alarm, WorkManager, the reminders and the
 * watch keep working while the app is locked.
 */
@Singleton
class DefaultAppLock @Inject internal constructor(
    private val settingsStore: SettingsStore,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
) : AppLock {
    private val state = MutableStateFlow(AppLockPolicy.coldStart())
    private val statusFlow = MutableStateFlow(AppLockStatus.UNKNOWN)
    private val lockedFlow = MutableStateFlow(true)
    private val started = AtomicBoolean(false)

    /** fix-09: [setEnabled] (true) is under way in this process, so the "on" it writes does not lock. */
    private val enabledInApp = AtomicBoolean(false)

    private var timeoutJob: Job? = null

    override val settings: Flow<AppLockSettings> = settingsStore.storedSettings.map { it.appLock }.distinctUntilChanged()

    override val status: StateFlow<AppLockStatus> = statusFlow.asStateFlow()

    override val isLocked: StateFlow<Boolean> = lockedFlow.asStateFlow()

    /** Starts following the setting and the process lifecycle (once; on the main thread). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        followSettings()
        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = onForeground()

                    override fun onStop(owner: LifecycleOwner) = onBackground()
                },
            )
        } catch (e: IllegalStateException) {
            // Without the process lifecycle (never expected) the lock still locks at every cold start.
        }
    }

    /** The stored setting reaches the policy (tests call it instead of [start]). */
    internal fun followSettings() {
        scope.launch {
            settings.collect { stored ->
                val inApp = stored.enabled && enabledInApp.getAndSet(false)
                change { AppLockPolicy.withSettings(it, stored, enabledInApp = inApp) }
            }
        }
    }

    /** A screen of the app is visible again (the process lifecycle's ON_START). */
    internal fun onForeground() {
        timeoutJob?.cancel()
        timeoutJob = null
        change { AppLockPolicy.toForeground(it, clock.elapsedMillis()) }
    }

    /** No screen of the app is visible (ON_STOP): the background time starts, and a timer locks when it has passed. */
    internal fun onBackground() {
        change { AppLockPolicy.toBackground(it, clock.elapsedMillis()) }
        val current = state.value
        val timeout = current.settings?.takeIf { it.enabled && !current.locked }?.timeout ?: return
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeout.millis)
            change { AppLockPolicy.tick(it, clock.elapsedMillis()) }
        }
    }

    override fun onUnlocked() = change(AppLockPolicy::unlocked)

    override suspend fun unlockedWithinTimeout(): Boolean {
        statusFlow.first { it != AppLockStatus.UNKNOWN }
        change { AppLockPolicy.tick(it, clock.elapsedMillis()) }
        return !AppLockPolicy.blocks(AppSurface.APP_FUNCTION, statusFlow.value)
    }

    override suspend fun setEnabled(enabled: Boolean): Outcome<Unit> {
        // Only a real off-to-on change is marked: one that never reaches the collector must not
        // leave the mark for a later "on" that comes from somewhere else.
        enabledInApp.set(enabled && state.value.settings?.enabled == false)
        val outcome = write { it.copy(appLock = it.appLock.copy(enabled = enabled)) }
        if (outcome is Outcome.Failure) enabledInApp.set(false)
        return outcome
    }

    override suspend fun setTimeout(timeout: AppLockTimeout): Outcome<Unit> =
        write { it.copy(appLock = it.appLock.copy(timeout = timeout)) }

    /** The current [AppLockState] (tests). */
    internal val current: AppLockState
        get() = state.value

    /**
     * One event at a time (the lifecycle on the main thread, the setting on a background one): the
     * status never goes back to an older one.
     */
    private fun change(transform: (AppLockState) -> AppLockState) = synchronized(this) {
        val next = transform(state.value)
        state.value = next
        val status = next.status
        statusFlow.value = status
        lockedFlow.value = !status.allowsContent
    }

    /** The setting reaches the policy through [settings] (the collector in [start]). */
    private suspend fun write(transform: (AppSettings) -> AppSettings): Outcome<Unit> = try {
        settingsStore.update(transform)
        Outcome.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Outcome.Failure(DataProblem.SETTINGS_NOT_SAVED.toError())
    }
}
