package casa.moller.tandem.feature.recipes.detail

import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.domain.scaling.FormatAmount
import casa.moller.tandem.core.domain.scaling.ScaleIngredients
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Ingredient
import java.util.Locale

/**
 * An ingredient row of the recipe detail, scaled to the chosen servings (plan 6.2, feature #4).
 * Built by the ViewModel; turned into text with [amountText] / [nameText] in the UI's locale.
 *
 * @property id the Tandoor ingredient id (key for strike-through and the unit choice).
 * @property isHeader a section title row ("Deg", "Fyllning"): only [title] is shown.
 * @property title the header text, or the free text of a row without a food.
 * @property units the amount in every unit the row can be shown in, scaled. The first entry is
 *   the recipe's own unit; the rest come from the server's `conversions` (duplicates of the
 *   own unit removed). Empty for "no amount" rows and headers.
 * @property foodName the food's singular name; empty for free-text rows.
 * @property foodPluralName the food's plural name, if the user entered one.
 * @property note the note after the food ("pressade").
 */
@Immutable
data class IngredientItem(
    val id: Long,
    val isHeader: Boolean = false,
    val title: String = "",
    val units: List<UnitAmount> = emptyList(),
    val foodName: String = "",
    val foodPluralName: String? = null,
    val note: String? = null,
) {
    /** The row can show its amount in more than one unit (tap to switch). */
    val canSwitchUnit: Boolean
        get() = units.size > 1

    /** The amount in the unit picked with [unitIndex] (wraps around), or null for "no amount". */
    fun unitAmount(unitIndex: Int): UnitAmount? = if (units.isEmpty()) null else units[Math.floorMod(unitIndex, units.size)]

    /**
     * "2 ½ dl", "400 g", "3" (no unit) or null when there is no amount to show (also for 0). Follows the
     * user's Tandoor preferences: fractions when `use_fractions` is on, otherwise
     * `ingredient_decimals` decimals, decimal separator from [locale]. Units in plural when the
     * shown amount is not 1.
     */
    fun amountText(unitIndex: Int, locale: Locale, preferences: UserPreferences): String? {
        val shown = unitAmount(unitIndex) ?: return null
        if (shown.amount <= 0.0) return null
        val number = FormatAmount(shown.amount, locale, preferences.useFractions, preferences.ingredientDecimals)
        val unit = shown.unitText(plural = number != ONE)
        return if (unit.isBlank()) number else "$number $unit"
    }

    /** The food in singular or plural (plural when the amount is not 1), or the free text. */
    fun nameText(): String {
        if (foodName.isEmpty()) return title
        val amount = units.firstOrNull()?.amount
        val plural = foodPluralName?.takeIf { it.isNotBlank() }
        return if (plural != null && amount != null && amount > 0.0 && !amount.isOne()) plural else foodName
    }

    private fun Double.isOne(): Boolean = kotlin.math.abs(this - 1.0) < ONE_TOLERANCE

    private companion object {
        const val ONE = "1"
        const val ONE_TOLERANCE = 1e-9
    }
}

/**
 * An amount in one unit.
 *
 * @property amount the scaled amount.
 * @property unitName the unit's singular name; empty when the amount has no unit ("3 ägg").
 * @property unitPluralName the unit's plural name, if any.
 */
@Immutable
data class UnitAmount(val amount: Double, val unitName: String = "", val unitPluralName: String? = null) {
    fun unitText(plural: Boolean): String = if (plural) unitPluralName?.takeIf { it.isNotBlank() } ?: unitName else unitName
}

/**
 * Builds the display row of [ingredient] for a scaling [factor] (`servings / recipe.servings`).
 * Headers and "no amount" rows are never scaled (core:domain ScaleIngredients semantics).
 */
fun ingredientItem(ingredient: Ingredient, factor: Double): IngredientItem {
    if (ingredient.isHeader) {
        val title = ingredient.note?.takeIf { it.isNotBlank() } ?: ingredient.food?.name ?: ingredient.originalText.orEmpty()
        return IngredientItem(id = ingredient.id, isHeader = true, title = title)
    }
    val food = ingredient.food
    val units = if (ingredient.noAmount) {
        emptyList()
    } else {
        val own = UnitAmount(
            amount = ScaleIngredients.amount(ingredient.amount, factor),
            unitName = ingredient.unit?.name.orEmpty(),
            unitPluralName = ingredient.unit?.pluralName,
        )
        // Tandoor lists the ingredient's own unit first among its conversions; keep it once.
        val others = ingredient.conversions
            .filterNot { it.unitName.equals(own.unitName, ignoreCase = true) }
            .map { UnitAmount(ScaleIngredients.amount(it.amount, factor), it.unitName, it.unitPluralName) }
            .distinctBy { it.unitName.lowercase() }
        listOf(own) + others
    }
    return if (food == null) {
        IngredientItem(
            id = ingredient.id,
            title = ingredient.originalText?.takeIf { it.isNotBlank() } ?: ingredient.note.orEmpty(),
            units = units,
            note = null,
        )
    } else {
        IngredientItem(
            id = ingredient.id,
            units = units,
            foodName = food.name,
            foodPluralName = food.pluralName,
            note = ingredient.note?.takeIf { it.isNotBlank() },
        )
    }
}
