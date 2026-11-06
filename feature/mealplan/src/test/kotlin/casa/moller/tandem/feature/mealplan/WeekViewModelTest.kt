package casa.moller.tandem.feature.mealplan

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingRecipeLabel
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeDrinkRepository
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeMakeNowRepository
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakeRecipeBookRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.mealplan.MealPlanSamples.day
import casa.moller.tandem.feature.mealplan.MealPlanSamples.dinner
import casa.moller.tandem.feature.mealplan.MealPlanSamples.lunch
import casa.moller.tandem.feature.mealplan.MealPlanSamples.monday
import casa.moller.tandem.feature.mealplan.MealPlanSamples.todayDinner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WeekViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(MealPlanSamples.now)
    private val mealPlans = FakeMealPlanRepository(meals = MealPlanSamples.meals, mealTypes = MealPlanSamples.mealTypes)
    private val permissions = FakeSpacePermissionsRepository()
    private val foreground = FakeForegroundSync()
    private val shopping = FakeShoppingRepository(entries = emptyList(), lists = emptyList())
    private val removedMeals = RemovedMeals()
    private val recipes = FakeRecipeRepository(overviews = MealPlanSamples.suggestionCandidates)
    private val makeNow = FakeMakeNowRepository(emptyMap())
    private val books = FakeRecipeBookRepository(recipes = MealPlanSamples.suggestionCandidates)
    private val drinks = FakeDrinkRepository(drinks = emptyList(), pairings = emptyList())

    private fun viewModel(weekStart: String? = null, saved: SavedStateHandle = SavedStateHandle()) =
        WeekViewModel(weekStart, saved, mealPlans, permissions, foreground, clock, shopping, removedMeals, recipes, makeNow, books, drinks)

    /** Starts collecting the state (as the screen does) and the effects. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: WeekViewModel): MutableList<WeekEffect> {
        val effects = mutableListOf<WeekEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    @Test
    fun opensOnThisWeekWithTodayAndKeepsItFresh() = runTest {
        val vm = viewModel()
        open(vm)
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(MealPlanSamples.weekStart, state.weekStart)
        assertEquals(MealPlanSamples.today, state.today)
        assertTrue(state.isThisWeek)
        assertEquals(7, state.days.size)
        assertEquals(listOf("Frukost", "Middag"), state.days[3].meals.map { it.mealType.name })
        assertEquals(6, state.plannedDays)
        assertEquals(MealPlanSamples.today, state.shownDay)
        assertEquals(listOf<ForegroundTarget>(ForegroundTarget.MealPlan(MealPlanSamples.weekStart)), foreground.started)
    }

    @Test
    fun anyDayOfTheKeyOpensItsWeek() = runTest {
        val vm = viewModel(weekStart = "2026-10-28")
        open(vm)
        assertEquals(LocalDate(2026, 10, 26), vm.uiState.value.weekStart)
        assertFalse(vm.uiState.value.isThisWeek)
    }

    @Test
    fun previousNextAndBackToToday() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved = saved)
        open(vm)
        vm.onAction(WeekAction.NextWeek)
        assertEquals(day(7), vm.uiState.value.weekStart)
        assertTrue(ForegroundTarget.MealPlan(day(7)) in foreground.started)
        vm.onAction(WeekAction.PreviousWeek)
        vm.onAction(WeekAction.PreviousWeek)
        assertEquals(day(-7), vm.uiState.value.weekStart)
        vm.onAction(WeekAction.ThisWeek)
        assertEquals(MealPlanSamples.weekStart, vm.uiState.value.weekStart)
        assertEquals(MealPlanSamples.today, vm.uiState.value.selectedDay)
        // The shown week survives process death.
        vm.onAction(WeekAction.NextWeek)
        val restored = viewModel(saved = saved)
        open(restored)
        assertEquals(day(7), restored.uiState.value.weekStart)
    }

    @Test
    fun removingAMealCanBeUndone() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.Delete(todayDinner))
        assertTrue(vm.uiState.value.days[3].meals.none { it.id == todayDinner.id })
        assertEquals(WeekEffect.Show(WeekMessage.Removed(todayDinner.displayTitle)), effects.single())
        vm.onAction(WeekAction.Undo)
        val again = mealPlans.drafts.single()
        assertEquals(todayDinner.toDraft(), again)
        assertEquals(todayDinner.recipe?.id, again.recipeId)
        assertEquals("Dubbel sats, frys hälften", again.note)
        assertEquals(1, vm.uiState.value.days[3].meals.count { it.recipe?.id == todayDinner.recipe?.id })
    }

    /**
     * fix-ui (Phase 2 review): Tandoor deletes a meal's rows on the shopping list with it, so
     * removing such a meal asks first (with how many rows), and "Ångra" plans the meal again AND
     * puts the rows still to buy back on the list, linked to the new meal.
     */
    @Test
    fun removingAMealWithRowsOnTheListAsksFirstAndUndoPutsTheRowsBack() = runTest {
        val label = ShoppingRecipeLabel(listRecipeId = 40, recipeId = todayDinner.recipe?.id, mealPlanId = todayDinner.id, servings = 4.0)
        shopping.entries.value = listOf(
            ShoppingEntry(1, ShoppingFood(1, "champinjon"), amount = 400.0, recipe = label, ingredientId = 10111, listIds = setOf(2)),
            ShoppingEntry(2, ShoppingFood(4, "pasta"), amount = 400.0, recipe = label, ingredientId = 10121, listIds = setOf(2)),
            ShoppingEntry(3, ShoppingFood(6, "vispgrädde"), amount = 2.5, recipe = label, ingredientId = 10131, checked = true),
            ShoppingEntry(4, ShoppingFood(8, "mjölk"), amount = 1.0),
        )
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.Delete(todayDinner))
        assertEquals(DeleteMealState(todayDinner, rows = 3), vm.uiState.value.deleting)
        assertTrue("nothing is removed before the answer", mealPlans.meals.value.any { it.id == todayDinner.id })

        vm.onAction(WeekAction.ConfirmDelete(false))
        assertNull(vm.uiState.value.deleting)
        assertTrue(mealPlans.meals.value.any { it.id == todayDinner.id })

        vm.onAction(WeekAction.Delete(todayDinner))
        vm.onAction(WeekAction.ConfirmDelete(true))
        assertTrue(mealPlans.meals.value.none { it.id == todayDinner.id })
        assertEquals(WeekEffect.Show(WeekMessage.Removed(todayDinner.displayTitle)), effects.single())

        vm.onAction(WeekAction.Undo)
        val again = mealPlans.meals.value.single { it.fromDate == todayDinner.fromDate && it.recipe?.id == todayDinner.recipe?.id }
        val request = shopping.recipeRequests.single()
        assertEquals(again.id, request.mealPlanId)
        assertEquals("the rows still to buy, not the bought one", setOf(10111L, 10121L), request.ingredientIds)
        assertEquals(4.0, request.servings, 0.0)
        assertEquals(setOf(2L), request.listIds)
    }

    /**
     * 0.3.0 komplettering: a meal removed on its own page (the page closed at once) gets the same
     * "Ångra" in the agenda: the meal is planned again and its rows still to buy go back with addRecipe(mealPlanId).
     */
    @Test
    fun aMealRemovedOnItsOwnPageCanBeUndoneInTheAgenda() = runTest {
        val label = ShoppingRecipeLabel(listRecipeId = 40, recipeId = todayDinner.recipe?.id, mealPlanId = todayDinner.id, servings = 4.0)
        val rows = listOf(ShoppingEntry(1, ShoppingFood(1, "champinjon"), amount = 400.0, recipe = label, ingredientId = 10111))
        val vm = viewModel()
        val effects = open(vm)
        mealPlans.meals.value = mealPlans.meals.value.filterNot { it.id == todayDinner.id }
        removedMeals.offer(RemovedMeals.Removal(todayDinner, rows))
        assertEquals(WeekEffect.Show(WeekMessage.Removed(todayDinner.displayTitle)), effects.single())
        assertNull("taken once", removedMeals.take())

        vm.onAction(WeekAction.Undo)
        val again = mealPlans.meals.value.single { it.fromDate == todayDinner.fromDate && it.recipe?.id == todayDinner.recipe?.id }
        assertEquals(todayDinner.toDraft(), mealPlans.drafts.single())
        val request = shopping.recipeRequests.single()
        assertEquals(again.id, request.mealPlanId)
        assertEquals(setOf(10111L), request.ingredientIds)
    }

    @Test
    fun aMealTheServerSaysIsOnTheListAsksToo() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.Delete(MealPlanSamples.sunday))
        assertEquals(DeleteMealState(MealPlanSamples.sunday, rows = 0), vm.uiState.value.deleting)
    }

    @Test
    fun movingKeepsTheLengthAndCanBeUndone() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.StartMove(monday))
        assertEquals(MoveMealState(monday, monday.fromDate, dinner.id), vm.uiState.value.move)
        vm.onAction(WeekAction.MoveDate(day(5)))
        vm.onAction(WeekAction.MoveMealType(lunch.id))
        vm.onAction(WeekAction.ConfirmMove)
        assertNull(vm.uiState.value.move)
        val moved = vm.uiState.value.days[5].meals.single()
        assertEquals(monday.id, moved.id)
        assertEquals(lunch.id, moved.mealType.id)
        assertEquals(WeekEffect.Show(WeekMessage.Moved(monday.displayTitle, day(5))), effects.single())
        vm.onAction(WeekAction.Undo)
        val back = vm.uiState.value.days[0].meals.single()
        assertEquals(dinner.id, back.mealType.id)
    }

    @Test
    fun aDroppedMealMovesAtOnceAndCanBeUndone() = runTest {
        // Fas 3 (dra och släpp): the agenda drops on a day (type kept), the grid on a day and type.
        val vm = viewModel()
        val effects = open(vm)
        fun mealOn(offset: Int) = vm.uiState.value.days[offset].meals.single { it.id == monday.id }
        vm.onAction(WeekAction.DropMeal(monday.id, day(5)))
        assertEquals(dinner.id, mealOn(5).mealType.id)
        assertEquals(WeekEffect.Show(WeekMessage.Moved(monday.displayTitle, day(5))), effects.single())
        vm.onAction(WeekAction.DropMeal(monday.id, day(5), lunch.id))
        assertEquals(lunch.id, mealOn(5).mealType.id)
        // Dropped where it already is: nothing; an unknown meal: nothing.
        vm.onAction(WeekAction.DropMeal(monday.id, day(5), lunch.id))
        vm.onAction(WeekAction.DropMeal(999, day(1)))
        assertEquals(2, effects.size)
        vm.onAction(WeekAction.Undo)
        assertEquals(dinner.id, mealOn(5).mealType.id)
    }

    // #21: a recipe dragged from "Vill laga" or the tablet's search.

    @Test
    fun aDroppedRecipeBecomesAMealWithUsualServingsAndUndo() = runTest {
        books.wantToCook.value = setOf(MealPlanSamples.lasagne.id)
        val vm = viewModel()
        val effects = open(vm)
        assertEquals(listOf(MealPlanSamples.lasagne.id), vm.uiState.value.wantToCook.map { it.id })

        vm.onAction(WeekAction.DropRecipe(MealPlanSamples.lasagne.id, day(5)))
        val planned = vm.uiState.value.days[5].meals.single()
        assertEquals(MealPlanSamples.lasagne.id, planned.recipe?.id)
        assertEquals("the main meal without a cell", dinner.id, planned.mealType.id)
        assertEquals("the week's other dinners", 4.0, planned.servings, 0.0)
        assertEquals(WeekEffect.Show(WeekMessage.Planned(MealPlanSamples.lasagne.name, day(5))), effects.last())

        vm.onAction(WeekAction.Undo)
        assertTrue(vm.uiState.value.days[5].meals.isEmpty())

        vm.onAction(WeekAction.DropRecipe(MealPlanSamples.lasagne.id, day(5), lunch.id))
        assertEquals("a cell of the grid names its meal type", lunch.id, vm.uiState.value.days[5].meals.single().mealType.id)
    }

    @Test
    fun aGuestNeitherSeesTheQueueNorPlansADroppedRecipe() = runTest {
        books.wantToCook.value = setOf(MealPlanSamples.lasagne.id)
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val vm = viewModel()
        val effects = open(vm)
        assertTrue(vm.uiState.value.wantToCook.isEmpty())
        val before = mealPlans.meals.value
        vm.onAction(WeekAction.DropRecipe(MealPlanSamples.lasagne.id, day(5)))
        assertEquals(before, mealPlans.meals.value)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun theRecipeSearchFindsStoredRecipes() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.SearchRecipes("lasagne"))
        assertEquals(listOf(MealPlanSamples.lasagne.id), vm.uiState.value.recipeResults.map { it.id })
        vm.onAction(WeekAction.SearchRecipes(""))
        assertTrue(vm.uiState.value.recipeResults.isEmpty())
    }

    // Fas 4 "Förslag på veckans matsedel".

    @Test
    fun aDrinkIsNeverSuggestedAndItsKeywordsAreNoChoices() = runTest {
        val wine = Keyword(901, "Vin")
        val pairs = Keyword(902, "Till fisk")
        val rioja = MealPlanSamples.porkTenderloin.copy(id = 900, name = "Rioja", keywords = listOf(wine), rating = 5.0, timesCooked = 99)
        val dish = MealPlanSamples.lasagne.copy(keywords = MealPlanSamples.lasagne.keywords + pairs)
        recipes.overviews.value = MealPlanSamples.suggestionCandidates.filterNot { it.id == dish.id } + dish + rioja
        drinks.drinks.value = listOf(Drink(id = rioja.id, name = rioja.name, types = listOf("Vin")))
        drinks.pairings.value = listOf(Pairing(pairs.id, pairs.name))
        val vm = viewModel(day(7).toString())
        open(vm)
        vm.onAction(WeekAction.Suggest)
        val suggestions = vm.uiState.value.suggestions!!
        assertTrue(suggestions.meals.isNotEmpty())
        assertFalse("the highest rated recipe is a wine", suggestions.meals.any { it.recipe.id == rioja.id })
        assertFalse(wine in suggestions.keywords)
        assertFalse(pairs in suggestions.keywords)
        assertFalse(pairs in suggestions.allKeywords)
    }

    @Test
    fun anEmptyWeekPlansTheServingsOfRecentMealsAndTheChoiceInTheSheet() = runTest {
        val vm = viewModel(day(7).toString())
        open(vm)
        vm.onAction(WeekAction.Suggest)
        assertEquals("this week's dinners, though next week is empty", 4.0, vm.uiState.value.suggestions!!.servings, 0.0)
        vm.onAction(WeekAction.MoreSuggestServings)
        vm.onAction(WeekAction.MoreSuggestServings)
        vm.onAction(WeekAction.FewerSuggestServings)
        assertEquals(5.0, vm.uiState.value.suggestions!!.servings, 0.0)
        vm.onAction(WeekAction.AcceptSuggestions)
        assertEquals(5.0, vm.uiState.value.days.first().meals.first().servings, 0.0)
    }

    @Test
    fun withoutAnyMealsTheServingsAreThePlanPagesDefault() = runTest {
        mealPlans.meals.value = emptyList()
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.Suggest)
        assertEquals(PlanMealUiState.DEFAULT_SERVINGS, vm.uiState.value.suggestions!!.servings, 0.0)
    }

    @Test
    fun suggestsADinnerForEveryFreeDayFromTodayAndPlansThemWithUndo() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.Suggest)
        val suggestions = vm.uiState.value.suggestions!!
        // Dinner is the week's main meal; Thursday (today), Friday and Sunday have one, Saturday has not.
        assertEquals(dinner, suggestions.mealType)
        assertEquals(listOf(day(5)), suggestions.meals.map { it.date })
        // The best candidate not in the week: often cooked, not cooked lately (the stew is in the week).
        assertEquals("Fläskfilé med rotfrukter", suggestions.meals.single().recipe.name)

        vm.onAction(WeekAction.AcceptSuggestions)
        assertNull(vm.uiState.value.suggestions)
        val planned = vm.uiState.value.days[5].meals.single()
        assertEquals(MealPlanSamples.porkTenderloin.id, planned.recipe?.id)
        assertEquals(dinner.id, planned.mealType.id)
        assertEquals("servings like the week's other dinners", 4.0, planned.servings, 0.0)
        assertEquals(WeekEffect.Show(WeekMessage.Suggested(1)), effects.last())

        vm.onAction(WeekAction.Undo)
        assertTrue(vm.uiState.value.days[5].meals.isEmpty())
    }

    @Test
    fun swappingReshufflingAndRemovingChangeTheSuggestionsBeforeTheyArePlanned() = runTest {
        mealPlans.meals.value = listOf(MealPlanSamples.todayBreakfast)
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.Suggest)
        val first = vm.uiState.value.suggestions!!
        assertEquals(listOf(day(3), day(4), day(5), day(6)), first.meals.map { it.date })
        assertEquals(4, first.meals.map { it.recipe.id }.distinct().size)
        assertEquals("the stew is free this week", "Linsgryta med kokosmjölk", first.meals.first().recipe.name)

        vm.onAction(WeekAction.SwapSuggestion(day(3)))
        val swapped = vm.uiState.value.suggestions!!
        assertTrue(swapped.meals.first().recipe.id !in first.meals.map { it.recipe.id })
        assertEquals(first.meals.drop(1), swapped.meals.drop(1))

        vm.onAction(WeekAction.Reshuffle)
        val shuffled = vm.uiState.value.suggestions!!
        assertEquals(4, shuffled.meals.size)
        assertEquals(4, shuffled.meals.map { it.recipe.id }.distinct().size)

        vm.onAction(WeekAction.RemoveSuggestion(day(6)))
        assertEquals(listOf(day(3), day(4), day(5)), vm.uiState.value.suggestions!!.meals.map { it.date })
        vm.onAction(WeekAction.CancelSuggestions)
        assertNull(vm.uiState.value.suggestions)
        assertEquals(1, mealPlans.meals.value.size)
    }

    @Test
    fun nextWeekGetsSevenDinnersFromTheChosenKeywordsWithoutRepeatsAndTheyArePlannedAsMeals() = runTest {
        // Plan §10 Fas 4 "Klart när" (Autoplan): seven dinners for next week from chosen keywords, no recipe twice.
        val many = (1..9).map { RecipeOverview(100L + it, "Vardagsrätt $it", keywords = listOf(MealPlanSamples.weekday)) }
        recipes.overviews.value = MealPlanSamples.suggestionCandidates + many
        val vm = viewModel(weekStart = day(7).toString())
        open(vm)
        vm.onAction(WeekAction.Suggest)
        vm.onAction(WeekAction.ToggleSuggestKeyword(MealPlanSamples.weekday.id))
        val suggestions = vm.uiState.value.suggestions!!
        assertEquals(setOf(MealPlanSamples.weekday.id), suggestions.keywordIds)
        assertEquals((7..13).map { day(it) }, suggestions.meals.map { it.date })
        assertEquals(7, suggestions.meals.map { it.recipe.id }.distinct().size)
        assertTrue(suggestions.meals.all { meal -> MealPlanSamples.weekday in meal.recipe.keywords })
        assertTrue(suggestions.meals.all { it.mealType == dinner })

        vm.onAction(WeekAction.AcceptSuggestions)
        // Saved as ordinary meal plans (what the web shows), one per day.
        val planned = mealPlans.meals.value.filter { it.fromDate >= day(7) && it.mealType.id == dinner.id }
        assertEquals(7, planned.map { it.fromDate }.distinct().size)
        assertEquals(7, planned.mapNotNull { it.recipe?.id }.distinct().size)
    }

    @Test
    fun theMealTypeCanBeChosenAndKeywordsWithoutRecipesSaySo() = runTest {
        mealPlans.meals.value = listOf(MealPlanSamples.todayBreakfast)
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.Suggest)
        vm.onAction(WeekAction.SuggestMealType(MealPlanSamples.lunch.id))
        val lunches = vm.uiState.value.suggestions!!
        assertEquals(MealPlanSamples.lunch.id, lunches.mealType?.id)
        assertEquals(listOf(day(3), day(4), day(5), day(6)), lunches.meals.map { it.date })

        vm.onAction(WeekAction.ToggleSuggestKeyword(MealPlanSamples.fish.id))
        vm.onAction(WeekAction.ToggleSuggestKeyword(MealPlanSamples.quick.id))
        val none = vm.uiState.value.suggestions!!
        assertTrue(none.noKeywordMatch)
        assertTrue(none.meals.isEmpty())
        vm.onAction(WeekAction.ToggleSuggestKeyword(MealPlanSamples.quick.id))
        assertEquals(listOf("Fisksoppa med saffran"), vm.uiState.value.suggestions!!.meals.map { it.recipe.name })
    }

    @Test
    fun suggestionsPreferRecipesThatUseWhatExpiresSoon() = runTest {
        mealPlans.meals.value = listOf(MealPlanSamples.todayBreakfast)
        makeNow.state.value = MakeNowState(
            mapOf(
                MakeNowGroup.HAS_ALL to listOf(
                    MakeNowRecipe(MealPlanSamples.lasagne, MakeNowGroup.HAS_ALL, expiring = MealPlanSamples.expiringCream),
                ),
            ),
        )
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.Suggest)
        val first = vm.uiState.value.suggestions!!.meals.first()
        assertEquals("cooked once, but it uses the cream", "Lasagne", first.recipe.name)
        assertEquals(MealPlanSamples.expiringCream, first.expiring)
    }

    @Test
    fun aGuestGetsNoSuggestionsAndAFullWeekSaysSo() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val guest = viewModel()
        open(guest)
        assertFalse(guest.uiState.value.canSuggest)
        guest.onAction(WeekAction.Suggest)
        assertNull(guest.uiState.value.suggestions)

        permissions.state.value = SpacePermissions(SpaceRole.USER)
        val saturday = MealPlanEntry(19, recipe = MealPlanSamples.salmon, fromDate = day(5), mealType = dinner)
        mealPlans.meals.value = MealPlanSamples.meals + saturday
        val full = viewModel()
        open(full)
        full.onAction(WeekAction.Suggest)
        assertTrue(full.uiState.value.suggestions!!.noFreeDays)
        assertTrue(full.uiState.value.suggestions!!.meals.isEmpty())
    }

    @Test
    fun anUnchangedMoveDoesNothing() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.StartMove(monday))
        vm.onAction(WeekAction.ConfirmMove)
        assertTrue(effects.isEmpty())
        assertEquals(monday, vm.uiState.value.days[0].meals.single())
    }

    @Test
    fun aGuestSeesTheWeekButCannotChangeIt() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        val effects = open(vm)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(WeekAction.Delete(todayDinner))
        vm.onAction(WeekAction.StartMove(monday))
        assertNull(vm.uiState.value.move)
        vm.onAction(WeekAction.DropMeal(monday.id, day(3)))
        assertTrue(effects.isEmpty())
        assertEquals(MealPlanSamples.meals.size, mealPlans.meals.value.size)
    }

    /** 0.3.0 review (fix-data, plan 8.6): without a household the week says it is not shared. */
    @Test
    fun aMemberWithoutAHouseholdIsWarned() = runTest {
        val vm = viewModel()
        open(vm)
        assertFalse(vm.uiState.value.householdMissing)
        permissions.state.value = permissions.state.value.copy(householdMissing = true)
        assertTrue(vm.uiState.value.householdMissing)
        assertFalse(vm.uiState.value.readOnly)
    }

    /** 0.3.0 komplettering (plan 8.6): "Delad med: …" names the household's other members. */
    @Test
    fun theWeekSaysWhoElseSeesIt() = runTest {
        val vm = viewModel()
        open(vm)
        assertEquals(emptyList<String>(), vm.uiState.value.sharedWith)
        permissions.state.value = permissions.state.value.copy(sharedWith = listOf("Anna"))
        assertEquals(listOf("Anna"), vm.uiState.value.sharedWith)
    }

    @Test
    fun aConflictIsShownWithItsMealAndResolved() = runTest {
        val conflict = MealPlanConflict(todayDinner.id, "title", mine = "Min", theirs = "Deras")
        mealPlans.conflicts.value = listOf(conflict)
        val vm = viewModel()
        open(vm)
        assertEquals(ConflictState(conflict, todayDinner.displayTitle), vm.uiState.value.conflict)
        vm.onAction(WeekAction.ResolveConflict(keepMine = false))
        assertEquals(listOf(conflict to false), mealPlans.resolved)
        assertNull(vm.uiState.value.conflict)
    }

    @Test
    fun aFailedRefreshIsReported() = runTest {
        mealPlans.failure = TandemError.NetworkUnavailable
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.Refresh)
        assertEquals(WeekEffect.Failed(TandemError.NetworkUnavailable), effects.single())
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test
    fun selectingADayShowsItBeside() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.SelectDay(day(6)))
        assertEquals(day(6), vm.uiState.value.shownDay)
    }
}
