package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.feature.mealplan.MealPlanSamples.day
import casa.moller.tandem.feature.mealplan.MealPlanSamples.dinner
import casa.moller.tandem.feature.mealplan.MealPlanSamples.mealTypes
import casa.moller.tandem.feature.mealplan.MealPlanSamples.now
import casa.moller.tandem.feature.mealplan.MealPlanSamples.suggestionCandidates
import casa.moller.tandem.feature.mealplan.MealPlanSamples.today
import casa.moller.tandem.feature.mealplan.MealPlanSamples.week
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekSuggestionsTest {

    private val days = week(listOf(MealPlanSamples.todayBreakfast))

    @Test
    fun theMainMealTypeIsTheLatestOfTheDayElseTheMostUsed() {
        assertEquals(dinner, WeekSuggestions.mainMealType(week(), mealTypes))
        assertEquals("only a breakfast planned: still the latest of the day", dinner, WeekSuggestions.mainMealType(days, mealTypes))
        val untimed = mealTypes.map { it.copy(time = null) }
        val lunch = MealPlanSamples.leftovers.copy(recipe = MealPlanSamples.pasta)
        val friday = lunch.copy(id = 99, fromDate = day(4), recipe = MealPlanSamples.salmon)
        val lunches = week(listOf(lunch, friday, MealPlanSamples.todayDinner))
        assertEquals(untimed.first { it.id == MealPlanSamples.lunch.id }, WeekSuggestions.mainMealType(lunches, untimed))
        val empty = week(emptyList())
        assertEquals("nothing planned: the last by order", untimed.maxBy { it.order }, WeekSuggestions.mainMealType(empty, untimed))
        assertNull(WeekSuggestions.mainMealType(days, emptyList()))
    }

    @Test
    fun rankingLeavesOutTheWeeksRecipesAndPutsRecentlyCookedLast() {
        val ranked = WeekSuggestions.ranked(week(), suggestionCandidates, now)
        assertFalse("the stew is planned on Monday", ranked.any { it.id == MealPlanSamples.lentils.id })
        assertEquals("Fläskfilé med rotfrukter", ranked.first().name)
        assertEquals("cooked three days ago, however popular", "Fredagstacos", ranked.last().name)
        val expected =
            listOf("Fläskfilé med rotfrukter", "Fisksoppa med saffran", "Fransk örtomelett med chèvre", "Lasagne", "Fredagstacos")
        assertEquals(expected, ranked.map { it.name })
    }

    @Test
    fun oneSuggestionPerFreeDayFromTodayAndReshufflingKeepsToTheBest() {
        val state = WeekSuggestions.suggest(days, mealTypes, suggestionCandidates, today, now)
        assertEquals(listOf(day(3), day(4), day(5), day(6)), state.meals.map { it.date })
        assertTrue(state.meals.all { it.mealType == dinner })
        // The stew is not planned this week, so its 30 times cooked put it first.
        val expected =
            listOf("Linsgryta med kokosmjölk", "Fläskfilé med rotfrukter", "Fisksoppa med saffran", "Fransk örtomelett med chèvre")
        assertEquals(expected, state.meals.map { it.recipe.name })

        val shuffled = WeekSuggestions.suggest(days, mealTypes, suggestionCandidates, today, now, seed = 7)
        assertEquals(4, shuffled.meals.map { it.recipe.id }.distinct().size)
        assertEquals(shuffled, WeekSuggestions.suggest(days, mealTypes, suggestionCandidates, today, now, seed = 7))

        val few = WeekSuggestions.suggest(days, mealTypes, suggestionCandidates.take(2), today, now)
        assertEquals("fewer recipes than days: the days that can be filled", 2, few.meals.size)
    }

    @Test
    fun aFullWeekOrNoRecipesExplainsItself() {
        val full = week(MealPlanSamples.meals + MealPlanSamples.todayDinner.copy(id = 98, fromDate = day(5), toDate = day(5)))
        val noDays = WeekSuggestions.suggest(full, mealTypes, suggestionCandidates, today, now)
        assertTrue(noDays.noFreeDays)
        assertTrue(noDays.meals.isEmpty())
        val noRecipes = WeekSuggestions.suggest(days, mealTypes, emptyList(), today, now)
        assertTrue(noRecipes.noRecipes)
        val past = WeekSuggestions.suggest(days, mealTypes, suggestionCandidates, day(10), now)
        assertTrue("a week gone by has no free day", past.noFreeDays)
    }

    @Test
    fun swappingTakesTheNextRecipeInLineAndLeavesOtherDaysAlone() {
        val state = WeekSuggestions.suggest(days, mealTypes, suggestionCandidates, today, now)
        val swapped = WeekSuggestions.swap(state, day(4), days, suggestionCandidates, now)
        assertEquals("Ugnsstekt citronkyckling", swapped.meals[1].recipe.name)
        assertEquals(state.meals.filterIndexed { index, _ -> index != 1 }, swapped.meals.filterIndexed { index, _ -> index != 1 })
        assertEquals("a day not suggested", swapped, WeekSuggestions.swap(swapped, day(1), days, suggestionCandidates, now))
        val again = WeekSuggestions.swap(swapped, day(5), days, suggestionCandidates, now)
        assertEquals("the pork is free again", "Fläskfilé med rotfrukter", again.meals[2].recipe.name)
        val only = WeekSuggestions.suggest(days, mealTypes, suggestionCandidates.take(1), today, now)
        assertEquals("nothing left to swap to", only, WeekSuggestions.swap(only, day(3), days, suggestionCandidates.take(1), now))
    }

    // fas4-kvar: plan §8.3 "Autoplan" (chosen keywords and meal type, no repeats) and the pantry.

    @Test
    fun keywordsMustAllMatchAndTheChoicesAreTheMostUsedOnes() {
        val weekdayOnly = WeekSuggestions.matching(suggestionCandidates, setOf(MealPlanSamples.weekday.id))
        val expected = setOf("Lasagne", "Fredagstacos", "Fransk örtomelett med chèvre", "Fläskfilé med rotfrukter")
        assertEquals(expected, weekdayOnly.map { it.name }.toSet())
        val both = WeekSuggestions.matching(suggestionCandidates, setOf(MealPlanSamples.weekday.id, MealPlanSamples.quick.id))
        assertEquals(setOf("Fredagstacos", "Fransk örtomelett med chèvre"), both.map { it.name }.toSet())
        assertEquals(suggestionCandidates, WeekSuggestions.matching(suggestionCandidates, emptySet()))

        val choices = WeekSuggestions.keywordChoices(suggestionCandidates, emptySet())
        assertEquals(listOf("Vardag", "Snabbt", "Fisk"), choices.map { it.name })
        val many = (1..20).map { MealPlanSamples.lasagne.copy(id = 200L + it, keywords = listOf(Keyword(300L + it, "Ord $it"))) }
        val beer = Keyword(999, "Ölkorv") // Sorted last among keywords used once.
        val capped = WeekSuggestions.keywordChoices(many + MealPlanSamples.lasagne.copy(id = 998, keywords = listOf(beer)), setOf(beer.id))
        assertEquals(WeekSuggestions.MAX_KEYWORDS + 1, capped.size)
        assertEquals("a chosen keyword stays offered", beer, capped.last())
    }

    @Test
    fun aWholeWeekGetsSevenDifferentRecipesOfTheChosenMealTypeAndKeywords() {
        val next = week(emptyList(), start = day(7))
        val many = (1..10).map { MealPlanSamples.lasagne.copy(id = 400L + it, name = "Vardag $it") }
        val state = WeekSuggestions.suggest(
            next,
            mealTypes,
            suggestionCandidates + many,
            today,
            now,
            mealTypeId = MealPlanSamples.lunch.id,
            keywordIds = setOf(MealPlanSamples.weekday.id),
        )
        assertEquals(MealPlanSamples.lunch, state.mealType)
        assertEquals(next.map { it.date }, state.meals.map { it.date })
        assertEquals(7, state.meals.map { it.recipe.id }.distinct().size)
        assertTrue(state.meals.all { MealPlanSamples.weekday in it.recipe.keywords })
        repeat(20) { seed ->
            val keywordIds = setOf(MealPlanSamples.weekday.id)
            val shuffled = WeekSuggestions.suggest(next, mealTypes, suggestionCandidates + many, today, now, seed, keywordIds = keywordIds)
            assertEquals("seed $seed", 7, shuffled.meals.map { it.recipe.id }.distinct().size)
        }
        // Fewer matching recipes than days: days stay empty rather than a recipe twice.
        val few = WeekSuggestions.suggest(next, mealTypes, suggestionCandidates, today, now, keywordIds = setOf(MealPlanSamples.quick.id))
        assertEquals(2, few.meals.size)
        assertEquals(2, few.meals.map { it.recipe.id }.distinct().size)
    }

    @Test
    fun noRecipeWithEveryKeywordSaysSoAndKeepsTheChoices() {
        val state = MealPlanSamples.suggestionsNoMatchState
        assertTrue(state.noKeywordMatch)
        assertFalse(state.noRecipes)
        assertTrue(state.meals.isEmpty())
        assertEquals(setOf(MealPlanSamples.fish.id, MealPlanSamples.quick.id), state.keywordIds)
        assertEquals(mealTypes, state.mealTypes)
    }

    @Test
    fun recipesThatUseWhatExpiresSoonComeFirstAndSaySo() {
        val ranked = WeekSuggestions.ranked(days, suggestionCandidates, now, MealPlanSamples.suggestionExpiring)
        assertEquals(listOf("Fläskfilé med rotfrukter", "Fisksoppa med saffran"), ranked.take(2).map { it.name })
        assertEquals("cooked three days ago stays last", "Fredagstacos", ranked.last().name)
        val state = MealPlanSamples.suggestionsState
        assertEquals(2, state.meals.first { it.recipe.id == MealPlanSamples.porkTenderloin.id }.expiring.size)
        assertTrue(state.meals.first { it.recipe.name.startsWith("Linsgryta") }.expiring.isEmpty())
        // "Byt ut" keeps the pantry information of the new recipe.
        val swapped = WeekSuggestions.swap(state, day(3), days, suggestionCandidates, now, MealPlanSamples.suggestionExpiring)
        assertTrue(swapped.meals.first().expiring.isEmpty())
    }
}
