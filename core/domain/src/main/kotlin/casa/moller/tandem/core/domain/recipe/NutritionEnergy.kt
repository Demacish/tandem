package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.model.recipe.NutritionValue

/**
 * Fas 3 (näringsvärden, plan §10 "Klart när": "kJ/kcal följer `use_kj`"): an energy value is shown
 * in the unit the user chose in Tandoor (`UserPreference.use_kj`). A property type whose unit is
 * kcal is converted to kJ when [useKj] is on, one in kJ to kcal when it is off; every other value
 * (and an energy type with another unit) is left exactly as the space defined it.
 */
object NutritionEnergy {
    /** Kilojoules per kilocalorie (thermochemical calorie). */
    const val KJ_PER_KCAL: Double = 4.184

    fun inPreferredUnit(values: List<NutritionValue>, useKj: Boolean): List<NutritionValue> = values.map { inPreferredUnit(it, useKj) }

    fun inPreferredUnit(value: NutritionValue, useKj: Boolean): NutritionValue {
        val unit = value.unit.trim().lowercase()
        return when {
            useKj && unit in KCAL -> value.copy(amount = value.amount * KJ_PER_KCAL, unit = KJ)
            !useKj && unit in KILOJOULE -> value.copy(amount = value.amount / KJ_PER_KCAL, unit = KCAL_UNIT)
            else -> value
        }
    }

    private const val KJ = "kJ"
    private const val KCAL_UNIT = "kcal"
    private val KCAL = setOf("kcal", "kilocalorie", "kilocalories")
    private val KILOJOULE = setOf("kj", "kilojoule", "kilojoules")
}
