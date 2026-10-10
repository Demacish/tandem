package casa.moller.tandem.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.AppStatusChip
import casa.moller.tandem.core.ui.DemoBanner

/**
 * Marks a Navigation 3 key whose screen is shown ABOVE the tab bar (plan 6.1: recipe detail, cook
 * mode, ...). On compact widths (phones in portrait) the shell hides its bottom bar while such a
 * key is on top; on wider windows the navigation rail stays unless
 * [hidesNavigationOnAllWidths] is set (cook mode). Keys stay plain data; feature modules
 * implement this next to `androidx.navigation3.runtime.NavKey`.
 */
interface FullScreenDestination {
    /** Also hide the navigation rail on medium and expanded widths (cook mode, store mode). */
    val hidesNavigationOnAllWidths: Boolean
        get() = false
}

/**
 * What every screen inside the shell needs to know about the app around it (plan 6.2): the status
 * chip next to the title (demo mode / safe mode) and the demo banner with its actions. The shell
 * provides it with [LocalAppChrome]; feature screens read it, so features never depend on the
 * shell. Previews and tests get the default (no chip, no banner).
 *
 * @property status the chip to show next to screen titles, null for none.
 * @property showDemoBanner show the demo banner at the top of top-level screens.
 * @property onConnectTandoor "Connect your Tandoor" (leaves demo mode, opens the intro's server step).
 * @property onDismissDemoBanner hides the banner for this session (the chip stays).
 */
@Immutable
data class AppChrome(
    val status: AppStatus? = null,
    val showDemoBanner: Boolean = false,
    val onConnectTandoor: () -> Unit = {},
    val onDismissDemoBanner: () -> Unit = {},
)

/** The [AppChrome] of the current screen; provided by the shell. */
val LocalAppChrome = compositionLocalOf { AppChrome() }

/** The status chip of [LocalAppChrome] (for a top bar's `actions`), or nothing. */
@Composable
fun AppChromeStatusChip(modifier: Modifier = Modifier) {
    val status = LocalAppChrome.current.status ?: return
    AppStatusChip(status = status, modifier = modifier)
}

/** The demo banner of [LocalAppChrome] for the top of a top-level screen, or nothing. */
@Composable
fun AppChromeDemoBanner(modifier: Modifier = Modifier) {
    val chrome = LocalAppChrome.current
    if (chrome.showDemoBanner) {
        DemoBanner(onConnect = chrome.onConnectTandoor, onDismiss = chrome.onDismissDemoBanner, modifier = modifier)
    }
}
