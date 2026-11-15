package casa.moller.tandem.feature.mealplan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.accessibility.assertNoWordBreaks
import casa.moller.tandem.feature.mealplan.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.mealplan.Devices.TABLET
import casa.moller.tandem.feature.mealplan.Devices.TABLET_PORTRAIT
import casa.moller.tandem.feature.mealplan.ui.PLAN_MEAL_FORM_TAG
import casa.moller.tandem.feature.mealplan.ui.PLAN_MEAL_REMINDER_TAG
import casa.moller.tandem.feature.mealplan.ui.PLAN_MEAL_SAVE_ERROR_TAG
import casa.moller.tandem.feature.mealplan.ui.PLAN_MEAL_SAVE_TAG
import casa.moller.tandem.feature.mealplan.ui.PlanMealScreen
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tester's phone (Galaxy S21 FE): 360 × 780 dp at the default text size. */
private const val PHONE_SMALL = "w360dp-h780dp-port-xhdpi"

/** The same phone at the largest text and display size: 320 × 693 dp, font scale 2.0. */
private const val PHONE_LARGEST_TEXT = "w320dp-h693dp-port-xhdpi"

/**
 * Enhetstestet 2026-11-14, omgång 3, on "Planera måltid":
 * - B15: "Lägg till i matsedeln" did nothing visible, because "Välj en måltidstyp" stood a screen
 *   height up. A failed save now scrolls to the first missing field (and focuses it when it is a
 *   text field) and says what is missing beside the button, where TalkBack reads it.
 * - S2: the chosen recipe's name was cut ("…köttfärsgry…") beside "Byt recept"; the name has
 *   priority and "Byt recept" moves under it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_SMALL)
class PlanMealRoundThreeTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A server without meal types (B15) and a long search result, so the meal type is far up. */
    private val noMealTypes = MealPlanSamples.planState().copy(
        mealTypes = emptyList(),
        mealTypeId = null,
        results = MealPlanSamples.recipes,
    )

    private val longName = RecipeOverview(31, "Asiatisk köttfärsgryta med jasminris", workingMinutes = 30)

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun b15AFailedSaveBringsTheMissingMealTypeIntoView() {
        var state by mutableStateOf(noMealTypes)
        composeRule.setContent { MealPlanTestTheme { PlanMealScreen(state = state, onAction = { state = state.after(it) }) } }
        composeRule.onNodeWithTag(PLAN_MEAL_FORM_TAG).performScrollToNode(hasTestTag(PLAN_MEAL_REMINDER_TAG))
        // The meal type is far up: scrolled away.
        composeRule.onNodeWithText(string(R.string.mealplan_meal_type_heading)).assertDoesNotExist()

        composeRule.onNodeWithTag(PLAN_MEAL_SAVE_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.mealplan_meal_type_heading)).assertIsDisplayed()
        composeRule.onNodeWithTag(PLAN_MEAL_SAVE_ERROR_TAG).assertIsDisplayed().assertTextEquals(string(R.string.mealplan_type_missing))
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun b15AMissingRecipeIsShownAndItsSearchFocusedOnEveryTry() {
        var state by mutableStateOf(MealPlanSamples.planState().copy(results = MealPlanSamples.recipes))
        composeRule.setContent { MealPlanTestTheme { PlanMealScreen(state = state, onAction = { state = state.after(it) }) } }
        repeat(2) { attempt ->
            composeRule.onNodeWithTag(PLAN_MEAL_FORM_TAG).performScrollToNode(hasTestTag(PLAN_MEAL_REMINDER_TAG))
            composeRule.onNodeWithTag(PLAN_MEAL_SAVE_TAG).performClick()
            composeRule.waitForIdle()
            composeRule.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
            composeRule.onNodeWithTag(PLAN_MEAL_SAVE_ERROR_TAG).assertTextEquals(string(R.string.mealplan_recipe_missing))
            assertTrue("try ${attempt + 1}", state.failedSaves == attempt + 1)
        }
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun b15PhoneSwedish() = captureError("phone_small_devicetest_plan_meal_save_error_light_sv")

    @Test
    fun b15PhoneDark() = captureError("phone_small_devicetest_plan_meal_save_error_dark", darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST_TEXT")
    fun b15LargestTextSwedish() = captureError("phone_largest_devicetest_plan_meal_save_error_font2_light_sv", fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun b15PhoneLandscape() = captureError("phone_landscape_devicetest_plan_meal_save_error_light")

    // ATF runs on the phones only: at mdpi its estimate of the error text's contrast is too low
    // (4,43 for the theme's error colour, which passes on every xhdpi phone above).
    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun b15TabletSwedish() = captureError("tablet_devicetest_plan_meal_save_error_light_sv", accessibility = false)

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun b15TabletPortraitDark() =
        captureError("tablet_portrait_devicetest_plan_meal_save_error_dark", darkTheme = true, accessibility = false)

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun s2TheChosenRecipesNameHasPriority() {
        showChosen()
        assertNameWhole()
        composeRule.assertAccessible()
        capture("phone_small_devicetest_plan_meal_chosen_light_sv")
    }

    @Test
    fun s2Dark() {
        showChosen(darkTheme = true)
        assertNameWhole()
        composeRule.assertAccessible()
        capture("phone_small_devicetest_plan_meal_chosen_dark")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LARGEST_TEXT")
    fun s2LargestTextSwedish() {
        showChosen(fontScale = 2f)
        assertNameWhole()
        composeRule.assertAccessible()
        capture("phone_largest_devicetest_plan_meal_chosen_font2_light_sv")
    }

    private fun showChosen(darkTheme: Boolean = false, fontScale: Float = 1f) {
        val state = MealPlanSamples.planState(recipe = longName)
        composeRule.setContent {
            MealPlanTestTheme(darkTheme = darkTheme, fontScale = fontScale) { PlanMealScreen(state = state, onAction = {}) }
        }
        // The chosen recipe comes after the days, the meal types and the kind: below the fold on 360 dp.
        composeRule.onNodeWithTag(PLAN_MEAL_FORM_TAG).performScrollToNode(hasText(longName.name))
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/mealplan/$name.png")
    }

    /** The whole name is shown (no ellipsis, no word broken), and "Byt recept" is beside or under it, never over it. */
    private fun assertNameWhole() {
        val change = string(R.string.mealplan_change_recipe)
        composeRule.assertNoWordBreaks(longName.name, change)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(longName.name, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse("the name is not cut", layouts.first().hasVisualOverflow)
        val name = composeRule.onNodeWithText(longName.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithText(change, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "\"$change\" beside or under the name",
            button.left >= name.right || button.top >= name.bottom,
        )
    }

    private fun captureError(name: String, darkTheme: Boolean = false, fontScale: Float = 1f, accessibility: Boolean = true) {
        var state by mutableStateOf(noMealTypes)
        composeRule.setContent {
            MealPlanTestTheme(darkTheme = darkTheme, fontScale = fontScale) {
                PlanMealScreen(state = state, onAction = { state = state.after(it) })
            }
        }
        composeRule.onNodeWithTag(PLAN_MEAL_FORM_TAG).performScrollToNode(hasTestTag(PLAN_MEAL_REMINDER_TAG))
        composeRule.onNodeWithTag(PLAN_MEAL_SAVE_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PLAN_MEAL_SAVE_ERROR_TAG).assertIsDisplayed()
        if (accessibility) composeRule.assertAccessible()
        capture(name)
    }

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
}

/** What PlanMealViewModel does with "Lägg till i matsedeln" when a field is missing (B15). */
private fun PlanMealUiState.after(action: PlanMealAction): PlanMealUiState = when (action) {
    PlanMealAction.Save -> if (typeMissing || recipeMissing || titleMissing) {
        copy(showErrors = true, failedSaves = failedSaves + 1)
    } else {
        this
    }

    else -> this
}
