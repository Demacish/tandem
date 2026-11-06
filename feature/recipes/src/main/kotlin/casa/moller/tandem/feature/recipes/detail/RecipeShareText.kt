package casa.moller.tandem.feature.recipes.detail

import casa.moller.tandem.core.domain.recipe.IngredientRow

/**
 * "Dela som text" (plan §6.2, §8.6): the recipe as the reader sees it right now, with the servings,
 * units and number style on the screen. The server's address is not in it: a text is for someone
 * who does not have an account, and the recipe's own source is only there when it has one.
 */
internal object RecipeShareText {
    /** The texts in the UI's language; [servings] is the whole line ("4 portioner"). */
    data class Labels(val servings: String, val ingredients: String, val steps: String, val source: String)

    fun of(state: RecipeDetailUiState, labels: Labels): String = listOfNotNull(
        heading(state, labels),
        ingredients(state, labels),
        steps(state, labels),
        state.sourceUrl?.let { "${labels.source} $it" },
    ).joinToString("\n\n")

    private fun heading(state: RecipeDetailUiState, labels: Labels): String {
        val servings = state.servings?.let { shown -> if (shown.text.isBlank()) labels.servings else "${shown.amountText} ${shown.text}" }
        return listOfNotNull(state.header?.name, state.header?.description?.takeIf { it.isNotBlank() }, servings).joinToString("\n")
    }

    /** The overview, or every step's rows when the recipe only shows its ingredients per step. */
    private fun ingredients(state: RecipeDetailUiState, labels: Labels): String? {
        val rows = state.ingredients.ifEmpty { state.steps.flatMap { it.ingredients } }
        if (rows.isEmpty()) return null
        return (listOf(labels.ingredients) + rows.map { line(it, state) }).joinToString("\n")
    }

    private fun line(row: IngredientRow, state: RecipeDetailUiState): String = when (row) {
        is IngredientRow.Header -> row.title

        is IngredientRow.Item -> {
            val text = row.text(state.unitChoices[row.id] ?: 0)
            "- " + if (row.note.isEmpty()) text else "$text, ${row.note}"
        }
    }

    private fun steps(state: RecipeDetailUiState, labels: Labels): String? {
        val steps = state.steps.filter { it.name.isNotBlank() || it.instruction.isNotBlank() }
        if (steps.isEmpty()) return null
        val numbered = steps.mapIndexed { index, step ->
            "${index + 1}. " + listOf(step.name, step.instruction.trim()).filter { it.isNotBlank() }.joinToString("\n")
        }
        return (listOf(labels.steps) + numbered).joinToString("\n")
    }
}
