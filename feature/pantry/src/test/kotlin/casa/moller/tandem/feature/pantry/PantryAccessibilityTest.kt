package casa.moller.tandem.feature.pantry

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.pantry.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.pantry.Devices.PHONE_TALL
import casa.moller.tandem.feature.pantry.Devices.PHONE_XTALL
import casa.moller.tandem.feature.pantry.Devices.TABLET
import casa.moller.tandem.feature.pantry.Devices.TABLET_PORTRAIT
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6/6.6) of every Hemma screen
 * and state: 48 dp targets, labels, contrast, light and dark, font scale 2, phone, phone in
 * landscape and tablet in both orientations.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-$PHONE_TALL")
class PantryAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hemma() = check(PantryShot.HEMMA)

    @Test
    @Config(qualifiers = "sv-$PHONE_XTALL")
    fun hemmaDarkFontScale2() = check(PantryShot.HEMMA, darkTheme = true, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun hemmaLandscape() = check(PantryShot.HEMMA)

    @Test
    @Config(qualifiers = TABLET)
    fun hemmaTablet() = check(PantryShot.HEMMA)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun hemmaTabletPortraitDark() = check(PantryShot.HEMMA, darkTheme = true)

    @Test
    fun hemmaEmpty() = check(PantryShot.HEMMA_EMPTY)

    @Test
    fun hemmaNoLocations() = check(PantryShot.HEMMA_NO_LOCATIONS)

    @Test
    fun hemmaGuest() = check(PantryShot.HEMMA_GUEST)

    @Test
    fun hemmaOfflineDark() = check(PantryShot.HEMMA_OFFLINE, darkTheme = true)

    @Test
    fun hemmaDemo() = check(PantryShot.HEMMA_FRIDGE_PHOTO, chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true))

    @Test
    fun hemmaLocation() = check(PantryShot.HEMMA_LOCATION)

    @Test
    fun hemmaMove() = check(PantryShot.HEMMA_MOVE)

    @Test
    fun entryNew() = check(PantryShot.ENTRY_NEW)

    @Test
    fun entryEditDarkFontScale2() = check(PantryShot.ENTRY_EDIT, darkTheme = true, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun entryTablet() = check(PantryShot.ENTRY_NEW)

    @Test
    fun unpack() = check(PantryShot.UNPACK)

    @Test
    fun unpackDarkFontScale2() = check(PantryShot.UNPACK, darkTheme = true, fontScale = 2f)

    @Test
    fun unpackEmpty() = check(PantryShot.UNPACK_EMPTY)

    @Test
    fun staples() = check(PantryShot.STAPLES)

    @Test
    fun staplesGuestDark() = check(PantryShot.STAPLES_GUEST, darkTheme = true)

    @Test
    fun makeNow() = check(PantryShot.MAKE_NOW)

    @Test
    fun makeNowMissingDarkFontScale2() = check(PantryShot.MAKE_NOW_MISSING, darkTheme = true, fontScale = 2f)

    @Test
    fun makeNowFirstTime() = check(PantryShot.MAKE_NOW_FIRST_TIME)

    @Test
    fun makeNowEmptyFiltered() = check(PantryShot.MAKE_NOW_EMPTY_FILTERED)

    @Test
    @Config(qualifiers = TABLET)
    fun makeNowTablet() = check(PantryShot.MAKE_NOW)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun makeNowTabletPortrait() = check(PantryShot.MAKE_NOW)

    // Fas S review (fix-ui): the tablet's columns at font scale 2 with every notice, and the
    // forms in landscape (compact bar; PantryLayoutTest covers the open keyboard).
    @Test
    @Config(qualifiers = TABLET)
    fun makeNowTabletCrowdedFontScale2() = check(PantryShot.MAKE_NOW_CROWDED, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun entryLandscape() = check(PantryShot.ENTRY_EDIT)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun staplesLandscape() = check(PantryShot.STAPLES)

    private fun check(shot: PantryShot, darkTheme: Boolean = false, fontScale: Float = 1f, chrome: AppChrome = AppChrome()) {
        composeRule.setContent {
            PantryTestTheme(darkTheme = darkTheme, fontScale = fontScale, chrome = chrome) { shot.content() }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
