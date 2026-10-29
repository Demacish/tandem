package casa.moller.tandem.feature.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.placeholder.PlaceholderPage
import casa.moller.tandem.feature.scan.engine.BarcodeEngine
import casa.moller.tandem.feature.scan.engine.ReceiptTextEngine
import casa.moller.tandem.feature.scan.navigation.AiSettingsKey
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.scan.navigation.FridgeScanKey
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.navigation.ScanDestination
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey

// Fas S (grund lane): the ONE route feature:shell shows for every [ScanDestination]. The skanning
// lane replaces the barcode, receipt and review branches, the ai-foto lane the fridge photo and
// Settings → AI. The engines are created (lazily, nothing runs until a scan) by their screens.

/**
 * @param onNavigate opens another destination (the review after a scan, Settings → AI).
 * @param onBack closes the page.
 *
 * `onNavigate` is unused until the lanes build the screens (every branch is a placeholder now).
 */
@Suppress("UnusedParameter")
@Composable
fun ScanRoute(key: ScanDestination, onNavigate: (NavKey) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    when (key) {
        is BarcodeScanKey -> {
            val engine = remember { BarcodeEngine() }
            DisposableEffect(engine) { onDispose { engine.close() } }
        }

        is ReceiptScanKey -> {
            val engine = remember { ReceiptTextEngine() }
            DisposableEffect(engine) { onDispose { engine.close() } }
        }

        is FridgeScanKey, is ScanReviewKey, AiSettingsKey -> Unit
    }
    val title = when (key) {
        is BarcodeScanKey -> R.string.scan_barcode_title
        is ReceiptScanKey -> R.string.scan_receipt_title
        is FridgeScanKey -> R.string.scan_fridge_title
        is ScanReviewKey -> R.string.scan_review_title
        AiSettingsKey -> R.string.scan_ai_settings_title
    }
    PlaceholderPage(title = stringResource(title), onNavigateBack = onBack, modifier = modifier)
}
