package casa.moller.tandem.feature.scan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.placeholder.PlaceholderPage
import casa.moller.tandem.feature.scan.barcode.BarcodeScanRoute
import casa.moller.tandem.feature.scan.navigation.AiSettingsKey
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.scan.navigation.FridgeScanKey
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.navigation.ScanDestination
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import casa.moller.tandem.feature.scan.receipt.ReceiptScanRoute
import casa.moller.tandem.feature.scan.review.ScanReviewRoute

// Fas S: the ONE route feature:shell shows for every [ScanDestination]. The skanning lane built the
// barcode scanner, the receipt scanner and the shared review list; the ai-foto lane replaces the
// fridge photo and Settings → AI branches (still placeholders) and ends its fridge photo in the
// same review (docs/BYGGE.md "Skanning").

/**
 * @param onNavigate opens another destination (the review after a scan, Settings → AI).
 * @param onBack closes the page.
 */
@Composable
fun ScanRoute(key: ScanDestination, onNavigate: (NavKey) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    when (key) {
        is BarcodeScanKey -> BarcodeScanRoute(key = key, onNavigate = onNavigate, onBack = onBack, modifier = modifier)

        is ReceiptScanKey -> ReceiptScanRoute(key = key, onNavigate = onNavigate, onBack = onBack, modifier = modifier)

        is ScanReviewKey -> ScanReviewRoute(key = key, onBack = onBack, modifier = modifier)

        // --- ai-foto lane: the fridge photo and Settings → AI ---
        is FridgeScanKey -> PlaceholderPage(title = stringResource(R.string.scan_fridge_title), onNavigateBack = onBack, modifier = modifier)

        AiSettingsKey -> PlaceholderPage(title = stringResource(R.string.scan_ai_settings_title), onNavigateBack = onBack, modifier = modifier)
    }
}
