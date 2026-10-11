package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.UnitConversion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ingredient rows like the web's ingredient table (FACTS.md "Fas 1 l" 3–4) plus unit cycling. */
class IngredientDisplayTest {
    private val decimals = UserPreferences(useFractions = false)
    private val fractions = UserPreferences(useFractions = true)

    private fun rows(recipe: Recipe, servings: Double, preferences: UserPreferences, style: NumberStyle): List<IngredientRow> =
        IngredientDisplay.rows(recipe.ingredients, ServingsScaling.factor(recipe, servings), preferences, style)

    private fun List<IngredientRow>.item(food: String): IngredientRow.Item =
        filterIsInstance<IngredientRow.Item>().first { it.food == food }

    @Test
    fun amountUnitFoodAndNoteInSwedish() {
        val pasta = RecipeFixtures.recipe("recipe-svamppasta.json")
        val atThree = rows(pasta, 3.0, decimals, NumberStyle.Swedish)
        val mushrooms = atThree.item("champinjoner")
        assertEquals("187,5", mushrooms.amount)
        assertEquals("g", mushrooms.unit)
        assertEquals("gärna blandade svampar", mushrooms.note)
        assertEquals("187,5 g champinjoner", mushrooms.text())
        val onion = atThree.item("gula lökar")
        assertEquals("0,75", onion.amount)
        assertEquals("st", onion.unit)
        val garlic = atThree.item("vitlöksklyftor")
        assertEquals("1,5", garlic.amount)
        assertEquals("", garlic.unit)
        assertEquals("1,5 vitlöksklyftor", garlic.text())
        assertEquals("gul lök", rows(pasta, 4.0, decimals, NumberStyle.Swedish).item("gul lök").food)
        val withFractions = rows(pasta, 3.0, fractions, NumberStyle.Swedish)
        assertEquals("187\u00A0½", withFractions.item("champinjoner").amount)
        assertEquals("¾", withFractions.item("gula lökar").amount)
    }

    @Test
    fun noAmountAndZeroAmountRowsShowOnlyTheFood() {
        val pasta = RecipeFixtures.recipe("recipe-svamppasta.json")
        val salt = rows(pasta, 8.0, decimals, NumberStyle.Swedish).first { it.id == pasta.steps[1].ingredients[1].id } as IngredientRow.Item
        assertTrue(salt.noAmount)
        assertEquals("", salt.amount)
        assertEquals("", salt.unit)
        assertTrue(salt.units.isEmpty())
        assertNull(salt.unitAt(1))
        assertEquals("salt", salt.food)
        assertEquals("till pastavattnet", salt.note)
        // Amount 0 without no_amount: no amount, no unit, and the food in plural (like the web).
        val cake = RecipeFixtures.recipe("recipe-smorgastarta.json")
        val tomato = rows(cake, 12.0, decimals, NumberStyle.Swedish).item("tomater")
        assertEquals("", tomato.amount)
        assertEquals("", tomato.unit)
        assertFalse(tomato.canCycleUnits)
    }

    @Test
    fun unitPluralFollowsTheScaledAmount() {
        val shakshuka = RecipeFixtures.recipe("recipe-shakshuka.json")
        assertEquals("cups", rows(shakshuka, 2.0, decimals, NumberStyle.English).item("crushed tomatoes").unit)
        assertEquals("cup", rows(shakshuka, 2.0 / 1.5, decimals, NumberStyle.English).item("crushed tomatoes").unit)
        assertEquals("pinch", rows(shakshuka, 2.0, decimals, NumberStyle.English).item("salt").unit)
        assertEquals("pinches", rows(shakshuka, 4.0, decimals, NumberStyle.English).item("salt").unit)
    }

    @Test
    fun unitConversionsInServerOrderWithoutRepeats() {
        val cake = RecipeFixtures.recipe("recipe-appelkaka.json")
        val flour = rows(cake, 8.0, decimals, NumberStyle.Swedish).item("vetemjöl")
        assertTrue(flour.canCycleUnits)
        assertEquals(
            listOf("2,5 dl", "250 ml", "150 g", "1,06 cup", "0,25 l", "16,91 msk", "50,72 tsk", "0,15 kg"),
            flour.units.map { "${it.amount} ${it.unit}" },
        )
        assertEquals("2,5 dl vetemjöl", flour.text(0))
        assertEquals("150 g vetemjöl", flour.text(2))
        assertEquals("2,5 dl vetemjöl", flour.text(8))
        assertEquals("0,15 kg vetemjöl", flour.text(-1))
        val doubled = rows(cake, 16.0, decimals, NumberStyle.Swedish).item("vetemjöl")
        assertEquals("300 g", doubled.units[2].let { "${it.amount} ${it.unit}" })
        // An ingredient without unit or food has no conversions: nothing to cycle.
        assertFalse(rows(cake, 8.0, decimals, NumberStyle.Swedish).item("ägg").canCycleUnits)
    }

    @Test
    fun conversionsSkipEmptyZeroAndDuplicateUnits() {
        val milk = Ingredient(
            id = 1,
            food = Food(1, "mjölk"),
            unit = MeasurementUnit(1, "dl"),
            amount = 2.0,
            conversions = listOf(
                UnitConversion(2.0, "dl"),
                UnitConversion(200.0, "ml"),
                UnitConversion(0.0, "l"),
                UnitConversion(200.0, "ML"),
                UnitConversion(1.0, " "),
                UnitConversion(Double.NaN, "cup"),
                UnitConversion(13.5, "msk"),
            ),
        )
        val item = IngredientDisplay.row(milk, 1.0, decimals, NumberStyle.Swedish) as IngredientRow.Item
        assertEquals(listOf("dl", "ml", "msk"), item.units.map { it.unit })
    }

    @Test
    fun headersAndGroups() {
        val buns = RecipeFixtures.recipe("recipe-kanelbullar.json")
        val rows = rows(buns, 30.0, decimals, NumberStyle.Swedish)
        assertEquals(IngredientRow.Header(buns.ingredients.first().id, "Deg"), rows.first())
        val groups = IngredientDisplay.groups(rows)
        assertEquals(listOf("Deg", "Fyllning", "Pensling"), groups.map { it.header?.title })
        assertEquals(listOf(7, 3, 1), groups.map { it.items.size })
        assertEquals("1 tsk kardemumma", groups[0].items.first { it.food == "kardemumma" }.text())
        assertEquals("stött", groups[0].items.first { it.food == "kardemumma" }.note)

        val loose = IngredientRow.Item(id = 1, units = emptyList(), food = "salt", note = "")
        val header = IngredientRow.Header(id = 2, title = "Sås")
        val empty = IngredientRow.Header(id = 3, title = "Garnering")
        assertEquals(
            listOf(IngredientGroup(null, listOf(loose)), IngredientGroup(header, emptyList()), IngredientGroup(empty, listOf(loose))),
            IngredientDisplay.groups(listOf(loose, header, empty, loose)),
        )
        assertEquals(emptyList<IngredientGroup>(), IngredientDisplay.groups(emptyList()))
    }

    @Test
    fun headerAndFreeTextFallbacks() {
        val header = IngredientDisplay.row(Ingredient(id = 1, isHeader = true, note = " "), 1.0, decimals, NumberStyle.Swedish)
        assertEquals(IngredientRow.Header(1, ""), header)
        val freeText = Ingredient(id = 2, amount = 1.0, originalText = "en nypa salt ")
        assertEquals("1 en nypa salt", (IngredientDisplay.row(freeText, 1.0, decimals, NumberStyle.Swedish) as IngredientRow.Item).text())
        val onHand = Ingredient(id = 3, food = Food(3, "salt", onHand = true), amount = 1.0)
        assertTrue((IngredientDisplay.row(onHand, 1.0, decimals, NumberStyle.Swedish) as IngredientRow.Item).onHand)
    }
}
