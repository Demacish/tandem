package casa.moller.tandem.core.model.shopping

// Fas 5, lane handla (plan §8.2 "Sammanslagning i vyn", §6.7 "Enhetskonvertering via Tandoors
// conversions"): what the shopping list needs to show the same food in convertible units as one
// row. Tandoor's own data, cached per account with the small tables (docs/BYGGE.md "Sammanslagning
// och rutnät"); nothing here is an own translation table.

/**
 * A unit as the conversions see it (Tandoor `Unit`).
 *
 * @property baseUnit Tandoor's `base_unit` key (`g`, `kg`, `ml`, `l`, `tbsp`, `us_cup` …), null for a
 *   unit without one (Tandoor then uses the unit's NAME as the key, FACTS "Fas 5").
 */
data class ConversionUnit(val id: Long, val name: String, val baseUnit: String? = null)

/**
 * One of the space's own conversions (Tandoor `UnitConversion`): [baseAmount] of [baseUnitId] is
 * [convertedAmount] of [convertedUnitId], for [foodId] only or (null) for every food. Works in both
 * directions, as on the server.
 */
data class UnitConversionRule(
    val id: Long,
    val baseUnitId: Long,
    val baseAmount: Double,
    val convertedUnitId: Long,
    val convertedAmount: Double,
    val foodId: Long? = null,
)

/**
 * Everything the merging of shopping rows knows about units: the space's units with their base
 * unit keys and its own conversions. [NONE] when nothing is cached (a guest, who may not read
 * conversions; before the first sync): then only the same unit and Tandoor's base table by name merge.
 */
data class UnitConversions(val units: List<ConversionUnit> = emptyList(), val rules: List<UnitConversionRule> = emptyList()) {
    companion object {
        val NONE: UnitConversions = UnitConversions()
    }
}
