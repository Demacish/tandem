package casa.moller.tandem.feature.recipes.detail

import casa.moller.tandem.core.domain.scaling.FormatAmount
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.UnitConversion
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scaling, fractions, decimals, plurals and conversions of ingredient rows (plan 6.2, feature #4). */
class IngredientDisplayTest {

    private val sv = Locale.forLanguageTag("sv")
    private val en = Locale.ENGLISH
    private val decimals = UserPreferences.Default
    private val fractions = UserPreferences(useFractions = true)

    private val cup = MeasurementUnit(id = 7, name = "cup", pluralName = "cups")
    private val onion = Food(id = 1, name = "lök", pluralName = "lökar")

    @Test
    fun amountsAreScaledAndFormattedAfterThePreferences() {
        val item = ingredientItem(Ingredient(id = 1, food = onion, unit = MeasurementUnit(2, "dl"), amount = 1.5), factor = 1.5)
        assertEquals("2,25 dl", item.amountText(0, sv, decimals))
        assertEquals("2.25 dl", item.amountText(0, en, decimals))
        assertEquals("2${FormatAmount.MIXED_NUMBER_SEPARATOR}¼ dl", item.amountText(0, en, fractions))
        assertEquals("2,3 dl", item.amountText(0, sv, UserPreferences(ingredientDecimals = 1)))
    }

    @Test
    fun unitsAndFoodsArePluralWhenTheAmountIsNotOne() {
        val one = ingredientItem(Ingredient(id = 1, food = onion, unit = cup, amount = 1.0), factor = 1.0)
        assertEquals("1 cup", one.amountText(0, en, decimals))
        assertEquals("lök", one.nameText())

        val two = ingredientItem(Ingredient(id = 1, food = onion, unit = cup, amount = 1.0), factor = 2.0)
        assertEquals("2 cups", two.amountText(0, en, decimals))
        assertEquals("lökar", two.nameText())

        val half = ingredientItem(Ingredient(id = 1, food = onion, unit = cup, amount = 1.0), factor = 0.5)
        assertEquals("½ cups", half.amountText(0, en, fractions))
        assertEquals("lökar", half.nameText())
    }

    @Test
    fun foodsWithoutAPluralKeepTheirName() {
        val item = ingredientItem(Ingredient(id = 1, food = Food(2, "smör"), amount = 3.0), factor = 1.0)
        assertEquals("smör", item.nameText())
        assertEquals("3", item.amountText(0, sv, decimals))
    }

    @Test
    fun headersAndNoAmountRowsAreNeverScaled() {
        val header = ingredientItem(Ingredient(id = 1, note = "Deg", isHeader = true), factor = 3.0)
        assertTrue(header.isHeader)
        assertEquals("Deg", header.title)

        val salt = ingredientItem(Ingredient(id = 2, food = Food(3, "salt"), noAmount = true), factor = 3.0)
        assertNull(salt.amountText(0, sv, decimals))
        assertFalse(salt.canSwitchUnit)
        assertEquals("salt", salt.nameText())
    }

    @Test
    fun zeroAmountsShowNoNumber() {
        val item = ingredientItem(Ingredient(id = 1, food = onion, unit = cup, amount = 0.0), factor = 2.0)
        assertNull(item.amountText(0, en, decimals))
    }

    @Test
    fun conversionsAreScaledAndTheOwnUnitIsNotRepeated() {
        val cream = ingredientItem(
            Ingredient(
                id = 1,
                food = Food(4, "vetemjöl"),
                unit = MeasurementUnit(2, "dl"),
                amount = 2.5,
                conversions = listOf(
                    // Tandoor lists the ingredient itself first.
                    UnitConversion(2.5, "dl"),
                    UnitConversion(250.0, "ml"),
                    UnitConversion(150.0, "g"),
                    UnitConversion(250.0, "ML"),
                ),
            ),
            factor = 2.0,
        )
        assertTrue(cream.canSwitchUnit)
        assertEquals(listOf("dl", "ml", "g"), cream.units.map { it.unitName })
        assertEquals("5 dl", cream.amountText(0, sv, decimals))
        assertEquals("500 ml", cream.amountText(1, sv, decimals))
        assertEquals("300 g", cream.amountText(2, sv, decimals))
        assertEquals("5 dl", cream.amountText(3, sv, decimals))
    }

    @Test
    fun freeTextRowsShowTheirOriginalText() {
        val item = ingredientItem(Ingredient(id = 1, originalText = "en nypa kärlek", noAmount = true), factor = 1.0)
        assertEquals("en nypa kärlek", item.nameText())
        assertNull(item.amountText(0, sv, decimals))
    }

    @Test
    fun notesAreKeptAndBlankNotesDropped() {
        assertEquals("pressade", ingredientItem(Ingredient(id = 1, food = onion, note = "pressade"), 1.0).note)
        assertNull(ingredientItem(Ingredient(id = 1, food = onion, note = " "), 1.0).note)
    }
}
