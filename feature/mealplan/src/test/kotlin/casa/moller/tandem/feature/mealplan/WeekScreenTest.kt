package casa.moller.tandem.feature.mealplan

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.domain.mealplan.WeekShoppingMerge
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
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
import casa.moller.tandem.feature.mealplan.ui.WEEK_SHOPPING_LIST_TAG
import casa.moller.tandem.feature.mealplan.ui.WeekScreen
import casa.moller.tandem.feature.mealplan.ui.WeekShoppingScreen
import casa.moller.tandem.feature.mealplan.ui.dayTag
import casa.moller.tandem.feature.mealplan.ui.mealTag
import casa.moller.tandem.feature.mealplan.ui.planMealRecipeTag
import casa.moller.tandem.feature.mealplan.ui.weekShoppingLineTag
import casa.moller.tandem.feature.mealplan.ui.weekShoppingMealTag
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

    private val todayBreakfastId = MealPlanSamples.todayBreakfast.id

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

    /** fix-ui (plan §10 "veckorutnätet … i båda orienteringarna"): a tablet in portrait gets the grid too. */
    @Test
    @Config(qualifiers = Devices.TABLET_PORTRAIT)
    fun theTabletInPortraitShowsTheWeekGridWithTodayInView() {
        show(MealPlanSamples.state())
        composeRule.onNodeWithTag(WEEK_GRID_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(dayTag(MealPlanSamples.today)).assertIsDisplayed()
        composeRule.onNodeWithTag(mealTag(todayDinner.id)).assertIsDisplayed()
    }

    /**
     * fix-ui: in the grid the day is a column heading TalkBack does not pass on, so a meal says its
     * meal type and day itself ("Dinner, Thursday, October 22, today: …").
     */
    @Test
    @Config(qualifiers = Devices.TABLET)
    fun aMealInTheGridSaysItsDayAndMealType() {
        show(MealPlanSamples.state())
        val description = composeRule.onNodeWithTag(mealTag(todayDinner.id)).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        val today = string(R.string.mealplan_today_heading, format.fullDay(MealPlanSamples.today))
        assertTrue(description, description.startsWith(string(R.string.mealplan_tile_description, todayDinner.mealType.name, today, "")))
        assertTrue(description, todayDinner.displayTitle in description)
        val onMonday = composeRule.onNodeWithTag(mealTag(monday.id)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertTrue(onMonday.joinToString(), format.fullDay(day(0)) in onMonday.joinToString())
    }

    @Test
    fun aLongPressOnAMealMovesIt() {
        show(MealPlanSamples.state())
        composeRule.onNodeWithTag(WEEK_AGENDA_TAG).performScrollToNode(hasTestTag(mealTag(monday.id)))
        composeRule.onNodeWithTag(mealTag(monday.id)).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(WeekAction.StartMove(monday), actions.last())
    }

    @Test
    fun aGuestHasNoLongPress() {
        show(MealPlanSamples.state(readOnly = true))
        composeRule.onNodeWithTag(WEEK_AGENDA_TAG).performScrollToNode(hasTestTag(mealTag(monday.id)))
        assertTrue(SemanticsActions.OnLongClick !in composeRule.onNodeWithTag(mealTag(monday.id)).fetchSemanticsNode().config)
    }

    /** fix-ui: the agenda opens on today (Thursday), but the guest note above the days stays in view. */
    @Test
    @Config(qualifiers = Devices.PHONE)
    fun theGuestNoteShowsWhenTheWeekOpensOnToday() {
        show(MealPlanSamples.state(readOnly = true))
        composeRule.onNodeWithText(string(R.string.mealplan_read_only)).assertIsDisplayed()
        composeRule.onNodeWithTag(dayTag(MealPlanSamples.today)).assertIsDisplayed()
    }

    @Test
    fun removingAMealWithRowsOnTheListAsksFirst() {
        show(MealPlanSamples.state().copy(deleting = DeleteMealState(todayDinner, rows = 3)))
        val rows = context.resources.getQuantityString(R.plurals.mealplan_delete_confirm_rows, 3, 3)
        composeRule.onNodeWithText(string(R.string.mealplan_delete_confirm_body, todayDinner.displayTitle) + " " + rows).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.mealplan_delete_confirm)).performClick()
        assertEquals(WeekAction.ConfirmDelete(true), actions.last())
    }

    @Test
    @Config(qualifiers = Devices.PHONE_LANDSCAPE)
    fun aPhoneInLandscapeShowsTheDaysBesideTheChosenDay() {
        show(MealPlanSamples.state())
        composeRule.onNodeWithTag(WEEK_DAYS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WEEK_DAY_PANE_TAG).assertIsDisplayed()
        // Today is shown beside by default.
        composeRule.onNodeWithTag(mealTag(todayBreakfastId)).assertIsDisplayed()
        composeRule.onNodeWithTag(WEEK_DAYS_TAG).performScrollToNode(hasTestTag(dayTag(day(6))))
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
        val vm = WeekViewModel(
            null,
            SavedStateHandle(),
            mealPlans,
            permissions,
            FakeForegroundSync(),
            TestClock(MealPlanSamples.now),
            FakeShoppingRepository(entries = emptyList(), lists = emptyList()),
            RemovedMeals(),
        )
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
        // Sunday's chicken is on the list already: its chip cannot be picked.
        composeRule.onNodeWithTag(weekShoppingMealTag(MealPlanSamples.sunday.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(weekShoppingMealTag(MealPlanSamples.friday.id)).assertIsEnabled().performClick()
        assertEquals(WeekShoppingAction.ToggleMeal(MealPlanSamples.friday.id), shoppingActions.last())
        val add = context.resources.getQuantityString(R.plurals.mealplan_week_shopping_add_items, 5, 5)
        composeRule.onNodeWithText(add).assertIsEnabled().performClick()
        assertEquals(WeekShoppingAction.Add, shoppingActions.last())
    }

    /** Phase 2 (koppling lane): one merged line is one TalkBack item and toggles by its key. */
    @Test
    fun aMergedLineIsOneToggleableItem() {
        val shoppingActions = mutableListOf<WeekShoppingAction>()
        composeRule.setContent {
            MealPlanTestTheme { WeekShoppingScreen(state = MealPlanSamples.weekShopping, onAction = { shoppingActions += it }) }
        }
        val cream = WeekShoppingMerge.key("grädde", "dl")
        composeRule.onNodeWithTag(WEEK_SHOPPING_LIST_TAG).performScrollToNode(hasTestTag(weekShoppingLineTag(cream)))
        composeRule.onNodeWithTag(weekShoppingLineTag(cream))
            .assertIsOn()
            .assert(hasContentDescription(string(R.string.mealplan_week_shopping_merged), substring = true))
            .performClick()
        assertEquals(WeekShoppingAction.ToggleLine(cream), shoppingActions.last())
    }

    /** 0.3.0 komplettering (finding 22): "Finns redan hemma?" says where "at home" comes from. */
    @Test
    fun theAtHomeGroupExplainsTheTandoorSettings() {
        composeRule.setContent { MealPlanTestTheme { WeekShoppingScreen(state = MealPlanSamples.weekShopping, onAction = {}) } }
        val hint = string(R.string.mealplan_week_shopping_at_home_settings)
        composeRule.onNodeWithTag(WEEK_SHOPPING_LIST_TAG).performScrollToNode(hasText(hint))
        composeRule.onNodeWithText(hint).assertIsDisplayed()
    }
}
