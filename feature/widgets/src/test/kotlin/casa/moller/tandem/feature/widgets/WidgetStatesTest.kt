package casa.moller.tandem.feature.widgets

import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.data.SampleShopping
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the widgets show of core:data's models (Fas 3, widgets lane): the open entries in order, today's meals. */
class WidgetStatesTest {
    @Test
    fun theShoppingWidgetShowsOpenEntriesInOrderAndCountsThem() {
        val now = SampleShopping.now
        val entries = SampleShopping.entries + listOf(
            ShoppingEntry(6, ShoppingFood(50, "kaffe"), ShoppingUnit(7, "paket"), 1.0, order = -1, delayUntil = now + 2.hours),
            ShoppingEntry(7, ShoppingFood(51, "smör"), null, 0.5, order = 9),
        )
        val state = ShoppingWidgetState.of(entries, now)
        assertEquals(5, state.remaining)
        assertEquals(listOf("2 l mjölk", "3 dl grädde", "1 kg potatis", "1 tandkräm", "0,5 smör"), state.rows.map { it.text })
        assertEquals(listOf(1L, 2L, 3L, 4L, 7L), state.rows.map { it.id })
        assertTrue(state.signedIn)
        assertEquals(ShoppingWidgetState(signedIn = false), ShoppingWidgetState.of(emptyList(), now, signedIn = false))
    }

    @Test
    fun theShoppingWidgetShowsAtMostTwelveRows() {
        val many = (1..20).map { ShoppingEntry(it.toLong(), ShoppingFood(it.toLong(), "vara $it"), amount = 0.0, order = it) }
        val state = ShoppingWidgetState.of(many, SampleShopping.now)
        assertEquals(ShoppingWidgetState.MAX_ROWS, state.rows.size)
        assertEquals(20, state.remaining)
        assertEquals("vara 1", state.rows.first().text)
    }

    @Test
    fun theTonightWidgetShowsTodaysMealsInMealTypeOrder() {
        val lunch = MealPlanEntry(id = 9, title = "Rester", fromDate = SampleRecipes.Today, mealType = SampleRecipes.Lunch)
        val upcoming = UpcomingMeals(
            today = SampleRecipes.Today,
            todayMeals = SampleRecipes.upcomingMeals.todayMeals + lunch,
            tomorrowMeals = SampleRecipes.upcomingMeals.tomorrowMeals,
        )
        val state = TonightWidgetState.of(upcoming)
        assertEquals(listOf("Rester", "Krämig svamppasta"), state.meals.map { it.title })
        assertEquals(listOf("Lunch", "Middag"), state.meals.map { it.mealType })
        assertEquals(listOf(null, SampleRecipes.MushroomPasta.id), state.meals.map { it.recipeId })
        assertFalse(TonightWidgetState.of(UpcomingMeals(today = SampleRecipes.Today), signedIn = false).signedIn)
    }
}
