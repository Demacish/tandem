package casa.moller.tandem.feature.scan

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.scan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.scan.Devices.PHONE_TALL
import casa.moller.tandem.feature.scan.Devices.TABLET
import casa.moller.tandem.feature.scan.Devices.TABLET_PORTRAIT
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6/6.6) of every scan screen and
 * state: 48 dp targets, labels, contrast, light and dark, font scale 2, phone, phone in landscape
 * and tablet in both orientations.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class ScanAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun barcodeEmpty() = check(ScanShot.BARCODE_EMPTY)

    @Test
    fun barcodeKnown() = check(ScanShot.BARCODE_KNOWN)

    @Test
    fun barcodeSuggestions() = check(ScanShot.BARCODE_SUGGESTIONS)

    @Test
    fun barcodePicker() = check(ScanShot.BARCODE_PICKER)

    @Test
    fun barcodeSeveral() = check(ScanShot.BARCODE_SEVERAL)

    @Test
    fun barcodeOffline() = check(ScanShot.BARCODE_OFFLINE)

    @Test
    fun barcodeCameraNotAsked() = check(ScanShot.BARCODE_CAMERA_NOT_ASKED)

    @Test
    fun barcodeCameraBlocked() = check(ScanShot.BARCODE_CAMERA_BLOCKED)

    @Test
    fun barcodeGuest() = check(ScanShot.BARCODE_GUEST)

    @Test
    fun barcodeDemo() = check(ScanShot.BARCODE_DEMO)

    @Test
    fun receiptStart() = check(ScanShot.RECEIPT_START)

    @Test
    fun receiptPages() = check(ScanShot.RECEIPT_PAGES)

    @Test
    fun receiptReading() = check(ScanShot.RECEIPT_READING)

    @Test
    fun receiptNoItems() = check(ScanShot.RECEIPT_NO_ITEMS)

    @Test
    fun receiptCameraDenied() = check(ScanShot.RECEIPT_CAMERA_DENIED)

    @Test
    fun receiptNoCamera() = check(ScanShot.RECEIPT_NO_CAMERA)

    @Test
    fun review() = check(ScanShot.REVIEW)

    @Test
    fun reviewFridge() = check(ScanShot.REVIEW_FRIDGE)

    @Test
    fun reviewEditSheet() = check(ScanShot.REVIEW_EDIT_SHEET)

    @Test
    fun reviewGuest() = check(ScanShot.REVIEW_GUEST)

    @Test
    fun reviewMissing() = check(ScanShot.REVIEW_MISSING)

    @Test
    fun barcodeKnownDarkFontScale2() = check(ScanShot.BARCODE_KNOWN, darkTheme = true, fontScale = 2f)

    @Test
    fun barcodeCameraNotAskedFontScale2() = check(ScanShot.BARCODE_CAMERA_NOT_ASKED, fontScale = 2f)

    @Test
    fun receiptPagesDarkFontScale2() = check(ScanShot.RECEIPT_PAGES, darkTheme = true, fontScale = 2f)

    @Test
    fun reviewDarkFontScale2() = check(ScanShot.REVIEW, darkTheme = true, fontScale = 2f)

    @Test
    fun reviewEditSheetFontScale2() = check(ScanShot.REVIEW_EDIT_SHEET, fontScale = 2f)

    @Test
    fun reviewFridgeDark() = check(ScanShot.REVIEW_FRIDGE, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun barcodeLandscape() = check(ScanShot.BARCODE_KNOWN)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun receiptLandscape() = check(ScanShot.RECEIPT_PAGES)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun reviewLandscape() = check(ScanShot.REVIEW)

    @Test
    @Config(qualifiers = TABLET)
    fun barcodeTablet() = check(ScanShot.BARCODE_SEVERAL)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun barcodeTabletPortraitDark() = check(ScanShot.BARCODE_KNOWN, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun receiptTablet() = check(ScanShot.RECEIPT_START)

    @Test
    @Config(qualifiers = TABLET)
    fun reviewEditPaneTablet() = check(ScanShot.REVIEW_EDIT_PANE)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun reviewTabletPortrait() = check(ScanShot.REVIEW)

    private fun check(shot: ScanShot, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent { ScanTestTheme(darkTheme = darkTheme, fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
