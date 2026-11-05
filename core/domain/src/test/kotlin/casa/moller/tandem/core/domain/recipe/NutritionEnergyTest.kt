package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.model.recipe.NutritionValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NutritionEnergyTest {
    private val values = listOf(NutritionValue("Energi", 500.0, "kcal"), NutritionValue("Protein", 18.0, "g"))

    @Test
    fun kcalStaysWhenTheUserPrefersKcal() {
        assertEquals(values, NutritionEnergy.inPreferredUnit(values, useKj = false))
    }

    @Test
    fun kcalBecomesKjWhenTheUserPrefersKj() {
        val shown = NutritionEnergy.inPreferredUnit(values, useKj = true)
        assertEquals("kJ", shown[0].unit)
        assertEquals(2092.0, shown[0].amount, 1e-9)
        assertEquals(values[1], shown[1])
    }

    @Test
    fun aTypeDefinedInKjFollowsThePreferenceToo() {
        val kj = NutritionValue("Energy", 2092.0, "KJ")
        assertEquals(NutritionValue("Energy", 500.0, "kcal").amount, NutritionEnergy.inPreferredUnit(kj, useKj = false).amount, 1e-9)
        assertEquals("kcal", NutritionEnergy.inPreferredUnit(kj, useKj = false).unit)
        assertEquals(kj, NutritionEnergy.inPreferredUnit(kj, useKj = true))
    }
}
