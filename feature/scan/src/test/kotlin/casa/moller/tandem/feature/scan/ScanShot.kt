package casa.moller.tandem.feature.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.MatchedFood
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.feature.scan.barcode.BarcodeLookupState
import casa.moller.tandem.feature.scan.barcode.BarcodeRow
import casa.moller.tandem.feature.scan.barcode.BarcodeScanScreen
import casa.moller.tandem.feature.scan.barcode.BarcodeScanUiState
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.receipt.ReceiptProblem
import casa.moller.tandem.feature.scan.receipt.ReceiptScanScreen
import casa.moller.tandem.feature.scan.receipt.ReceiptScanUiState
import casa.moller.tandem.feature.scan.review.ReviewEdit
import casa.moller.tandem.feature.scan.review.ReviewEditLayout
import casa.moller.tandem.feature.scan.review.ScanReviewScreen
import casa.moller.tandem.feature.scan.review.ScanReviewUiState
import casa.moller.tandem.feature.scan.ui.FoodPickerUi

/**
 * Every scan screen and state with SYNTHETIC data, for the screenshot and accessibility tests (one
 * list, so both cover the same states). The camera preview is the placeholder: Robolectric has no
 * camera.
 */
internal enum class ScanShot(val content: @Composable () -> Unit) {
    BARCODE_EMPTY({ Barcode(ScanShots.barcode()) }),
    BARCODE_KNOWN({ Barcode(ScanShots.barcode(listOf(ScanShots.known))) }),
    BARCODE_SUGGESTIONS({ Barcode(ScanShots.barcode(listOf(ScanShots.product))) }),
    BARCODE_PICKER({ Barcode(ScanShots.barcode(listOf(ScanShots.notFound)).copy(picker = ScanShots.picker)) }),
    BARCODE_SEVERAL({ Barcode(ScanShots.barcode(listOf(ScanShots.lookingUp, ScanShots.product, ScanShots.known))) }),
    BARCODE_OFFLINE({ Barcode(ScanShots.barcode(listOf(ScanShots.offline))) }),
    BARCODE_CAMERA_NOT_ASKED({ Barcode(ScanShots.barcode(), CameraAccess.NOT_ASKED) }),
    BARCODE_CAMERA_BLOCKED({ Barcode(ScanShots.barcode(), CameraAccess.BLOCKED) }),
    BARCODE_GUEST({ Barcode(ScanShots.barcode(listOf(ScanShots.known)).copy(readOnly = true)) }),
    BARCODE_DEMO({ Barcode(ScanShots.barcode().copy(demo = true)) }),
    RECEIPT_START({ Receipt(ReceiptScanUiState()) }),
    RECEIPT_PAGES({ Receipt(ReceiptScanUiState(pages = 2)) }),
    RECEIPT_READING({ Receipt(ReceiptScanUiState(pages = 1, reading = true)) }),
    RECEIPT_NO_ITEMS({ Receipt(ReceiptScanUiState(pages = 1, problem = ReceiptProblem.NO_ITEMS)) }),
    RECEIPT_CAMERA_DENIED({ Receipt(ReceiptScanUiState(), CameraAccess.DENIED) }),
    RECEIPT_NO_CAMERA({ Receipt(ReceiptScanUiState(), CameraAccess.UNAVAILABLE) }),
    REVIEW({ Review(ScanShots.review()) }),
    REVIEW_FRIDGE({ Review(ScanShots.review(ScanSource.FRIDGE, ScanSamples.fridgeRows())) }),
    REVIEW_EDIT_PANE({ Review(ScanShots.review().copy(edit = ScanShots.edit)) }),
    REVIEW_EDIT_SHEET({ Sheet { ReviewEditLayout(edit = ScanShots.edit, state = ScanShots.review(), onAction = {}) } }),
    REVIEW_GUEST({ Review(ScanShots.review().copy(readOnly = true)) }),
    REVIEW_MISSING({ Review(ScanReviewUiState(loading = false, missing = true)) }),
}

@Composable
private fun Barcode(state: BarcodeScanUiState, access: CameraAccess = CameraAccess.GRANTED) =
    BarcodeScanScreen(state = state, camera = ScanCameraUi(access, torchAvailable = true), onAction = {}, onCamera = {}, onBack = {})

@Composable
private fun Receipt(state: ReceiptScanUiState, access: CameraAccess = CameraAccess.GRANTED) =
    ReceiptScanScreen(state = state, camera = ScanCameraUi(access), onAction = {}, onCamera = {}, onTakePhoto = {}, onBack = {})

@Composable
private fun Review(state: ScanReviewUiState) = ScanReviewScreen(state = state, onAction = {}, onBack = {})

/** A sheet that fills the window, so its scrolling part and pinned buttons show as on a device. */
@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) { content() }
    }
}

/** The states the shots show. */
internal object ScanShots {
    private fun item(key: String, code: String, food: MatchedFood? = null, text: String = code) = ScanReviewItem(
        key = key,
        text = text,
        source = ScanSource.BARCODE,
        food = food,
        barcode = code,
        learn = food?.via != FoodMatchVia.ALIAS,
    )

    val known = BarcodeRow(
        item = item("b1", ScanSamples.KNOWN_CODE, MatchedFood(110, "kikärtor", FoodMatchVia.ALIAS))
            .copy(expires = ExpiryChoice.PLUS_1_WEEK.dateFrom(ScanSamples.today)),
        lookup = BarcodeLookupState.KNOWN,
    )

    val product = BarcodeRow(
        item = item("b2", ScanSamples.PRODUCT_CODE, text = "Vispgrädde 36%"),
        lookup = BarcodeLookupState.PRODUCT,
        productName = "Vispgrädde 36% Exempelmejeriet",
        suggestions = listOf(ScanSamples.whippingCream, ScanSamples.cream),
    )

    val notFound = BarcodeRow(item = item("b3", ScanSamples.UNKNOWN_CODE), lookup = BarcodeLookupState.NOT_FOUND)

    val offline = BarcodeRow(item = item("b4", ScanSamples.UNKNOWN_CODE), lookup = BarcodeLookupState.OFFLINE)

    val lookingUp = BarcodeRow(item = item("b5", "2000000000046"), lookup = BarcodeLookupState.LOOKING_UP)

    val picker = FoodPickerUi(query = "gr", results = listOf(ScanSamples.cream, ScanSamples.whippingCream))

    fun barcode(rows: List<BarcodeRow> = emptyList()) = BarcodeScanUiState(
        loading = false,
        locations = SamplePantry.locations,
        defaultLocationId = SamplePantry.fridge.id,
        rows = rows,
        today = ScanSamples.today,
    )

    fun review(source: ScanSource = ScanSource.RECEIPT, rows: List<ScanReviewItem> = ScanSamples.receiptRows()) = ScanReviewUiState(
        loading = false,
        source = source,
        rows = rows,
        locations = SamplePantry.locations,
        defaultLocationId = SamplePantry.fridge.id,
        today = ScanSamples.today,
    )

    val edit = ReviewEdit(
        key = "r3",
        text = "TVÄTTMEDEL COLOR 1,5L",
        food = MatchedFood(112, "gurka", FoodMatchVia.MANUAL),
        amount = "1,5",
        unit = "l",
        learn = true,
        canLearn = true,
        picker = FoodPickerUi(query = "gu", results = listOf(ScanSamples.cucumber)),
    )
}
