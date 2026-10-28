package casa.moller.tandem.feature.shopping

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.shopping.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shopping.Devices.PHONE_TALL
import casa.moller.tandem.feature.shopping.Devices.TABLET
import casa.moller.tandem.feature.shopping.Devices.TABLET_PORTRAIT
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6/6.6) of every Handla screen
 * and state: 48 dp targets (64 dp rows in store mode), labels, contrast, light and dark, font
 * scale 2, phone, phone in landscape and tablet in both orientations.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class ShoppingAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun list() = check(ShoppingShot.LIST)

    @Test
    fun listDark() = check(ShoppingShot.LIST, darkTheme = true)

    @Test
    fun listFontScale2() = check(ShoppingShot.LIST, fontScale = 2f)

    @Test
    fun listCheckedOpen() = check(ShoppingShot.LIST_CHECKED_OPEN)

    @Test
    fun listEmpty() = check(ShoppingShot.LIST_EMPTY)

    @Test
    fun listOfflineDark() = check(ShoppingShot.LIST_OFFLINE, darkTheme = true)

    @Test
    fun listPaused() = check(ShoppingShot.LIST_PAUSED)

    @Test
    fun listBreaker() = check(ShoppingShot.LIST_BREAKER)

    @Test
    fun listGuest() = check(ShoppingShot.LIST_GUEST)

    @Test
    fun listNoHousehold() = check(ShoppingShot.LIST_NO_HOUSEHOLD)

    @Test
    fun listShared() = check(ShoppingShot.LIST_SHARED)

    @Test
    fun listSharedManyDarkFontScale2() = check(ShoppingShot.LIST_SHARED_MANY, darkTheme = true, fontScale = 2f)

    @Test
    fun listDemo() = check(ShoppingShot.LIST, chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true))

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun listPhoneLandscape() = check(ShoppingShot.LIST)

    @Test
    @Config(qualifiers = TABLET)
    fun listTablet() = check(ShoppingShot.LIST)

    @Test
    @Config(qualifiers = TABLET)
    fun listTabletDark() = check(ShoppingShot.LIST, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun listTabletPortrait() = check(ShoppingShot.LIST)

    @Test
    fun add() = check(ShoppingShot.ADD)

    @Test
    fun addRecentDark() = check(ShoppingShot.ADD_RECENT, darkTheme = true)

    @Test
    fun edit() = check(ShoppingShot.EDIT)

    @Test
    fun editOfflineFoodDark() = check(ShoppingShot.EDIT_OFFLINE_FOOD, darkTheme = true)

    @Test
    fun storeMode() = check(ShoppingShot.STORE_MODE)

    @Test
    fun storeModeDark() = check(ShoppingShot.STORE_MODE, darkTheme = true)

    @Test
    fun storeModeFontScale2() = check(ShoppingShot.STORE_MODE, fontScale = 2f)

    @Test
    fun storeModeDone() = check(ShoppingShot.STORE_MODE_DONE)

    @Test
    fun storeChoice() = check(ShoppingShot.STORE_CHOICE)

    @Test
    fun storeChoiceEmpty() = check(ShoppingShot.STORE_CHOICE_EMPTY)

    @Test
    fun history() = check(ShoppingShot.HISTORY)

    @Test
    fun historyEmpty() = check(ShoppingShot.HISTORY_EMPTY)

    // Phase 2 (koppling lane): "Lägg på inköpslistan" (#12) and removing a recipe from the list.

    @Test
    fun recipeSheet() = check(ShoppingShot.RECIPE_SHEET)

    @Test
    fun recipeSheetDarkFontScale2() = check(ShoppingShot.RECIPE_SHEET, darkTheme = true, fontScale = 2f)

    @Test
    fun recipeSheetLists() = check(ShoppingShot.RECIPE_SHEET_LISTS)

    @Test
    fun recipeButton() = check(ShoppingShot.RECIPE_BUTTON, fontScale = 2f)

    @Test
    fun editFromRecipe() = check(ShoppingShot.EDIT_FROM_RECIPE)

    private fun check(shot: ShoppingShot, darkTheme: Boolean = false, fontScale: Float = 1f, chrome: AppChrome = AppChrome()) {
        composeRule.setContent {
            ShoppingTestTheme(darkTheme = darkTheme, fontScale = fontScale, chrome = chrome) { shot.content() }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
