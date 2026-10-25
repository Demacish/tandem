package casa.moller.tandem.core.ui.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.preview.ThemePreviews
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.ui.status.OfflineNotice
import casa.moller.tandem.core.ui.status.SafeModeBanner
import casa.moller.tandem.core.ui.status.SafeModeInfoScreen
import casa.moller.tandem.core.ui.status.ServerTooOldNotice

/*
 * Samples of the server status components (core:ui status/) for @Preview and the Roborazzi tests.
 * Debug source set only; the versions are examples.
 */

/** The safe mode banner for the three safe mode cases, and the offline notice. */
@Composable
fun StatusBannersSample() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SafeModeBanner(version = "2.7.3", compatibility = ServerCompatibility.UNTESTED_NEWER, onClick = {})
        SafeModeBanner(version = "2.5.3", compatibility = ServerCompatibility.UNTESTED_OLDER, onClick = {})
        SafeModeBanner(version = null, compatibility = ServerCompatibility.UNRECOGNIZED, onClick = {})
        OfflineNotice()
    }
}

/** The blocking notice for a Tandoor 1.x server. */
@Composable
fun ServerTooOldSample() {
    ServerTooOldNotice(version = "1.5.19", onCheckAgain = {})
}

/** The explanation screen for an untested newer server. */
@Composable
fun SafeModeInfoSample() {
    SafeModeInfoScreen(version = "2.7.3", compatibility = ServerCompatibility.UNTESTED_NEWER, onBack = {}, onOpenReleases = {})
}

@ThemePreviews
@Composable
private fun StatusBannersPreview() = UiSampleSurface { StatusBannersSample() }

@ThemePreviews
@Composable
private fun ServerTooOldPreview() = UiSampleSurface { ServerTooOldSample() }

@Preview(name = "Phone", widthDp = 411, heightDp = 891)
@Composable
private fun SafeModeInfoPreview() = UiSampleSurface(fillScreen = true) { SafeModeInfoSample() }
