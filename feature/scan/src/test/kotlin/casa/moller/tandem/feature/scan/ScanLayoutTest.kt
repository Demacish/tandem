package casa.moller.tandem.feature.scan

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.scan.Devices.PHONE
import casa.moller.tandem.feature.scan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.scan.barcode.BARCODE_PRIMARY_TAG
import casa.moller.tandem.feature.scan.barcode.BarcodeScanScreen
import casa.moller.tandem.feature.scan.barcode.BarcodeScanUiState
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.receipt.ReceiptScanScreen
import casa.moller.tandem.feature.scan.receipt.ReceiptScanUiState
import casa.moller.tandem.feature.scan.ui.SCAN_FOOD_SEARCH_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas S review (fix-ui): the scanners on a normal phone (not the tall test phone) at font scale 2
 * and in landscape: "Tillåt kameran" stays reachable in the fixed camera area, the pinned buttons
 * step aside for the food picker, and a package scanned again shows its count.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE")
class ScanLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun barcode(state: BarcodeScanUiState, access: CameraAccess = CameraAccess.GRANTED, fontScale: Float = 1f) {
        composeRule.setContent {
            ScanTestTheme(fontScale = fontScale) {
                BarcodeScanScreen(state = state, camera = ScanCameraUi(access), onAction = {}, onCamera = {}, onBack = {})
            }
        }
    }

    @Test
    fun allowTheCameraIsReachableAtFontScale2() {
        barcode(ScanShots.barcode(), CameraAccess.NOT_ASKED, fontScale = 2f)
        composeRule.onNodeWithText("Tillåt kameran").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun openSettingsIsReachableInLandscapeAtFontScale2() {
        barcode(ScanShots.barcode(), CameraAccess.BLOCKED, fontScale = 2f)
        composeRule.onNodeWithText("Öppna inställningarna").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theReceiptCameraPanelScrollsAtFontScale2() {
        composeRule.setContent {
            ScanTestTheme(fontScale = 2f) {
                ReceiptScanScreen(
                    state = ReceiptScanUiState(),
                    camera = ScanCameraUi(CameraAccess.DENIED),
                    onAction = {},
                    onCamera = {},
                    onTakePhoto = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Tillåt kameran").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun thePinnedButtonsStepAsideForTheFoodPicker() {
        barcode(ScanShots.barcode(listOf(ScanShots.notFound)).copy(picker = ScanShots.picker))
        composeRule.onNodeWithTag(BARCODE_PRIMARY_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SCAN_FOOD_SEARCH_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPackageScannedAgainShowsItsCount() {
        val counted = ScanShots.known.copy(item = ScanShots.known.item.copy(amount = 3.0))
        barcode(ScanShots.barcode(listOf(counted)))
        composeRule.onNode(hasContentDescription("3 st", substring = true)).assertExists()
    }
}
