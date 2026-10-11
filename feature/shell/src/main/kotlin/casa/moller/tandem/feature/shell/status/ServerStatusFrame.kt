package casa.moller.tandem.feature.shell.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import casa.moller.tandem.core.ui.status.SafeModeBanner
import casa.moller.tandem.core.ui.status.ServerTooOldNotice
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
 * the safe mode banner on top of the tab content, and the blocking notice for a server that is too
 * old in place of every tab but "More" (where the user can log out or change server).
 *
 * The banner takes the status bar inset and consumes it, so the screens below it do not pad for
 * the status bar twice. It is left out on the safe mode explanation itself and on screens that
 * hide all navigation (cook mode).
 */
@Composable
internal fun ServerStatusFrame(
    state: ShellUiState,
    navigation: ShellNavigationState,
    onOpenSafeModeInfo: () -> Unit,
    onCheckAgain: () -> Unit,
    content: @Composable () -> Unit,
) {
    val notice = state.serverNotice
    val current = navigation.currentKey
    val showBanner = notice is ServerNotice.SafeMode &&
        current != SafeModeInfoKey &&
        (current as? FullScreenDestination)?.hidesNavigationOnAllWidths != true
    val bannerInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Column(modifier = Modifier.fillMaxSize()) {
        if (notice is ServerNotice.SafeMode && showBanner) {
            SafeModeBanner(
                version = notice.version,
                compatibility = notice.compatibility,
                onClick = onOpenSafeModeInfo,
                windowInsets = bannerInsets,
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
