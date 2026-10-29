package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6/6.6) of every drink screen
 * and state ([DrinkShot]): 48 dp targets, labels on the remove and filter buttons, one item per
 * drink row and checkbox row, contrast in light and dark, font scale 2 and the tablet's layouts.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class DrinkAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(shot: DrinkShot, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun list() = check(DrinkShot.LIST)

    @Test
    fun listDark() = check(DrinkShot.LIST, darkTheme = true)

    @Test
    fun listFontScale2() = check(DrinkShot.LIST, fontScale = 2f)

    @Test
    fun listFiltered() = check(DrinkShot.LIST_FILTERED)

    @Test
    fun listGuest() = check(DrinkShot.LIST_GUEST)

    @Test
    fun listEmpty() = check(DrinkShot.LIST_EMPTY)

    @Test
    fun listEmptyGuest() = check(DrinkShot.LIST_EMPTY_GUEST, darkTheme = true)

    @Test
    fun listNoMatch() = check(DrinkShot.LIST_NO_MATCH)

    @Test
    @Config(qualifiers = TABLET)
    fun listDetail() = check(DrinkShot.LIST_DETAIL)

    @Test
    fun dishPairings() = check(DrinkShot.DISH_PAIRINGS)

    @Test
    fun dishPairingsDarkFontScale2() = check(DrinkShot.DISH_PAIRINGS, darkTheme = true, fontScale = 2f)

    @Test
    fun dishPairingsEmpty() = check(DrinkShot.DISH_PAIRINGS_EMPTY)

    @Test
    fun dishPairingsGuest() = check(DrinkShot.DISH_PAIRINGS_GUEST)

    @Test
    fun drinkPairings() = check(DrinkShot.DRINK_PAIRINGS)

    @Test
    fun drinkPairingsDark() = check(DrinkShot.DRINK_PAIRINGS, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun detailWithPairings() = check(DrinkShot.DETAIL_WITH_PAIRINGS)

    @Test
    fun picker() = check(DrinkShot.PICKER_DISH)

    @Test
    fun pickerDark() = check(DrinkShot.PICKER_DISH, darkTheme = true)

    @Test
    fun pickerFontScale2() = check(DrinkShot.PICKER_DISH, fontScale = 2f)

    @Test
    fun pickerNewKind() = check(DrinkShot.PICKER_NEW_KIND)

    @Test
    fun pickerGuest() = check(DrinkShot.PICKER_GUEST)

    @Test
    fun pickerForADrink() = check(DrinkShot.PICKER_DRINK)

    @Test
    fun create() = check(DrinkShot.CREATE_EMPTY)

    @Test
    fun createFilledDark() = check(DrinkShot.CREATE_FILLED, darkTheme = true)

    @Test
    fun createFontScale2() = check(DrinkShot.CREATE_FILLED, fontScale = 2f)

    @Test
    fun createNameMissing() = check(DrinkShot.CREATE_NAME_MISSING)

    @Test
    fun suggestion() = check(DrinkShot.SUGGESTION)

    @Test
    fun suggestionDark() = check(DrinkShot.SUGGESTION, darkTheme = true)
}
