package casa.moller.tandem.feature.mealplan

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.mealplan.Devices.PHONE
import casa.moller.tandem.feature.mealplan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.mealplan.Devices.TABLET
import casa.moller.tandem.feature.mealplan.Devices.TABLET_PORTRAIT
import casa.moller.tandem.feature.mealplan.ui.PLAN_MEAL_FORM_TAG
import casa.moller.tandem.feature.mealplan.ui.PLAN_MEAL_REMINDER_TAG
import casa.moller.tandem.feature.mealplan.ui.PlanMealScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas 5 (planera lane), plan §8.3: "Påminn kvällen innan" on the plan-meal page: the field writes
 * the reminder with the app language's word, and its screenshots (phone portrait and landscape,
 * tablet in both orientations, dark, Swedish, font scale 2) with ATF on each.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class PlanMealReminderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val state = MealPlanSamples.planState(kind = MealKind.TITLE, title = "Kycklinggryta", editing = true)
        .copy(note = "Dubbel sats, frys hälften", reminder = "Tina ut kycklingen")

    @Test
    fun theFieldSendsTheReminderWithTheLanguagesWord() {
        val actions = mutableListOf<PlanMealAction>()
        composeRule.setContent {
            MealPlanTestTheme { PlanMealScreen(state = state.copy(reminder = ""), onAction = { actions += it }) }
        }
        composeRule.onNodeWithTag(PLAN_MEAL_FORM_TAG).performScrollToNode(hasTestTag(PLAN_MEAL_REMINDER_TAG))
        composeRule.onNodeWithTag(PLAN_MEAL_REMINDER_TAG).performTextInput("Thaw the chicken")
        assertEquals(PlanMealAction.Reminder("Thaw the chicken", "Remind"), actions.last())
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun inSwedishTheWordIsPaminn() {
        val actions = mutableListOf<PlanMealAction>()
        composeRule.setContent {
            MealPlanTestTheme { PlanMealScreen(state = state.copy(reminder = ""), onAction = { actions += it }) }
        }
        composeRule.onNodeWithTag(PLAN_MEAL_FORM_TAG).performScrollToNode(hasTestTag(PLAN_MEAL_REMINDER_TAG))
        composeRule.onNodeWithTag(PLAN_MEAL_REMINDER_TAG).performTextInput("Tina ut")
        assertEquals(PlanMealAction.Reminder("Tina ut", "Påminn"), actions.last())
    }

    @Test
    fun phone() = capture("phone_plan_meal_reminder_light")

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun phoneDarkSwedish() = capture("phone_plan_meal_reminder_dark_sv", darkTheme = true)

    @Test
    fun phoneFontScale2() = capture("phone_plan_meal_reminder_font2_light", fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_plan_meal_reminder_light")

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun tablet() = capture("tablet_plan_meal_reminder_light_sv")

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletPortraitDark() = capture("tablet_portrait_plan_meal_reminder_dark", darkTheme = true)

    private fun capture(name: String, darkTheme: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            MealPlanTestTheme(darkTheme = darkTheme, fontScale = fontScale) { PlanMealScreen(state = state, onAction = {}) }
        }
        composeRule.onNodeWithTag(PLAN_MEAL_FORM_TAG).performScrollToNode(hasTestTag(PLAN_MEAL_REMINDER_TAG))
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/mealplan/$name.png")
    }
}
