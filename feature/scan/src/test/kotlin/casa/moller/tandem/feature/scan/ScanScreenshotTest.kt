package casa.moller.tandem.feature.scan

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.scan.Devices.PHONE
import casa.moller.tandem.feature.scan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.scan.Devices.PHONE_TALL
import casa.moller.tandem.feature.scan.Devices.TABLET
import casa.moller.tandem.feature.scan.Devices.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scanners and the shared review list (Fas S skanning lane, plan §8.8, §6.5): the barcode
 * scanner (empty, known, suggestions, picker, several, offline, camera not asked / blocked, guest,
 * demo), the receipt scanner (start, photos, reading, no items, camera denied, no camera) and the
 * review (receipt, fridge photo with the AI reminder, the edit as a sheet and as a pane, guest,
 * lost session) on a phone (portrait, landscape), a tablet in both orientations, light and dark,
 * English and Swedish, font scale 2. The camera preview is a placeholder. Recorded in CI
 * (Screenshots workflow).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ScanScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun barcodeEmpty() = capture("phone_scan_barcode_empty_light", ScanShot.BARCODE_EMPTY)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeEmptySwedish() = capture("phone_scan_barcode_empty_light_sv", ScanShot.BARCODE_EMPTY)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeKnown() = capture("phone_scan_barcode_known_light_sv", ScanShot.BARCODE_KNOWN)

    @Test
    fun barcodeKnownDark() = capture("phone_scan_barcode_known_dark", ScanShot.BARCODE_KNOWN, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun barcodeKnownFontScale2() = capture("phone_scan_barcode_known_font2_light", ScanShot.BARCODE_KNOWN, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeSuggestions() = capture("phone_scan_barcode_suggestions_light_sv", ScanShot.BARCODE_SUGGESTIONS)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodePicker() = capture("phone_scan_barcode_picker_light_sv", ScanShot.BARCODE_PICKER)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeSeveral() = capture("phone_scan_barcode_several_light_sv", ScanShot.BARCODE_SEVERAL)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeOffline() = capture("phone_scan_barcode_offline_dark_sv", ScanShot.BARCODE_OFFLINE, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeCameraNotAsked() = capture("phone_scan_barcode_camera_light_sv", ScanShot.BARCODE_CAMERA_NOT_ASKED)

    @Test
    fun barcodeCameraBlocked() = capture("phone_scan_barcode_camera_blocked_dark", ScanShot.BARCODE_CAMERA_BLOCKED, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeGuest() = capture("phone_scan_barcode_guest_light_sv", ScanShot.BARCODE_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun barcodeDemo() = capture("phone_scan_barcode_demo_light_sv", ScanShot.BARCODE_DEMO)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun barcodeLandscape() = capture("phone_landscape_scan_barcode_known_light", ScanShot.BARCODE_KNOWN)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun barcodeTablet() = capture("tablet_scan_barcode_several_light_sv", ScanShot.BARCODE_SEVERAL)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun barcodeTabletPortrait() = capture("tablet_portrait_scan_barcode_known_dark", ScanShot.BARCODE_KNOWN, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun receiptStart() = capture("phone_scan_receipt_start_light_sv", ScanShot.RECEIPT_START)

    @Test
    fun receiptStartEnglish() = capture("phone_scan_receipt_start_light", ScanShot.RECEIPT_START)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun receiptPages() = capture("phone_scan_receipt_pages_light_sv", ScanShot.RECEIPT_PAGES)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun receiptReading() = capture("phone_scan_receipt_reading_dark_sv", ScanShot.RECEIPT_READING, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun receiptNoItems() = capture("phone_scan_receipt_no_items_light_sv", ScanShot.RECEIPT_NO_ITEMS)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun receiptCameraDenied() = capture("phone_scan_receipt_camera_denied_light_sv", ScanShot.RECEIPT_CAMERA_DENIED)

    @Test
    fun receiptNoCamera() = capture("phone_scan_receipt_no_camera_dark", ScanShot.RECEIPT_NO_CAMERA, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun receiptFontScale2() = capture("phone_scan_receipt_pages_font2_light", ScanShot.RECEIPT_PAGES, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun receiptLandscape() = capture("phone_landscape_scan_receipt_pages_dark", ScanShot.RECEIPT_PAGES, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun receiptTablet() = capture("tablet_scan_receipt_start_light_sv", ScanShot.RECEIPT_START)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun receiptTabletPortrait() = capture("tablet_portrait_scan_receipt_pages_light", ScanShot.RECEIPT_PAGES)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun review() = capture("phone_scan_review_light_sv", ScanShot.REVIEW)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun reviewEnglish() = capture("phone_scan_review_light", ScanShot.REVIEW)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun reviewDark() = capture("phone_scan_review_dark_sv", ScanShot.REVIEW, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun reviewFontScale2() = capture("phone_scan_review_font2_light_sv", ScanShot.REVIEW, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun reviewFridge() = capture("phone_scan_review_fridge_light_sv", ScanShot.REVIEW_FRIDGE)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun reviewEditSheet() = capture("phone_scan_review_edit_light_sv", ScanShot.REVIEW_EDIT_SHEET)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun reviewEditSheetDarkFontScale2() =
        capture("phone_scan_review_edit_font2_dark", ScanShot.REVIEW_EDIT_SHEET, darkTheme = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun reviewGuest() = capture("phone_scan_review_guest_light_sv", ScanShot.REVIEW_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun reviewMissing() = capture("phone_scan_review_missing_light_sv", ScanShot.REVIEW_MISSING)

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun reviewLandscape() = capture("phone_landscape_scan_review_light_sv", ScanShot.REVIEW)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun reviewTablet() = capture("tablet_scan_review_edit_light_sv", ScanShot.REVIEW_EDIT_PANE)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun reviewTabletPortrait() = capture("tablet_portrait_scan_review_dark_sv", ScanShot.REVIEW, darkTheme = true)

    private fun capture(name: String, shot: ScanShot, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent { ScanTestTheme(darkTheme = darkTheme, fontScale = fontScale) { shot.content() } }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/scan/$name.png")
    }
}
