package casa.moller.tandem.feature.scan

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.feature.scan.Devices.PHONE_TALL
import casa.moller.tandem.feature.scan.barcode.BARCODE_PRIMARY_TAG
import casa.moller.tandem.feature.scan.barcode.BARCODE_SHOPPING_TAG
import casa.moller.tandem.feature.scan.barcode.BarcodeScanAction
import casa.moller.tandem.feature.scan.barcode.BarcodeScanScreen
import casa.moller.tandem.feature.scan.barcode.BarcodeScanUiState
import casa.moller.tandem.feature.scan.barcode.ScanCameraAction
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.receipt.RECEIPT_DONE_TAG
import casa.moller.tandem.feature.scan.receipt.RECEIPT_SHUTTER_TAG
import casa.moller.tandem.feature.scan.receipt.ReceiptScanAction
import casa.moller.tandem.feature.scan.receipt.ReceiptScanScreen
import casa.moller.tandem.feature.scan.receipt.ReceiptScanUiState
import casa.moller.tandem.feature.scan.review.SCAN_REVIEW_SAVE_TAG
import casa.moller.tandem.feature.scan.review.ScanReviewAction
import casa.moller.tandem.feature.scan.review.ScanReviewScreen
import casa.moller.tandem.feature.scan.review.ScanReviewUiState
import casa.moller.tandem.feature.scan.review.scanReviewRowTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The scan screens' wiring: buttons send the right actions, rows are single TalkBack items, guests see no save. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE_TALL")
class ScanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun barcode(
        state: BarcodeScanUiState,
        access: CameraAccess = CameraAccess.GRANTED,
    ): Pair<MutableList<BarcodeScanAction>, MutableList<ScanCameraAction>> {
        val actions = mutableListOf<BarcodeScanAction>()
        val camera = mutableListOf<ScanCameraAction>()
        composeRule.setContent {
            ScanTestTheme {
                BarcodeScanScreen(
                    state = state,
                    camera = ScanCameraUi(access),
                    onAction = { actions += it },
                    onCamera = { camera += it },
                    onBack = {},
                )
            }
        }
        return actions to camera
    }

    @Test
    fun oneKnownPackageGoesStraightIntoItsPlace() {
        val (actions, _) = barcode(ScanShots.barcode(listOf(ScanShots.known)))
        composeRule.onNodeWithText("Lägg i Kyl").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Frys").performClick()
        composeRule.onNodeWithText("+1 månad").performClick()
        composeRule.onNodeWithTag(BARCODE_SHOPPING_TAG).performClick()
        assertEquals(
            listOf(
                BarcodeScanAction.SaveToPantry,
                BarcodeScanAction.SetLocation("b1", 2),
                BarcodeScanAction.SetExpiry("b1", ExpiryChoice.PLUS_1_MONTH),
                BarcodeScanAction.AddToShoppingList,
            ),
            actions,
        )
    }

    @Test
    fun severalPackagesAreReviewed() {
        val (actions, _) = barcode(ScanShots.barcode(listOf(ScanShots.product, ScanShots.known)))
        composeRule.onNodeWithText("Granska 2 varor").performClick()
        composeRule.onNodeWithText("vispgrädde").performClick()
        assertEquals(listOf(BarcodeScanAction.Review, BarcodeScanAction.ChooseFood("b2", ScanSamples.whippingCream)), actions)
    }

    @Test
    fun anUnmatchedPackageCannotBeSavedYet() {
        barcode(ScanShots.barcode(listOf(ScanShots.notFound)))
        composeRule.onNodeWithTag(BARCODE_PRIMARY_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(BARCODE_SHOPPING_TAG).assertIsNotEnabled()
    }

    @Test
    fun theCameraIsAskedForInContextAndThePickerAlwaysWorks() {
        val (_, camera) = barcode(ScanShots.barcode(), CameraAccess.NOT_ASKED)
        composeRule.onNodeWithText("Tillåt kameran").performClick()
        // In the camera area and below the panel: both open the picker.
        composeRule.onAllNodesWithText("Välj en bild från galleriet")[0].performClick()
        composeRule.onAllNodesWithText("Välj en bild från galleriet")[1].performClick()
        assertEquals(listOf(ScanCameraAction.ALLOW, ScanCameraAction.PICK_IMAGE, ScanCameraAction.PICK_IMAGE), camera)
    }

    @Test
    fun aGuestScansButHasNoSaveButtons() {
        barcode(ScanShots.barcode(listOf(ScanShots.known)).copy(readOnly = true))
        composeRule.onNodeWithTag(BARCODE_PRIMARY_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Frys").assertDoesNotExist()
    }

    @Test
    fun theReceiptScannerTakesPhotosAndReadsThem() {
        val actions = mutableListOf<ReceiptScanAction>()
        var photos = 0
        composeRule.setContent {
            ScanTestTheme {
                ReceiptScanScreen(
                    state = ReceiptScanUiState(pages = 2),
                    camera = ScanCameraUi(CameraAccess.GRANTED),
                    onAction = { actions += it },
                    onCamera = {},
                    onTakePhoto = { photos++ },
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("2 foton lästa").assertExists()
        composeRule.onNodeWithTag(RECEIPT_SHUTTER_TAG).performClick()
        composeRule.onNodeWithTag(RECEIPT_DONE_TAG).performClick()
        composeRule.onNodeWithText("Ta bort det senaste fotot").performClick()
        assertEquals(1, photos)
        assertEquals(listOf(ReceiptScanAction.Done, ReceiptScanAction.RemoveLastPage), actions)
    }

    private fun review(state: ScanReviewUiState): MutableList<ScanReviewAction> {
        val actions = mutableListOf<ScanReviewAction>()
        composeRule.setContent { ScanTestTheme { ScanReviewScreen(state = state, onAction = { actions += it }, onBack = {}) } }
        return actions
    }

    @Test
    fun aReviewRowIsOneTalkBackItemThatOpensTheEdit() {
        val actions = review(ScanShots.review())
        val row = composeRule.onNodeWithTag(scanReviewRowTag("r1"), useUnmergedTree = true)
        val description = row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertTrue(description, description!!.startsWith("grädde, 5 dl, Kyl, går ut 5 nov"))
        row.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(SCAN_REVIEW_SAVE_TAG).performClick()
        assertEquals(listOf(ScanReviewAction.Edit("r1"), ScanReviewAction.Save), actions)
    }

    @Test
    fun nothingReadyNothingToSave() {
        review(ScanShots.review(rows = ScanSamples.receiptRows().map { it.copy(include = false) }))
        composeRule.onNodeWithTag(SCAN_REVIEW_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun aGuestReviewHasNoSave() {
        review(ScanShots.review().copy(readOnly = true))
        composeRule.onNodeWithTag(SCAN_REVIEW_SAVE_TAG).assertDoesNotExist()
    }
}
