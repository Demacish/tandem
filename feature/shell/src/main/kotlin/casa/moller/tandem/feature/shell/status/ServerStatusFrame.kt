package casa.moller.tandem.feature.shell.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.containerColor
import casa.moller.tandem.core.designsystem.component.contentColor
import casa.moller.tandem.core.designsystem.component.iconColor
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import casa.moller.tandem.core.ui.status.LoginExpiredBanner
import casa.moller.tandem.core.ui.status.SafeModeBanner
import casa.moller.tandem.core.ui.status.ServerTooOldNotice
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ShellMode
import casa.moller.tandem.feature.shell.ShellUiState
import casa.moller.tandem.feature.shell.navigation.SafeModeInfoKey
import casa.moller.tandem.feature.shell.navigation.ShellNavigationState
import casa.moller.tandem.feature.shell.navigation.ShellTab

/** The server status the shell shows around the tab content (feature #2, plan 3.6). */
internal sealed interface ServerNotice {
    /** Untested version: the non-blocking safe mode banner. */
    data class SafeMode(val version: String?, val compatibility: ServerCompatibility) : ServerNotice

    /** Older than Tandem supports: the blocking notice instead of the tabs' content. */
    data class TooOld(val version: String?) : ServerNotice
}

/** The notice for the logged-in server, null when it is supported (and always in demo mode). */
internal val ShellUiState.serverNotice: ServerNotice?
    get() {
        val account = (mode as? ShellMode.Connected)?.account ?: return null
        return when {
            account.compatibility == ServerCompatibility.TOO_OLD -> ServerNotice.TooOld(account.serverVersion)
            account.compatibility.isSafeMode -> ServerNotice.SafeMode(account.serverVersion, account.compatibility)
            else -> null
        }
    }

/**
 * The hook between the shell and the server status UI of core:ui (docs/BYGGE.md "Serverstatus"):
 * the "Log in again" banner after Tandoor refused the token, the one-time "Tandoor was updated"
 * banner (plan B18, when no notification could be posted), the safe mode banner on top of the tab
 * content, and the blocking notice for a server that is too old in place of every tab but "More"
 * (where the user can log out or change server).
 *
 * The topmost banner takes the status bar inset and consumes it, so the screens below it do not
 * pad for the status bar twice. Banners are left out on the safe mode explanation itself (the
 * safe mode banner) and on screens that hide all navigation (cook mode).
 */
@Composable
internal fun ServerStatusFrame(
    state: ShellUiState,
    navigation: ShellNavigationState,
    onOpenSafeModeInfo: () -> Unit,
    onCheckAgain: () -> Unit,
    onLogInAgain: () -> Unit,
    onDismissServerUpdate: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val notice = state.serverNotice
    val current = navigation.currentKey
    val bannersAllowed = (current as? FullScreenDestination)?.hidesNavigationOnAllWidths != true
    val showLoginExpired = state.loginExpired && bannersAllowed
    val showSafeMode = notice is ServerNotice.SafeMode && current != SafeModeInfoKey && bannersAllowed
    // Never next to a safe mode or "too old" notice: one message per version change.
    val updatedTo = state.serverUpdatedTo?.takeIf { bannersAllowed && notice == null }
    val showBanner = showLoginExpired || showSafeMode || updatedTo != null
    val bannerInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Column(modifier = Modifier.fillMaxSize()) {
        // The topmost banner keeps its text clear of the status bar.
        if (showLoginExpired) {
            LoginExpiredBanner(onLogInAgain = onLogInAgain, windowInsets = bannerInsets)
        }
        if (updatedTo != null) {
            ServerUpdatedBanner(
                version = updatedTo,
                onDismiss = onDismissServerUpdate,
                windowInsets = if (showLoginExpired) WindowInsets(0) else bannerInsets,
            )
        }
        if (notice is ServerNotice.SafeMode && showSafeMode) {
            SafeModeBanner(
                version = notice.version,
                compatibility = notice.compatibility,
                onClick = onOpenSafeModeInfo,
                windowInsets = if (showLoginExpired) WindowInsets(0) else bannerInsets,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .then(if (showBanner) Modifier.consumeWindowInsets(bannerInsets) else Modifier),
        ) {
            if (notice is ServerNotice.TooOld && navigation.currentTab != ShellTab.MORE) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ServerTooOldNotice(
                        version = notice.version,
                        onCheckAgain = onCheckAgain,
                        modifier = Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            } else {
                content()
            }
        }
    }
}

/** Test tag of the "Tandoor was updated" banner. */
internal const val SERVER_UPDATED_BANNER_TAG = "shell_server_updated_banner"

/**
 * Slim banner in the style of the other status banners: "Tandoor was updated to 2.6.15 – Tandem
 * is tested with this version" with a close button (plan B18, the in-app fallback for the
 * notification). Announced politely by TalkBack when it appears.
 */
@Composable
internal fun ServerUpdatedBanner(
    version: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0),
) {
    val spacing = TandemTheme.spacing
    val tone = TandemTone.Success
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SERVER_UPDATED_BANNER_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(windowInsets)
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(start = spacing.lg, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = TandemIcons.CheckCircle,
                contentDescription = null,
                tint = tone.iconColor(),
                modifier = Modifier.size(TandemTheme.sizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shell_server_updated_title, version),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = stringResource(R.string.shell_server_updated_body), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = TandemIcons.Close,
                    contentDescription = stringResource(DesignR.string.tandem_action_close),
                    modifier = Modifier.size(TandemTheme.sizes.icon),
                )
            }
        }
    }
}
