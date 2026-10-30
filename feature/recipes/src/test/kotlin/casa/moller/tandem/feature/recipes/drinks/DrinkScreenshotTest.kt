package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import casa.moller.tandem.feature.recipes.books.PHONE_LANDSCAPE
import casa.moller.tandem.feature.recipes.books.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drinks and "Passar med" (#43, plan §8.8; no Stitch screens, the recipe list's patterns): phone
 * portrait and landscape, tablet (two columns, the list beside a drink) in both orientations,
 * light and dark, English and Swedish, font scale 2, empty, no match, guest and demo. Recorded in
 * CI (Screenshots workflow), under `drinks/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class DrinkScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun list() = capture("phone_drinks_light", DrinkShot.LIST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun listSwedish() = capture("phone_drinks_light_sv", DrinkShot.LIST)

    @Test
    fun listDark() = capture("phone_drinks_dark", DrinkShot.LIST, darkTheme = true)

    @Test
    fun listFontScale2() = capture("phone_drinks_font2_light", DrinkShot.LIST, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun listLandscape() = capture("phone_landscape_drinks_light", DrinkShot.LIST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun listFiltered() = capture("phone_drinks_wine_light_sv", DrinkShot.LIST_FILTERED)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun listGuest() = capture("phone_drinks_guest_light_sv", DrinkShot.LIST_GUEST)

    @Test
    fun listEmptyDemo() = capture("phone_drinks_empty_demo_light", DrinkShot.LIST_EMPTY, chrome = DEMO)

    @Test
    fun listEmptyGuestDark() = capture("phone_drinks_empty_guest_dark", DrinkShot.LIST_EMPTY_GUEST, darkTheme = true)

    @Test
    fun listNoMatch() = capture("phone_drinks_no_match_light", DrinkShot.LIST_NO_MATCH)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletListDetail() = capture("tablet_drinks_light_sv", DrinkShot.LIST_DETAIL)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletListDark() = capture("tablet_drinks_dark", DrinkShot.LIST, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun tabletPortraitList() = capture("tablet_portrait_drinks_light_sv", DrinkShot.LIST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun dishPairings() = capture("phone_dish_pairings_light_sv", DrinkShot.DISH_PAIRINGS)

    @Test
    fun dishPairingsDarkFontScale2() = capture("phone_dish_pairings_font2_dark", DrinkShot.DISH_PAIRINGS, darkTheme = true, fontScale = 2f)

    @Test
    fun dishPairingsEmpty() = capture("phone_dish_pairings_empty_light", DrinkShot.DISH_PAIRINGS_EMPTY)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun dishPairingsGuest() = capture("phone_dish_pairings_guest_light_sv", DrinkShot.DISH_PAIRINGS_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun drinkPairings() = capture("phone_drink_pairings_light_sv", DrinkShot.DRINK_PAIRINGS)

    @Test
    fun drinkPairingsDark() = capture("phone_drink_pairings_dark", DrinkShot.DRINK_PAIRINGS, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun detailWithPairings() = capture("phone_detail_pairings_light_sv", DrinkShot.DETAIL_WITH_PAIRINGS)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun detailWithPairingsTablet() = capture("tablet_detail_pairings_light_sv", DrinkShot.DETAIL_WITH_PAIRINGS)

    /** Fas S (integration): the recipe with "Passar med" in both tablet orientations. */
    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun detailWithPairingsTabletPortraitDark() =
        capture("tablet_portrait_detail_pairings_dark_sv", DrinkShot.DETAIL_WITH_PAIRINGS, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun pickerForADish() = capture("phone_pairing_picker_light_sv", DrinkShot.PICKER_DISH)

    @Test
    fun pickerForADishDark() = capture("phone_pairing_picker_dark", DrinkShot.PICKER_DISH, darkTheme = true)

    @Test
    fun pickerFontScale2() = capture("phone_pairing_picker_font2_light", DrinkShot.PICKER_DISH, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun pickerNewKind() = capture("phone_pairing_picker_new_kind_light_sv", DrinkShot.PICKER_NEW_KIND)

    @Test
    fun pickerGuest() = capture("phone_pairing_picker_guest_light", DrinkShot.PICKER_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun pickerForADrink() = capture("phone_pairing_picker_drink_light_sv", DrinkShot.PICKER_DRINK)

    @Test
    @Config(qualifiers = TABLET)
    fun pickerTablet() = capture("tablet_pairing_picker_light", DrinkShot.PICKER_DISH)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun pickerTabletPortrait() = capture("tablet_portrait_pairing_picker_light", DrinkShot.PICKER_DISH)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun createEmpty() = capture("phone_create_drink_light_sv", DrinkShot.CREATE_EMPTY)

    @Test
    fun createFilledDark() = capture("phone_create_drink_filled_dark", DrinkShot.CREATE_FILLED, darkTheme = true)

    @Test
    fun createFontScale2() = capture("phone_create_drink_font2_light", DrinkShot.CREATE_FILLED, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun createNameMissing() = capture("phone_create_drink_name_missing_light_sv", DrinkShot.CREATE_NAME_MISSING)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun createLandscape() = capture("phone_landscape_create_drink_light", DrinkShot.CREATE_FILLED)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun createTablet() = capture("tablet_create_drink_light_sv", DrinkShot.CREATE_FILLED)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun createTabletPortrait() = capture("tablet_portrait_create_drink_light_sv", DrinkShot.CREATE_FILLED)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun suggestion() = capture("phone_drink_suggestion_light_sv", DrinkShot.SUGGESTION)

    @Test
    fun suggestionDarkFontScale2() = capture("phone_drink_suggestion_font2_dark", DrinkShot.SUGGESTION, darkTheme = true, fontScale = 2f)

    private fun capture(
        name: String,
        shot: DrinkShot,
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        chrome: AppChrome = AppChrome(),
    ) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, chrome = chrome, fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/drinks/$name.png")
    }

    private companion object {
        val DEMO = AppChrome(status = AppStatus.Demo, showDemoBanner = true)
    }
}
