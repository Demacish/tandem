package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.security.AppLock
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.model.settings.AppLockTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Fas 5 (las lane, plan §5.5): Mer → Lås appen in memory (the real one is core:sync's DefaultAppLock).

/**
 * An [AppLock] the test drives: [settingsState] is the stored setting, [statusState] where the lock
 * stands. Turning the lock on unlocks (as in the app), turning it off gives [AppLockStatus.OFF].
 */
class FakeAppLock(
    settings: AppLockSettings = AppLockSettings(),
    status: AppLockStatus = if (settings.enabled) AppLockStatus.LOCKED else AppLockStatus.OFF,
) : AppLock {
    val settingsState = MutableStateFlow(settings)
    val statusState = MutableStateFlow(status)
    private val locked = MutableStateFlow(!status.allowsContent)

    /** How often the lock screen reported a confirmed screen lock. */
    var unlocks: Int = 0
        private set

    /** The next setter fails like a settings file that cannot be written. */
    var failWrites: Outcome.Failure? = null

    override val settings: Flow<AppLockSettings> = settingsState

    override val status: StateFlow<AppLockStatus> = statusState.asStateFlow()

    override val isLocked: StateFlow<Boolean> = locked.asStateFlow()

    /** Sets [status] (and [isLocked] with it). */
    fun setStatus(status: AppLockStatus) {
        statusState.value = status
        locked.value = !status.allowsContent
    }

    override suspend fun unlockedWithinTimeout(): Boolean = statusState.value.allowsContent

    override fun onUnlocked() {
        unlocks++
        if (statusState.value == AppLockStatus.LOCKED) setStatus(AppLockStatus.UNLOCKED)
    }

    override suspend fun setEnabled(enabled: Boolean): Outcome<Unit> {
        failWrites?.let { return it }
        settingsState.update { it.copy(enabled = enabled) }
        setStatus(if (enabled) AppLockStatus.UNLOCKED else AppLockStatus.OFF)
        return Outcome.Success(Unit)
    }

    override suspend fun setTimeout(timeout: AppLockTimeout): Outcome<Unit> {
        failWrites?.let { return it }
        settingsState.update { it.copy(timeout = timeout) }
        return Outcome.Success(Unit)
    }
}
