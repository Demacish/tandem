package casa.moller.tandem.core.domain.shopping

import casa.moller.tandem.core.model.shopping.ConversionUnit
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingRecipeLabel
import casa.moller.tandem.core.model.shopping.ShoppingUnit
import casa.moller.tandem.core.model.shopping.UnitConversionRule
import casa.moller.tandem.core.model.shopping.UnitConversions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Fas 5, lane handla (plan §8.2 "Sammanslagning i vyn"): "3 dl + 200 g grädde" → "5 dl".
class ShoppingMergeTest {
    // The units of a space like the contract server's (FACTS "Fas 5"): base units set on some,
    // dl without one (Tandoor's table has no dl), st and burk without.
    private val g = ConversionUnit(1, "g", "g")
    private val kg = ConversionUnit(2, "kg", "kg")
    private val ml = ConversionUnit(3, "ml", "ml")
    private val l = ConversionUnit(4, "l", "l")
    private val dl = ConversionUnit(5, "dl", null)
    private val st = ConversionUnit(8, "st", null)
    private val msk = ConversionUnit(6, "msk", "tbsp")

    private val cream = ShoppingFood(id = 70, name = "grädde")
    private val flour = ShoppingFood(id = 12, name = "vetemjöl")

    private val rules = listOf(
        UnitConversionRule(1, baseUnitId = dl.id, baseAmount = 1.0, convertedUnitId = ml.id, convertedAmount = 100.0),
        UnitConversionRule(2, baseUnitId = dl.id, baseAmount = 1.0, convertedUnitId = g.id, convertedAmount = 60.0, foodId = flour.id),
        UnitConversionRule(3, baseUnitId = dl.id, baseAmount = 1.0, convertedUnitId = g.id, convertedAmount = 100.0, foodId = cream.id),
    )
    private val space = UnitConversions(units = listOf(g, kg, ml, l, dl, st, msk), rules = rules)
    private val converter = UnitConverter(space)

    private var nextId = 1L

    private fun entry(
        food: ShoppingFood,
        amount: Double,
        unit: ConversionUnit?,
        checked: Boolean = false,
        recipe: String? = null,
        pending: Boolean = false,
    ) = ShoppingEntry(
        id = nextId++,
        food = food,
        unit = unit?.let { ShoppingUnit(it.id, it.name) },
        amount = amount,
        checked = checked,
        recipe = recipe?.let { ShoppingRecipeLabel(listRecipeId = it.hashCode().toLong(), recipeName = it) },
        pending = pending,
    )

    private fun text(row: MergedShoppingRow): String =
        row.quantities.joinToString(" + ") { q -> listOfNotNull(q.amount.toString(), q.unit?.name).joinToString(" ") }

    private fun merge(vararg entries: ShoppingEntry, with: UnitConverter = converter) = ShoppingMerge.merge(entries.toList(), with)

    @Test
    fun theSameUnitIsAdded() {
        val rows = merge(entry(cream, 2.0, dl), entry(cream, 3.0, dl))
        assertEquals(1, rows.size)
        assertEquals("5.0 dl", text(rows.single()))
        assertTrue(rows.single().isMerged)
    }

    @Test
    fun aFoodSpecificDensityTurnsGramsIntoTheMostUsedUnit() {
        // The plan's example: 3 dl + 200 g grädde → 5 dl (1 dl grädde = 100 g, only for grädde).
        val rows = merge(entry(cream, 3.0, dl), entry(cream, 200.0, g), entry(cream, 1.0, dl))
        assertEquals("6.0 dl", text(rows.single()))
        // A tie goes to the first entry's unit, in either direction.
        assertEquals("5.0 dl", text(merge(entry(cream, 3.0, dl), entry(cream, 200.0, g)).single()))
        assertEquals("500.0 g", text(merge(entry(cream, 200.0, g), entry(cream, 3.0, dl)).single()))
    }

    @Test
    fun aDensityIsOnlyForItsFood() {
        // vetemjöl has its own 1 dl = 60 g; grädde's 100 g never applies to it.
        assertEquals("2.0 dl", text(merge(entry(flour, 1.0, dl), entry(flour, 60.0, g)).single()))
        val other = ShoppingFood(id = 99, name = "socker")
        assertEquals("2.0 dl + 100.0 g", text(merge(entry(other, 2.0, dl), entry(other, 100.0, g)).single()))
    }

    @Test
    fun massAndVolumeFollowTandoorsBaseTable() {
        assertEquals("1500.0 g", text(merge(entry(cream, 500.0, g), entry(cream, 1.0, kg)).single()))
        assertEquals("1.5 kg", text(merge(entry(cream, 1.0, kg), entry(cream, 500.0, g)).single()))
        // 1 msk (tbsp) = 1000/67.628 ml.
        val water = ShoppingFood(id = 5, name = "vatten")
        assertEquals("1.014787 l", text(merge(entry(water, 1.0, l), entry(water, 1.0, msk)).single()))
    }

    @Test
    fun aCustomStepAndABaseStepChain() {
        // 1 l → 1000 ml (base) → 10 dl (the space's 1 dl = 100 ml, backwards). Tandoor's own order
        // misses this one (FACTS "Fas 5": no dl among 1 l's conversions); a factor works both ways.
        assertEquals("13.0 dl", text(merge(entry(cream, 3.0, dl), entry(cream, 1.0, l)).single()))
        assertEquals("1.3 l", text(merge(entry(cream, 1.0, l), entry(cream, 3.0, dl)).single()))
    }

    @Test
    fun withoutAConversionTheAmountsStayOnTheSameRow() {
        val rows = merge(entry(cream, 3.0, dl), entry(cream, 1.0, st), entry(cream, 2.0, dl))
        assertEquals(1, rows.size)
        assertEquals("5.0 dl + 1.0 st", text(rows.single()))
        // Without any conversion data only the same unit is added.
        val none = UnitConverter(UnitConversions.NONE)
        assertEquals("3.0 dl + 200.0 g", text(merge(entry(cream, 3.0, dl), entry(cream, 200.0, g), with = none).single()))
    }

    @Test
    fun theMostUsedUnitWins() {
        val rows = merge(entry(cream, 100.0, g), entry(cream, 1.0, dl), entry(cream, 200.0, g))
        assertEquals("400.0 g", text(rows.single()))
    }

    @Test
    fun sumsAreRoundedNotNoisy() {
        val rows = merge(entry(cream, 0.1, dl), entry(cream, 0.2, dl))
        assertEquals(0.3, rows.single().quantities.single().amount)
        // A density that does not divide evenly: 1 dl = 98 g.
        val milk = ShoppingFood(id = 6, name = "mjölk")
        val dense = UnitConverter(space.copy(rules = rules + UnitConversionRule(9, dl.id, 1.0, g.id, 98.0, foodId = milk.id)))
        assertEquals(4.020408, merge(entry(milk, 3.0, dl), entry(milk, 100.0, g), with = dense).single().quantities.single().amount)
    }

    @Test
    fun checkedAndUncheckedNeverShareARow() {
        val rows = merge(entry(cream, 2.0, dl), entry(cream, 3.0, dl, checked = true), entry(cream, 1.0, dl))
        assertEquals(2, rows.size)
        assertEquals(listOf("3.0 dl", "3.0 dl"), rows.map(::text))
        assertFalse(rows[0].checked)
        assertTrue(rows[1].checked)
        assertEquals(listOf(1L, 3L), rows[0].ids)
        assertEquals(listOf(2L), rows[1].ids)
    }

    @Test
    fun entriesFromDifferentRecipesKeepEveryLabel() {
        // Tandoor's list entries have no note (FACTS "Fas 5"); what tells them apart is the recipe.
        val rows = merge(
            entry(cream, 2.0, dl, recipe = "Pannkakor"),
            entry(cream, 1.0, dl, recipe = "Kladdkaka", pending = true),
            entry(cream, 1.0, dl, recipe = "Pannkakor"),
            entry(cream, 1.0, dl),
        )
        val row = rows.single()
        assertEquals(listOf("Pannkakor", "Kladdkaka"), row.recipeNames)
        assertEquals(4, row.entries.size)
        assertTrue(row.pending)
        assertTrue(3L in row)
        assertFalse(99L in row)
    }

    @Test
    fun entriesWithoutAnAmountJoinTheRowButAddNothing() {
        val milk = ShoppingFood(id = 6, name = "mjölk")
        assertEquals("1.0 l", text(merge(entry(milk, 0.0, null), entry(milk, 1.0, l)).single()))
        val only = merge(entry(milk, 0.0, null), entry(milk, 0.0, null)).single()
        assertTrue(only.quantities.isEmpty())
        assertEquals(2, only.entries.size)
    }

    @Test
    fun countsWithoutAUnitAreAdded() {
        val eggs = ShoppingFood(id = 7, name = "ägg")
        assertEquals("5.0", text(merge(entry(eggs, 2.0, null), entry(eggs, 3.0, null)).single()))
        assertEquals("6.0 + 1.0 st", text(merge(entry(eggs, 6.0, null), entry(eggs, 1.0, st)).single()))
    }

    @Test
    fun foodsMatchByNameAndOfflineUnitsByName() {
        // An entry made offline has no food id and its unit no id yet: they still join.
        val offline = ShoppingEntry(-4, food = ShoppingFood(id = null, name = " Grädde"), unit = ShoppingUnit(null, "DL"), amount = 1.0)
        val rows = merge(entry(cream, 200.0, g), offline, entry(cream, 1.0, dl))
        assertEquals("4.0 dl", text(rows.single()))
        // Different foods stay apart, in the order of their first entry.
        val apart = merge(entry(flour, 1.0, dl), entry(cream, 1.0, dl), entry(flour, 1.0, dl))
        assertEquals(listOf("vetemjöl", "grädde"), apart.map { it.first.food.name })
    }

    @Test
    fun aSingleEntryIsARowOfItsOwn() {
        val single = merge(entry(cream, 2.5, dl)).single()
        assertFalse(single.isMerged)
        assertEquals("2.5 dl", text(single))
        assertEquals(single.first.id, single.id)
        assertThrows(IllegalArgumentException::class.java) { MergedShoppingRow(emptyList(), emptyList()) }
    }

    @Test
    fun theConverterGivesFactorsBothWaysAndNullWithoutAPath() {
        val gramUnit = ShoppingUnit(g.id, g.name)
        val dlUnit = ShoppingUnit(dl.id, dl.name)
        assertEquals(100.0, converter.factor(dlUnit, gramUnit, cream.id))
        assertEquals(0.01, converter.factor(gramUnit, dlUnit, cream.id))
        assertNull(converter.factor(dlUnit, gramUnit, foodId = null))
        assertNull(converter.factor(dlUnit, ShoppingUnit(st.id, st.name), cream.id))
        assertEquals(1.0, converter.factor(null, null, cream.id))
        assertNull(converter.factor(null, dlUnit, cream.id))
        // A unit no table knows, by name only: Tandoor's base table by name ("ounce" → g).
        assertEquals(28.349, converter.factor(ShoppingUnit(null, "ounce"), gramUnit, null)!!, 0.001)
    }
}
