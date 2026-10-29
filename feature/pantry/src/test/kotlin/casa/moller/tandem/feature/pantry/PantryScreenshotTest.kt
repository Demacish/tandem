package casa.moller.tandem.feature.pantry

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.pantry.Devices.PHONE
import casa.moller.tandem.feature.pantry.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.pantry.Devices.PHONE_TALL
import casa.moller.tandem.feature.pantry.Devices.TABLET
import casa.moller.tandem.feature.pantry.Devices.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hemma (Fas S, hemma-ui lane, plan §8.8, 6.5): the pantry on a phone (portrait, landscape), a
 * tablet in both orientations (places as columns), light and dark, Swedish and English, font scale
 * 2, empty, without places, guest, offline, demo; an entry, "Packa upp", "Basvaror" and "Kan laga
 * nu" (tabs on phones, groups side by side on tablets). Recorded in CI (Screenshots workflow).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-$PHONE")
class PantryScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hemma() = capture("phone_pantry_light_sv", PantryShot.HEMMA)

    @Test
    @Config(qualifiers = PHONE)
    fun hemmaEnglishDark() = capture("phone_pantry_dark", PantryShot.HEMMA, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun hemmaFontScale2() = capture("phone_pantry_font2_light_sv", PantryShot.HEMMA, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun hemmaLandscape() = capture("phone_landscape_pantry_light_sv", PantryShot.HEMMA)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun hemmaTablet() = capture("tablet_pantry_light_sv", PantryShot.HEMMA)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun hemmaTabletPortraitDark() = capture("tablet_portrait_pantry_dark_sv", PantryShot.HEMMA, darkTheme = true)

    @Test
    fun hemmaEmpty() = capture("phone_pantry_empty_light_sv", PantryShot.HEMMA_EMPTY)

    @Test
    fun hemmaNoLocations() = capture("phone_pantry_no_locations_light_sv", PantryShot.HEMMA_NO_LOCATIONS)

    @Test
    fun hemmaGuest() = capture("phone_pantry_guest_light_sv", PantryShot.HEMMA_GUEST)

    @Test
    fun hemmaOffline() = capture("phone_pantry_offline_dark_sv", PantryShot.HEMMA_OFFLINE, darkTheme = true)

    @Test
    fun hemmaDemo() = capture(
        "phone_pantry_demo_light_sv",
        PantryShot.HEMMA_FRIDGE_PHOTO,
        chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true),
    )

    @Test
    fun hemmaLocation() = capture("phone_pantry_location_light_sv", PantryShot.HEMMA_LOCATION)

    @Test
    fun hemmaMove() = capture("phone_pantry_move_light_sv", PantryShot.HEMMA_MOVE)

    @Test
    fun hemmaNewPlace() = capture("phone_pantry_new_place_light_sv", PantryShot.HEMMA_NEW_PLACE)

    @Test
    fun entryNew() = capture("phone_pantry_entry_new_light_sv", PantryShot.ENTRY_NEW)

    @Test
    @Config(qualifiers = PHONE)
    fun entryEditEnglishDark() = capture("phone_pantry_entry_edit_dark", PantryShot.ENTRY_EDIT, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun entryFontScale2() = capture("phone_pantry_entry_font2_light_sv", PantryShot.ENTRY_EDIT, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun entryTablet() = capture("tablet_pantry_entry_light_sv", PantryShot.ENTRY_NEW)

    @Test
    fun unpack() = capture("phone_unpack_light_sv", PantryShot.UNPACK)

    @Test
    @Config(qualifiers = PHONE)
    fun unpackEnglishDark() = capture("phone_unpack_dark", PantryShot.UNPACK, darkTheme = true)

    @Test
    fun unpackEmpty() = capture("phone_unpack_empty_light_sv", PantryShot.UNPACK_EMPTY)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun unpackTabletPortrait() = capture("tablet_portrait_unpack_light_sv", PantryShot.UNPACK)

    @Test
    fun staples() = capture("phone_staples_light_sv", PantryShot.STAPLES)

    @Test
    @Config(qualifiers = PHONE)
    fun staplesEnglishFontScale2() = capture("phone_staples_font2_light", PantryShot.STAPLES, fontScale = 2f)

    @Test
    fun staplesGuest() = capture("phone_staples_guest_dark_sv", PantryShot.STAPLES_GUEST, darkTheme = true)

    @Test
    fun makeNow() = capture("phone_make_now_light_sv", PantryShot.MAKE_NOW)

    @Test
    @Config(qualifiers = PHONE)
    fun makeNowEnglishDark() = capture("phone_make_now_dark", PantryShot.MAKE_NOW, darkTheme = true)

    @Test
    fun makeNowMissing() = capture("phone_make_now_missing_light_sv", PantryShot.MAKE_NOW_MISSING)

    @Test
    fun makeNowFirstTime() = capture("phone_make_now_first_time_light_sv", PantryShot.MAKE_NOW_FIRST_TIME)

    @Test
    fun makeNowEmptyFiltered() = capture("phone_make_now_empty_filtered_light_sv", PantryShot.MAKE_NOW_EMPTY_FILTERED)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun makeNowFontScale2() = capture("phone_make_now_font2_light_sv", PantryShot.MAKE_NOW_MISSING, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun makeNowLandscape() = capture("phone_landscape_make_now_light_sv", PantryShot.MAKE_NOW)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun makeNowTablet() = capture("tablet_make_now_light_sv", PantryShot.MAKE_NOW)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun makeNowTabletPortraitDark() = capture("tablet_portrait_make_now_dark_sv", PantryShot.MAKE_NOW, darkTheme = true)

    private fun capture(
        name: String,
        shot: PantryShot,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        chrome: AppChrome = AppChrome(),
    ) {
        composeRule.setContent {
            PantryTestTheme(darkTheme = darkTheme, fontScale = fontScale, chrome = chrome) { shot.content() }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/pantry/$name.png")
    }
}
