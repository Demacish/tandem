package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.shopping.ShoppingViewPreferences
import casa.moller.tandem.core.data.shopping.UnitConversionRepository
import casa.moller.tandem.core.model.shopping.ConversionUnit
import casa.moller.tandem.core.model.shopping.UnitConversionRule
import casa.moller.tandem.core.model.shopping.UnitConversions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Fas 5, lane handla (plan §8.2): fakes for merged rows and the grid on the shopping list.

/** [UnitConversionRepository] with settable [conversions] (none by default). */
class FakeUnitConversionRepository(initial: UnitConversions = UnitConversions.NONE) : UnitConversionRepository {
    val conversions = MutableStateFlow(initial)

    override fun observeConversions(): Flow<UnitConversions> = conversions
}

/** [ShoppingViewPreferences] in memory (rows by default). */
class FakeShoppingViewPreferences(grid: Boolean = false) : ShoppingViewPreferences {
    val grid = MutableStateFlow(grid)

    override val gridView: Flow<Boolean> = this.grid

    override suspend fun setGridView(enabled: Boolean) {
        grid.value = enabled
    }
}

/**
 * Units and conversions like a Swedish space (the contract server's, FACTS "Fas 5"): g, kg, ml and l
 * with base units, dl without one, st, the general 1 dl = 100 ml and 1 dl grädde = 100 g for the
 * food [CREAM_FOOD_ID]. The ids match [SampleShopping]'s units where it has them.
 */
object SampleConversions {
    const val CREAM_FOOD_ID: Long = 39

    val units: List<ConversionUnit> = listOf(
        ConversionUnit(1, "g", "g"),
        ConversionUnit(2, "kg", "kg"),
        ConversionUnit(3, "ml", "ml"),
        ConversionUnit(4, "l", "l"),
        ConversionUnit(5, "dl", null),
        ConversionUnit(6, "st", null),
    )

    val conversions: UnitConversions = UnitConversions(
        units = units,
        rules = listOf(
            UnitConversionRule(1, baseUnitId = 5, baseAmount = 1.0, convertedUnitId = 3, convertedAmount = 100.0),
            UnitConversionRule(3, baseUnitId = 5, baseAmount = 1.0, convertedUnitId = 1, convertedAmount = 100.0, foodId = CREAM_FOOD_ID),
        ),
    )
}
