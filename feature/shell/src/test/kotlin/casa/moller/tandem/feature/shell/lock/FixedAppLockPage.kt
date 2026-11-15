package casa.moller.tandem.feature.shell.lock

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.settings.AppLockTimeout

// Fas 5 (las lane): Mer → Lås appen in the shell's tests without Hilt.

internal val LOCK_OFF = AppLockSettingsUiState(loaded = true)
internal val LOCK_ON = AppLockSettingsUiState(loaded = true, enabled = true, timeout = AppLockTimeout.ONE_MINUTE)
internal val LOCK_ON_IMMEDIATELY = LOCK_ON.copy(timeout = AppLockTimeout.IMMEDIATELY)
internal val LOCK_NO_SCREEN_LOCK = LOCK_OFF.copy(screenLockMissing = true)
internal val LOCK_ON_SCREEN_LOCK_GONE = LOCK_ON.copy(screenLockMissing = true)

/** Fas 5 (funktioner lane): the assistant allowed, so "Tillåt assistenten när appen är låst" shows. */
internal val LOCK_ON_ASSISTANT = LOCK_ON.copy(assistantEnabled = true)

/** The section and the page with a fixed state. */
internal class FixedAppLockPage(
    private val state: AppLockSettingsUiState = LOCK_ON,
    private val onAction: (AppLockSettingsAction) -> Unit = {},
) : AppLockPage {
    @Composable
    override fun MoreSection(selected: Boolean, onOpen: () -> Unit) = AppLockMoreSection(state, selected, onOpen)

    @Composable
    override fun AppLock(onNavigateBack: () -> Unit) = AppLockSettingsScreen(state, onAction, onNavigateBack)
}
