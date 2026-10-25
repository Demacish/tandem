package casa.moller.tandem.feature.mealplan

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.mealplan.Devices.PHONE
import casa.moller.tandem.feature.mealplan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.mealplan.Devices.TABLET
import casa.moller.tandem.feature.mealplan.Devices.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Veckan tab (plan 6.2, 6.5; Stitch veckan_matsedel*, planera_m_ltid_ark, handla_f_r_veckan):
 * the agenda on a phone (portrait; landscape has two panes), the tablet's two panes in portrait
 * and week grid in landscape, light and dark, English and Swedish, font scale 2, empty, guest,
 * demo, another week; the move sheet, the plan-meal page and "Handla för veckan". Recorded in CI
 * (Screenshots workflow).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class MealPlanScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun phone() = capture("phone_week_light", MealPlanShot.WEEK)

    @Test
    fun phoneDark() = capture("phone_week_dark", MealPlanShot.WEEK, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneSwedish() = capture("phone_week_light_sv", MealPlanShot.WEEK)

    @Test
    fun phoneFontScale2() = capture("phone_week_font2_light", MealPlanShot.WEEK, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneEmpty() = capture("phone_week_empty_light_sv", MealPlanShot.WEEK_EMPTY)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneGuestDark() = capture("phone_week_guest_dark_sv", MealPlanShot.WEEK_GUEST, darkTheme = true)

    @Test
    fun phoneNextWeek() = capture("phone_week_next_light", MealPlanShot.WEEK_NEXT)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneDemo() = capture(
        "phone_week_demo_light_sv",
        MealPlanShot.WEEK,
        chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true),
    )

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_week_light", MealPlanShot.WEEK)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = capture("tablet_portrait_week_light", MealPlanShot.WEEK)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun tabletPortraitDarkSunday() = capture("tablet_portrait_week_sunday_dark_sv", MealPlanShot.WEEK_DAY_SELECTED, darkTheme = true)

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = capture("tablet_week_light", MealPlanShot.WEEK)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletDark() = capture("tablet_week_dark", MealPlanShot.WEEK, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tabletGuest() = capture("tablet_week_guest_light_sv", MealPlanShot.WEEK_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun moveSheet() = capture("phone_move_light_sv", MealPlanShot.MOVE)

    @Test
    fun moveSheetDark() = capture("phone_move_dark", MealPlanShot.MOVE, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun planSearch() = capture("phone_plan_meal_light_sv", MealPlanShot.PLAN_SEARCH)

    @Test
    fun planChosen() = capture("phone_plan_meal_chosen_light", MealPlanShot.PLAN_CHOSEN)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun planTitleError() = capture("phone_plan_meal_title_dark_sv", MealPlanShot.PLAN_TITLE_ERROR, darkTheme = true)

    @Test
    fun planEditFontScale2() = capture("phone_plan_meal_edit_font2_light", MealPlanShot.PLAN_EDIT, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun planTablet() = capture("tablet_plan_meal_light", MealPlanShot.PLAN_SEARCH)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun weekShopping() = capture("phone_week_shopping_light_sv", MealPlanShot.WEEK_SHOPPING)

    @Test
    fun weekShoppingDark() = capture("phone_week_shopping_dark", MealPlanShot.WEEK_SHOPPING, darkTheme = true)

    private fun capture(
        name: String,
        shot: MealPlanShot,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        chrome: AppChrome = AppChrome(),
    ) {
        composeRule.setContent {
            MealPlanTestTheme(darkTheme = darkTheme, fontScale = fontScale, chrome = chrome) { shot.content() }
        }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/mealplan/$name.png")
    }
}
