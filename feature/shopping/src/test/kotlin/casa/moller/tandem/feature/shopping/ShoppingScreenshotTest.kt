package casa.moller.tandem.feature.shopping

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.shopping.Devices.PHONE
import casa.moller.tandem.feature.shopping.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.shopping.Devices.PHONE_TALL
import casa.moller.tandem.feature.shopping.Devices.TABLET
import casa.moller.tandem.feature.shopping.Devices.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Handla tab (plan 6.2, 6.5; Stitch ink_pslista_*, butiksl_ge, ink_pslista_handla_surfplatta):
 * the list on a phone (portrait, landscape), a tablet in both orientations, light and dark,
 * English and Swedish, font scale 2, empty, offline, paused, guest and demo; the add and edit
 * sheets; store mode; the store choice and the history. Recorded in CI (Screenshots workflow).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ShoppingScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun phone() = capture("phone_shopping_light", ShoppingShot.LIST)

    @Test
    fun phoneDark() = capture("phone_shopping_dark", ShoppingShot.LIST, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSwedish() = capture("phone_shopping_light_sv", ShoppingShot.LIST)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun phoneCheckedOpen() = capture("phone_shopping_checked_open_light_sv", ShoppingShot.LIST_CHECKED_OPEN)

    @Test
    fun phoneFontScale2() = capture("phone_shopping_font2_light", ShoppingShot.LIST, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneEmpty() = capture("phone_shopping_empty_light_sv", ShoppingShot.LIST_EMPTY)

    @Test
    fun phoneEmptyDark() = capture("phone_shopping_empty_dark", ShoppingShot.LIST_EMPTY, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneOffline() = capture("phone_shopping_offline_dark_sv", ShoppingShot.LIST_OFFLINE, darkTheme = true)

    @Test
    fun phonePaused() = capture("phone_shopping_paused_light", ShoppingShot.LIST_PAUSED)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneBreaker() = capture("phone_shopping_breaker_light_sv", ShoppingShot.LIST_BREAKER)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneGuest() = capture("phone_shopping_guest_light_sv", ShoppingShot.LIST_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneDemo() = capture(
        "phone_shopping_demo_light_sv",
        ShoppingShot.LIST,
        chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true),
    )

    @Test
    fun phoneOneList() = capture("phone_shopping_one_list_light", ShoppingShot.LIST_ONE_LIST)

    /** 0.3.0 review (fix-data, plan 8.6): no household, so the list is not shared. */
    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneNoHousehold() = capture("phone_shopping_no_household_light_sv", ShoppingShot.LIST_NO_HOUSEHOLD)

    /** 0.3.0 komplettering (plan 8.6): "Delad med: …" at the top of the list. */
    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneShared() = capture("phone_shopping_shared_light_sv", ShoppingShot.LIST_SHARED)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletSharedDark() = capture("tablet_shopping_shared_dark", ShoppingShot.LIST_SHARED_MANY, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_shopping_light", ShoppingShot.LIST)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = capture("tablet_portrait_shopping_light", ShoppingShot.LIST)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun tabletPortraitDark() = capture("tablet_portrait_shopping_dark_sv", ShoppingShot.LIST, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = capture("tablet_shopping_light", ShoppingShot.LIST)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletDark() = capture("tablet_shopping_dark", ShoppingShot.LIST, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletSwedishGuest() = capture("tablet_shopping_guest_light_sv", ShoppingShot.LIST_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun addSheet() = capture("phone_shopping_add_light_sv", ShoppingShot.ADD)

    @Test
    fun addSheetRecentDark() = capture("phone_shopping_add_recent_dark", ShoppingShot.ADD_RECENT, darkTheme = true)

    @Test
    fun addSheetFontScale2() = capture("phone_shopping_add_font2_light", ShoppingShot.ADD, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun editSheet() = capture("phone_shopping_edit_light_sv", ShoppingShot.EDIT)

    @Test
    fun editSheetOfflineFood() = capture("phone_shopping_edit_offline_dark", ShoppingShot.EDIT_OFFLINE_FOOD, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun storeMode() = capture("phone_store_mode_light_sv", ShoppingShot.STORE_MODE)

    @Test
    fun storeModeDark() = capture("phone_store_mode_dark", ShoppingShot.STORE_MODE, darkTheme = true)

    @Test
    fun storeModeFontScale2() = capture("phone_store_mode_font2_light", ShoppingShot.STORE_MODE, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun storeModeTablet() = capture("tablet_store_mode_light", ShoppingShot.STORE_MODE)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun storeModeDone() = capture("phone_store_mode_done_light_sv", ShoppingShot.STORE_MODE_DONE)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun storeChoice() = capture("phone_store_choice_light_sv", ShoppingShot.STORE_CHOICE)

    @Test
    fun storeChoiceEmptyDark() = capture("phone_store_choice_empty_dark", ShoppingShot.STORE_CHOICE_EMPTY, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun history() = capture("phone_history_light_sv", ShoppingShot.HISTORY)

    @Test
    fun historyEmpty() = capture("phone_history_empty_light", ShoppingShot.HISTORY_EMPTY)

    // Phase 2 (koppling lane): "Lägg på inköpslistan" (#12) and removing a recipe from the list.

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun recipeSheet() = capture("phone_recipe_shopping_light_sv", ShoppingShot.RECIPE_SHEET)

    @Test
    fun recipeSheetDark() = capture("phone_recipe_shopping_dark", ShoppingShot.RECIPE_SHEET, darkTheme = true)

    @Test
    fun recipeSheetFontScale2() = capture("phone_recipe_shopping_font2_light", ShoppingShot.RECIPE_SHEET, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun recipeSheetLists() = capture("phone_recipe_shopping_lists_light_sv", ShoppingShot.RECIPE_SHEET_LISTS)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun recipeSheetLandscape() = capture("phone_landscape_recipe_shopping_light", ShoppingShot.RECIPE_SHEET)

    @Test
    @Config(qualifiers = TABLET)
    fun recipeSheetTablet() = capture("tablet_recipe_shopping_light", ShoppingShot.RECIPE_SHEET)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun recipeSheetTabletPortraitDark() = capture("tablet_portrait_recipe_shopping_dark_sv", ShoppingShot.RECIPE_SHEET, darkTheme = true)

    // 0.3.0 komplettering: "Lägg till n varor" pinned below the scrolling rows (phone portrait and landscape).

    @Test
    fun recipeSheetPinned() = capture("phone_recipe_shopping_pinned_light", ShoppingShot.RECIPE_SHEET_PINNED)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun recipeSheetPinnedLandscape() =
        capture("phone_landscape_recipe_shopping_pinned_dark", ShoppingShot.RECIPE_SHEET_PINNED, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun recipeButton() = capture("phone_recipe_shopping_button_light_sv", ShoppingShot.RECIPE_BUTTON)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun editFromRecipe() = capture("phone_shopping_edit_from_recipe_light_sv", ShoppingShot.EDIT_FROM_RECIPE)

    // Fas S (hemma-ui lane): the sheet from Kan laga nu and "Packa upp" on the list.

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun recipeSheetFromPantry() = capture("phone_recipe_shopping_from_pantry_light_sv", ShoppingShot.RECIPE_SHEET_FROM_PANTRY)

    @Test
    fun recipeSheetFromPantryDark() =
        capture("phone_recipe_shopping_from_pantry_dark", ShoppingShot.RECIPE_SHEET_FROM_PANTRY, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun listUnpack() = capture("phone_shopping_unpack_light_sv", ShoppingShot.LIST_UNPACK)

    @Test
    @Config(qualifiers = TABLET)
    fun listUnpackTabletDark() = capture("tablet_shopping_unpack_dark", ShoppingShot.LIST_UNPACK, darkTheme = true)

    /** Fas S (integration): the "Packa upp" card in both tablet orientations. */
    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun listUnpackTabletPortrait() = capture("tablet_portrait_shopping_unpack_light_sv", ShoppingShot.LIST_UNPACK)

    private fun capture(
        name: String,
        shot: ShoppingShot,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        chrome: AppChrome = AppChrome(),
    ) {
        composeRule.setContent {
            ShoppingTestTheme(darkTheme = darkTheme, fontScale = fontScale, chrome = chrome) { shot.content() }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shopping/$name.png")
    }
}
