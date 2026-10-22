package casa.moller.tandem.feature.shell.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.containerColor
import casa.moller.tandem.core.designsystem.component.contentColor
import casa.moller.tandem.core.designsystem.component.iconColor
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.feature.shell.R

/** Test tag of the banner for a held-back sync. */
internal const val SYNC_HELD_BACK_BANNER_TAG = "shell_sync_held_back_banner"

/**
 * Phase 1b (inställningar lane, 1a open issue): the sync is held back (plan 4.1), in the style of
 * the other status banners. [SyncPause.BLOCKED]: Tandoor or something in front of it turns the
 * app away for a while (a 403 without Tandoor's JSON, or 429). [SyncPause.CIRCUIT_BREAKER]: many
 * errors in a short time, so background requests wait five minutes. Both say that the recipes on
 * the phone still work and offer "Try again" (a sync the user asked for: it passes the circuit
 * breaker, while a block still waits for the server's time). Announced politely by TalkBack.
 */
@Composable
internal fun SyncHeldBackBanner(
    pause: SyncPause,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0),
) {
    val spacing = TandemTheme.spacing
    val tone = TandemTone.Warning
    val blocked = pause == SyncPause.BLOCKED
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SYNC_HELD_BACK_BANNER_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(windowInsets)
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(start = spacing.lg, end = spacing.sm, top = spacing.sm, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = TandemIcons.Warning,
                contentDescription = null,
                tint = tone.iconColor(),
                modifier = Modifier.size(TandemTheme.sizes.icon),
            )
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(
                    text = stringResource(if (blocked) R.string.sync_blocked_title else R.string.sync_breaker_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(if (blocked) R.string.sync_blocked_body else R.string.sync_breaker_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Filled: a text button's colour is too faint on the warning container (ATF contrast).
            TandemPrimaryButton(text = stringResource(R.string.sync_retry), onClick = onRetry, size = TandemButtonSize.Small)
        }
    }
}

