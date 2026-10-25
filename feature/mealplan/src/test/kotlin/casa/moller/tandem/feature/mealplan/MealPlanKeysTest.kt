package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import casa.moller.tandem.feature.mealplan.navigation.WeekAgendaKey
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MealPlanKeysTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, key: T): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, key))

    @Test
    fun keysSurviveSerialization() {
        assertEquals(WeekAgendaKey("2000-01-10"), roundTrip(WeekAgendaKey.serializer(), WeekAgendaKey("2000-01-10")))
        assertEquals(WeekAgendaKey(), roundTrip(WeekAgendaKey.serializer(), WeekAgendaKey()))
        val plan = PlanMealKey(date = "2000-01-15", mealTypeId = 1, mealPlanId = -4, recipeId = 101)
        assertEquals(plan, roundTrip(PlanMealKey.serializer(), plan))
        assertEquals(WeekShoppingKey("2000-01-10"), roundTrip(WeekShoppingKey.serializer(), WeekShoppingKey("2000-01-10")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun datesMustBeIsoDays() {
        PlanMealKey(date = "15/1/2000")
    }

    @Test(expected = IllegalArgumentException::class)
    fun weekStartsMustBeIsoDays() {
        WeekAgendaKey("vecka 2")
    }
}
