package casa.moller.tandem.feature.mealplan

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.mealplan.MealPlanSamples.day
import casa.moller.tandem.feature.mealplan.MealPlanSamples.leftovers
import casa.moller.tandem.feature.mealplan.MealPlanSamples.monday
import casa.moller.tandem.feature.mealplan.MealPlanSamples.salmon
import casa.moller.tandem.feature.mealplan.MealPlanSamples.todayDinner
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import casa.moller.tandem.feature.mealplan.ui.MoveMealPanel
import casa.moller.tandem.feature.mealplan.ui.PlanMealScreen
import casa.moller.tandem.feature.mealplan.ui.WEEK_AGENDA_TAG
import casa.moller.tandem.feature.mealplan.ui.WEEK_DAYS_TAG
import casa.moller.tandem.feature.mealplan.ui.WEEK_DAY_PANE_TAG
import casa.moller.tandem.feature.mealplan.ui.WEEK_GRID_TAG
import casa.moller.tandem.feature.mealplan.ui.WeekScreen
import casa.moller.tandem.feature.mealplan.ui.WeekShoppingScreen
import casa.moller.tandem.feature.mealplan.ui.dayTag
import casa.moller.tandem.feature.mealplan.ui.mealTag
import casa.moller.tandem.feature.mealplan.ui.planMealRecipeTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Veckan screens' behaviour and semantics (plan 6.2, 6.5, 6.6): actions, guest, layouts, undo. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = Devices.PHONE_TALL)
class WeekScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<WeekAction>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val format = MealPlanFormat(java.util.Locale.US)

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun show(state: WeekUiState) {
        composeRule.setContent { MealPlanTestTheme { WeekScreen(state = state, onAction = { actions += it }) } }
    }

    private fun customActions(id: Long): List<String> = composeRule.onNodeWithTag(mealTag(id)).fetchSemanticsNode().config
        .getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun aMealOpensItsRecipeAndOffersItsActionsToTalkBack() {
        show(MealPlanSamples.state())
        composeRule.onNodeWithTag(WEEK_AGENDA_TAG).performScrollToNode(androidx.compose.ui.test.hasTestTag(mealTag(monday.id)))
        composeRule.onNodeWithTag(mealTag(monday.id)).performClick()
        assertEquals(WeekAction.OpenRecipe(monday.recipe!!.id), actions.last())
        val expected = listOf(string(R.string.mealplan_edit), string(R.string.mealplan_move), string(R.string.mealplan_delete))
        assertEquals(expected, customActions(monday.id))
        // Today's meal can be cooked, also from TalkBack's actions.
        assertEquals(string(R.string.mealplan_cook), customActions(todayDinner.id).first())
        composeRule.onNodeWithContentDescription(string(R.string.mealplan_cook_description, todayDinner.displayTitle)).performClick()
        assertEquals(WeekAction.Cook(todayDinner), actions.last())
    }

    @Test
    fun aMealWithoutARecipeOpensTheChangePage() {
        show(MealPlanSamples.state())
        composeRule.onNodeWithTag(WEEK_AGENDA_TAG).performScrollToNode(androidx.compose.ui.test.hasTestTag(mealTag(leftovers.id)))
        composeRule.onNodeWithTag(mealTag(leftovers.id)).performClick()
        assertEquals(WeekAction.EditMeal(leftovers), actions.last())
    }

    @Test
    fun plusPlansAMealOnItsDay() {
        show(MealPlanSamples.state())
        val saturday = string(R.string.mealplan_plan_on_day, format.fullDayInText(day(5)))
        composeRule.onNodeWithTag(WEEK_AGENDA_TAG).performScrollToNode(hasContentDescription(saturday))
        composeRule.onNodeWithContentDescription(saturday).performClick()
        assertEquals(WeekAction.PlanMeal(day(5)), actions.last())
    }

    @Test
    fun aGuestHasNoPlanOrChangeControls() {
        show(MealPlanSamples.state(readOnly = true))
        composeRule.onNodeWithText(string(R.string.mealplan_read_only)).assertIsDisplayed()
        val plus = composeRule.onAllNodesWithContentDescription(string(R.string.mealplan_plan_on_day, format.fullDayInText(day(5))))
        assertTrue("no + for guests", plus.fetchSemanticsNodes().isEmpty())
        assertEquals(listOf(string(R.string.mealplan_cook)), customActions(todayDinner.id))
    }

    @Test
    fun anotherWeekHasTheWayBackToToday() {
        show(MealPlanSamples.state(start = day(7), meals = emptyList()))
        composeRule.onNodeWithContentDescription(string(R.string.mealplan_this_week_description)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.mealplan_next_week)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.mealplan_previous_week)).performClick()
        assertEquals(listOf(WeekAction.ThisWeek, WeekAction.NextWeek, WeekAction.PreviousWeek), actions)
        composeRule.onNodeWithText(string(R.string.mealplan_empty_week_title)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Devices.TABLET)
    fun theTabletGridShowsEveryDayIncludingSunday() {
        show(MealPlanSamples.state())
        composeRule.onNodeWithTag(WEEK_GRID_TAG).assertIsDisplayed()
        (0..6).forEach { composeRule.onNodeWithTag(dayTag(day(it))).assertIsDisplayed() }
        val saturdayDinner = string(R.string.mealplan_plan_on_day_type, MealPlanSamples.dinner.name, format.fullDayInText(day(5)))
        composeRule.onNodeWithContentDescription(saturdayDinner).performClick()
        assertEquals(WeekAction.PlanMeal(day(5), MealPlanSamples.dinner.id), actions.last())
    }

    @Test
    @Config(qualifiers = Devices.TABLET_PORTRAIT)
    fun theTabletInPortraitShowsTheDaysBesideTheChosenDay() {
        show(MealPlanSamples.state())
        composeRule.onNodeWithTag(WEEK_DAYS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WEEK_DAY_PANE_TAG).assertIsDisplayed()
        // Today is shown beside by default.
        composeRule.onNodeWithTag(mealTag(todayDinner.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(dayTag(day(6))).performClick()
        assertEquals(WeekAction.SelectDay(day(6)), actions.last())
    }

    @Test
    fun theMoveSheetChoosesADayAndAMealType() {
        var state = MoveMealState(monday, monday.fromDate, MealPlanSamples.dinner.id)
        composeRule.setContent {
            MealPlanTestTheme {
                MoveMealPanel(state, MealPlanSamples.mealTypes, MealPlanSamples.today, format, onAction = { actions += it })
            }
        }
        composeRule.onNodeWithText(string(R.string.mealplan_move)).assertIsNotEnabled()
        composeRule.onNodeWithText(format.shortDay(day(5))).performClick()
        composeRule.onNodeWithText(MealPlanSamples.lunch.name).performClick()
        assertEquals(listOf(WeekAction.MoveDate(day(5)), WeekAction.MoveMealType(MealPlanSamples.lunch.id)), actions)
        state = state.copy(date = day(5))
        assertTrue(state.changed)
    }

    @Test
    fun removingFromTheRouteCanBeUndone() {
        val mealPlans = FakeMealPlanRepository(meals = MealPlanSamples.meals, mealTypes = MealPlanSamples.mealTypes)
        val permissions = FakeSpacePermissionsRepository()
        val vm = WeekViewModel(null, SavedStateHandle(), mealPlans, permissions, FakeForegroundSync(), TestClock(MealPlanSamples.now))
        val planned = mutableListOf<PlanMealKey>()
        composeRule.setContent {
            MealPlanTestTheme {
                WeekRoute(
                    viewModel = vm,
                    onOpenRecipe = {},
                    onPlanMeal = { planned += it },
                    onWeekShopping = {},
                    onStartCooking = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag(WEEK_AGENDA_TAG).performScrollToNode(androidx.compose.ui.test.hasTestTag(mealTag(monday.id)))
        val remove = composeRule.onNodeWithTag(mealTag(monday.id)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == string(R.string.mealplan_delete) }
        composeRule.runOnIdle { remove.action() }
        composeRule.onNodeWithText(string(R.string.mealplan_msg_removed, monday.displayTitle)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.mealplan_undo)).performClick()
        composeRule.waitForIdle()
        assertEquals(monday.recipe?.id, mealPlans.drafts.single().recipeId)
        // "Ändra" opens the plan-meal page for the meal.
        val edit = composeRule.onNodeWithTag(mealTag(todayDinner.id)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == string(R.string.mealplan_edit) }
        composeRule.runOnIdle { edit.action() }
        assertEquals(PlanMealKey(date = todayDinner.fromDate.toString(), mealPlanId = todayDinner.id), planned.single())
    }

    @Test
    fun thePlanPagePicksARecipeAndNamesTheDayOnItsButton() {
        val planActions = mutableListOf<PlanMealAction>()
        composeRule.setContent {
            MealPlanTestTheme { PlanMealScreen(state = MealPlanSamples.planState(), onAction = { planActions += it }) }
        }
        composeRule.onNodeWithTag(planMealRecipeTag(salmon.id)).performClick()
        assertEquals(PlanMealAction.PickRecipe(salmon), planActions.last())
        composeRule.onNodeWithText(string(R.string.mealplan_save_new, format.weekdayInText(day(5)))).assertIsEnabled().performClick()
        assertEquals(PlanMealAction.Save, planActions.last())
    }

    @Test
    fun thePlanPageShowsWhatIsMissing() {
        composeRule.setContent {
            MealPlanTestTheme { PlanMealScreen(state = MealPlanSamples.planState(kind = MealKind.TITLE, showErrors = true), onAction = {}) }
        }
        composeRule.onNodeWithText(string(R.string.mealplan_title_missing)).assertIsDisplayed()
    }

    @Test
    fun weekShoppingNeverOffersAMealThatIsOnTheListAlready() {
        val shoppingActions = mutableListOf<WeekShoppingAction>()
        composeRule.setContent {
            MealPlanTestTheme { WeekShoppingScreen(state = MealPlanSamples.weekShopping, onAction = { shoppingActions += it }) }
        }
        composeRule.onNodeWithText(string(R.string.mealplan_week_shopping_auto_add)).assertIsDisplayed()
        val add = context.resources.getQuantityString(R.plurals.mealplan_week_shopping_add, 1, 1)
        composeRule.onNodeWithText(add).assertIsEnabled().performClick()
        assertEquals(WeekShoppingAction.Add, shoppingActions.last())
    }
}
