package casa.moller.tandem.feature.recipes.detail

import casa.moller.tandem.core.domain.scaling.FormatAmount
import casa.moller.tandem.core.domain.scaling.ScaleIngredients
import casa.moller.tandem.core.model.preferences.UserPreferences
import java.util.Locale

/**
 * Renders Tandoor's step templates in an instruction before it is shown as Markdown (plan 8.1,
 * api-schema/fixtures/2.6.13/FACTS.md "Fas 1 d"):
 *
 * - `{{ scale(100) }}` becomes the number scaled to the chosen servings ("200" for twice the
 *   servings), formatted like ingredient amounts;
 * - `{{ ingredients[0] }}` becomes the step's first ingredient ("2 ½ dl vispgrädde"), and
 *   `.amount`, `.unit`, `.food`, `.note` its parts;
 * - any other `{{ … }}` loses its braces (what the server does with code it cannot render).
 *
 * HOOK (recept-ui lane, plan "TemplateRenderer"): the domain lane provides the shared template
 * renderer in core:domain (also used by cook mode, Phase 1b). This small stand-in keeps the
 * recipe detail readable until then; replace the body of [render] with a call to it when it is
 * on main and keep this signature for the screen.
 */
object StepTemplates {
    private val template = Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL)
    private val scaleCall = Regex("^\\s*scale\\(\\s*(-?[0-9]+(?:[.,][0-9]+)?)\\s*\\)\\s*$")
    private val ingredientRef = Regex("^\\s*ingredients\\[\\s*(\\d+)\\s*\\](?:\\.(amount|unit|food|note))?\\s*$")

    /**
     * @param stepIngredients the step's ingredient rows in order (headers included), already scaled.
     * @param factor the scaling factor (`servings / recipe.servings`).
     */
    fun render(
        instruction: String,
        stepIngredients: List<IngredientItem>,
        factor: Double,
        locale: Locale,
        preferences: UserPreferences,
    ): String {
        if (!instruction.contains("{{")) return instruction
        return template.replace(instruction) { match ->
            val code = match.groupValues[1]
            scaleCall.matchEntire(code)?.let { scale ->
                val value = scale.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@replace code.trim()
                return@replace formatAmount(ScaleIngredients.amount(value, factor), locale, preferences)
            }
            ingredientRef.matchEntire(code)?.let { ref ->
                val item = ref.groupValues[1].toIntOrNull()?.let { stepIngredients.getOrNull(it) } ?: return@replace ""
                return@replace ingredientText(item, ref.groupValues[2], locale, preferences)
            }
            code.trim()
        }
    }

    private fun ingredientText(item: IngredientItem, part: String, locale: Locale, preferences: UserPreferences): String {
        val own = item.unitAmount(0)
        return when (part) {
            "amount" -> own?.takeIf { it.amount > 0.0 }?.let { formatAmount(it.amount, locale, preferences) }.orEmpty()

            "unit" -> own?.let { it.unitText(plural = it.amount != 1.0) }.orEmpty()

            "food" -> item.nameText()

            "note" -> item.note.orEmpty()

            else -> listOfNotNull(item.amountText(0, locale, preferences), item.nameText().takeIf { it.isNotBlank() }).joinToString(" ")
        }
    }

    private fun formatAmount(amount: Double, locale: Locale, preferences: UserPreferences): String =
        FormatAmount(amount, locale, preferences.useFractions, preferences.ingredientDecimals)
}
