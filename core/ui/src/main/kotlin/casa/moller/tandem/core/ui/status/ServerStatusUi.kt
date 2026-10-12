package casa.moller.tandem.core.ui.status

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.containerColor
import casa.moller.tandem.core.designsystem.component.contentColor
import casa.moller.tandem.core.designsystem.component.iconColor
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.ui.ErrorState
import casa.moller.tandem.core.ui.R

/*
 * Server status UI (feature #2, plan 3.6 "Säkert läge", design
 * tandem_s_kert_l_ge_kompatibilitetsvarning): the non-blocking safe mode banner, the blocking
 * notice for a server that is too old, and the offline notice. The safe mode explanation screen is
 * in SafeModeInfoScreen.kt. The shell decides where they go (docs/BYGGE.md "Serverstatus").
 */

/**
 * Slim, non-blocking banner above the tab content while the app runs in safe mode ("Tandoor 2.7.1
 * is not tested yet – Tandem runs in safe mode"). The whole banner is one button that opens the
 * explanation ([SafeModeInfoScreen]); it never hides content or asks for a decision.
 *
 * @param version the version the server reports, null when unknown.
 * @param compatibility why the app is in safe mode (UNTESTED_NEWER, UNTESTED_OLDER or UNRECOGNIZED).
 * @param windowInsets insets to keep the text clear of (the status bar when the banner is at the
 *   top of the window); the warning colour still fills the area behind them.
 */
@Composable
fun SafeModeBanner(
    version: String?,
    compatibility: ServerCompatibility,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0),
) {
    val spacing = TandemTheme.spacing
    val tone = TandemTone.Warning
    val openLabel = stringResource(R.string.ui_safe_mode_banner_open)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick),
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(windowInsets)
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = TandemIcons.Shield,
                contentDescription = null,
                tint = tone.iconColor(),
                modifier = Modifier.size(TandemTheme.sizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = safeModeHeadline(version, compatibility),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = stringResource(R.string.ui_safe_mode_banner_body), style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                imageVector = TandemIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(TandemTheme.sizes.icon),
            )
        }
    }
}

/**
 * Banner above the tab content after Tandoor refused this device's token (401 or JSON-403, plan
 * 4.1 and 5.4): the sync has stopped, nothing was deleted, and "Log in again" leads to a new
 * login on the same server. Logging in to the same user and space keeps every stored recipe and
 * lets the outbox continue (plan 3.5). Announced politely by TalkBack when it appears.
 *
 * @param windowInsets insets to keep the text clear of (the status bar when the banner is at the
 *   top of the window); the colour still fills the area behind them.
 */
@Composable
fun LoginExpiredBanner(onLogInAgain: () -> Unit, modifier: Modifier = Modifier, windowInsets: WindowInsets = WindowInsets(0)) {
    val spacing = TandemTheme.spacing
    val tone = TandemTone.Error
    Surface(
        modifier = modifier
            .fillMaxWidth()
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
                imageVector = TandemIcons.Key,
                contentDescription = null,
                tint = tone.iconColor(),
                modifier = Modifier.size(TandemTheme.sizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui_login_expired_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = stringResource(R.string.ui_login_expired_body), style = MaterialTheme.typography.bodySmall)
            }
            TandemTextButton(text = stringResource(R.string.ui_login_expired_action), onClick = onLogInAgain)
        }
    }
}

/**
 * The headline for a server version the app runs in safe mode for (also used by the explanation
 * screen): "Tandoor 2.7.1 is not tested yet".
 */
@Composable
fun safeModeHeadline(version: String?, compatibility: ServerCompatibility): String {
    if (version.isNullOrBlank()) return stringResource(R.string.ui_safe_mode_headline_unknown)
    @StringRes val text = when (compatibility) {
        ServerCompatibility.UNTESTED_OLDER -> R.string.ui_safe_mode_headline_older
        ServerCompatibility.UNRECOGNIZED -> R.string.ui_safe_mode_headline_unrecognized
        else -> R.string.ui_safe_mode_headline_newer
    }
    return stringResource(text, version)
}

/**
 * Blocking notice for a server older than Tandem supports ([ServerCompatibility.TOO_OLD], plan
 * 3.6: outside the supported range writes are turned off entirely). Shown instead of the tab
 * content; the app's settings (log out, change server) stay reachable around it.
 *
 * @param onCheckAgain checks the server version again ("Try again").
 */
@Composable
fun ServerTooOldNotice(
    version: String?,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ErrorState(
        title = if (version.isNullOrBlank()) {
            stringResource(R.string.ui_server_too_old_title_unknown)
        } else {
            stringResource(R.string.ui_server_too_old_title, version)
        },
        body = stringResource(R.string.ui_server_too_old_body),
        modifier = modifier,
        icon = TandemIcons.Warning,
        onRetry = onCheckAgain,
        secondaryAction = secondaryAction,
    )
}

/**
 * "Offline – showing what is saved on this device": a quiet inline notice for screens that keep
 * showing stored content when the server cannot be reached (plan 6.2 "Offline visas senast
 * hämtade"). Announced politely by TalkBack when it appears.
 */
@Composable
fun OfflineNotice(modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(imageVector = TandemIcons.CloudOff, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.iconSmall))
            Text(text = stringResource(R.string.ui_offline_notice), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
