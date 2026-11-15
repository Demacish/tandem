package casa.moller.tandem.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.R
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemIconHero
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.AdaptiveContentCard
import casa.moller.tandem.lock.AppLockAction
import casa.moller.tandem.lock.AppLockUiState

/**
 * Fas 5 (las lane, plan §5.5): the lock screen in front of the whole app while Mer → Lås appen is
 * on and the app is locked. "Lås upp" asks for the phone's own screen lock (the system's prompt
 * opens by itself when the screen comes up). Without a screen lock on the phone it explains that
 * and offers the system settings, or to go on without the lock this time.
 *
 * Nothing of the user's is on it: no account, no list, no recipe.
 */
@Composable
internal fun AppLockScreen(state: AppLockUiState, onAction: (AppLockAction) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AdaptiveContentCard(modifier = Modifier.safeDrawingPadding(), maxContentWidth = TandemTheme.sizes.maxReadingWidth) {
            TandemIconHero(icon = TandemIcons.Lock, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                text = stringResource(R.string.app_lock_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
            )
            if (state.noScreenLock) {
                InfoCard(
                    title = stringResource(R.string.app_lock_no_screen_lock_title),
                    body = stringResource(R.string.app_lock_no_screen_lock_body),
                    icon = TandemIcons.Warning,
                    tone = TandemTone.Warning,
                )
                TandemPrimaryButton(
                    text = stringResource(R.string.app_lock_open_settings),
                    onClick = { onAction(AppLockAction.OpenScreenLockSettings) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = TandemIcons.Settings,
                )
                TandemSecondaryButton(
                    text = stringResource(R.string.app_lock_continue_without),
                    onClick = { onAction(AppLockAction.ContinueWithoutLock) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(R.string.app_lock_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failureText(state)?.let { failure ->
                    InfoCard(
                        title = stringResource(R.string.app_lock_failed_title),
                        body = failure,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        icon = TandemIcons.Warning,
                        tone = TandemTone.Warning,
                    )
                }
                TandemPrimaryButton(
                    text = stringResource(R.string.app_lock_unlock),
                    onClick = { onAction(AppLockAction.Unlock) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.prompting,
                    leadingIcon = TandemIcons.Lock,
                )
                Text(
                    text = stringResource(R.string.app_lock_still_working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The system's own text after a failed prompt, or ours when it gave none. */
@Composable
private fun failureText(state: AppLockUiState): String? = when {
    state.failure != null -> state.failure
    state.failed -> stringResource(R.string.app_lock_failed_body)
    else -> null
}
