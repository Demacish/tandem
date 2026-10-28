package casa.moller.tandem.feature.mealplan

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.mealplan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.mealplan.Devices.PHONE_TALL
import casa.moller.tandem.feature.mealplan.Devices.TABLET
import casa.moller.tandem.feature.mealplan.Devices.TABLET_PORTRAIT
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6/6.6) of every Veckan screen
 * and state: 48 dp targets, labels, contrast, light and dark, font scale 2, phone, phone in
 * landscape and tablet in both orientations, the move sheet, the conflict dialog, the plan-meal
 * page and "Handla för veckan".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class MealPlanAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun week() = check(MealPlanShot.WEEK)

    @Test
    fun weekDark() = check(MealPlanShot.WEEK, darkTheme = true)

    @Test
    fun weekFontScale2() = check(MealPlanShot.WEEK, fontScale = 2f)

    @Test
    fun weekEmpty() = check(MealPlanShot.WEEK_EMPTY)

    @Test
    fun weekGuest() = check(MealPlanShot.WEEK_GUEST)

    @Test
    fun weekNoHousehold() = check(MealPlanShot.WEEK_NO_HOUSEHOLD)

    @Test
    fun weekShared() = check(MealPlanShot.WEEK_SHARED)

    @Test
    fun weekSharedManyDarkFontScale2() = check(MealPlanShot.WEEK_SHARED_MANY, darkTheme = true, fontScale = 2f)

    @Test
    fun weekNext() = check(MealPlanShot.WEEK_NEXT)

    @Test
    fun weekDemo() = check(MealPlanShot.WEEK, chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true))

    @Test
    fun weekConflict() = check(MealPlanShot.WEEK_CONFLICT)

    @Test
    fun weekDelete() = check(MealPlanShot.WEEK_DELETE)

    @Test
    fun planDelete() = check(MealPlanShot.PLAN_DELETE)

    @Test
    fun planCollections() = check(MealPlanShot.PLAN_COLLECTIONS)

    @Test
    fun planCollectionsDarkFontScale2() = check(MealPlanShot.PLAN_COLLECTIONS, darkTheme = true, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun weekPhoneLandscape() = check(MealPlanShot.WEEK)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun weekTabletPortrait() = check(MealPlanShot.WEEK_DAY_SELECTED)

    @Test
    @Config(qualifiers = TABLET)
    fun weekTablet() = check(MealPlanShot.WEEK)

    @Test
    @Config(qualifiers = TABLET)
    fun weekTabletDarkFontScale2() = check(MealPlanShot.WEEK, darkTheme = true, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun weekTabletGuest() = check(MealPlanShot.WEEK_GUEST)

    @Test
    fun move() = check(MealPlanShot.MOVE)

    @Test
    fun moveDark() = check(MealPlanShot.MOVE, darkTheme = true)

    @Test
    fun planSearch() = check(MealPlanShot.PLAN_SEARCH)

    @Test
    fun planChosenDark() = check(MealPlanShot.PLAN_CHOSEN, darkTheme = true)

    @Test
    fun planTitleError() = check(MealPlanShot.PLAN_TITLE_ERROR)

    @Test
    fun planEditFontScale2() = check(MealPlanShot.PLAN_EDIT, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun planTablet() = check(MealPlanShot.PLAN_SEARCH)

    @Test
    fun weekShopping() = check(MealPlanShot.WEEK_SHOPPING)

    @Test
    fun weekShoppingDarkFontScale2() = check(MealPlanShot.WEEK_SHOPPING, darkTheme = true, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun weekShoppingTablet() = check(MealPlanShot.WEEK_SHOPPING)

    @Test
    fun weekShoppingGuest() = check(MealPlanShot.WEEK_SHOPPING_GUEST)

    @Test
    fun weekShoppingAllOnList() = check(MealPlanShot.WEEK_SHOPPING_ALL_ON_LIST)

    @Test
    fun weekShoppingEmpty() = check(MealPlanShot.WEEK_SHOPPING_EMPTY, fontScale = 2f)

    @Test
    fun weekShoppingAtHome() = check(MealPlanShot.WEEK_SHOPPING_AT_HOME)

    @Test
    fun weekShoppingAtHomeDarkFontScale2() = check(MealPlanShot.WEEK_SHOPPING_AT_HOME, darkTheme = true, fontScale = 2f)

    private fun check(shot: MealPlanShot, darkTheme: Boolean = false, fontScale: Float = 1f, chrome: AppChrome = AppChrome()) {
        composeRule.setContent {
            MealPlanTestTheme(darkTheme = darkTheme, fontScale = fontScale, chrome = chrome) { shot.content() }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
