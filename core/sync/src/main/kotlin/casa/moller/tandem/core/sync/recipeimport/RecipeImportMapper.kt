package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.ImportDuplicate
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.network.dto.RecipeFromSourceResponseDto
import casa.moller.tandem.core.network.dto.SourceImportIngredientDto
import casa.moller.tandem.core.network.dto.SourceImportRecipeDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Fas 3 (import lane): the server's import answer (`RecipeFromSourceResponse`, FACTS "Fas 3 e")
 * as the draft the editor opens, as Tandoor's own import page would create it:
 *
 * - foods and units by name (an empty name is none); a line with no food keeps the source's
 *   `original_text` as its note, so nothing of the source is lost; an amount of 0 is "no amount";
 * - the keywords the source carried (`import_keyword` is false for all of them in the
 *   recordings, so it is not a filter; the editor's chips drop the unwanted ones);
 * - the picture: the recipe's `image_url`, else the first of `images`;
 * - the duplicates the server found (same name or source), for the warning.
 */
internal object RecipeImportMapper {
    /** The result of [dto], or null when it carries no recipe with a name. */
    fun toResult(dto: RecipeFromSourceResponseDto): RecipeImportResult? {
        val recipe = dto.recipe ?: return null
        if (recipe.name.isBlank()) return null
        val images = (listOf(recipe.imageUrl) + dto.images).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return RecipeImportResult(
            draft = toDraft(recipe, imageUrl = images.firstOrNull()),
            imageUrls = images,
            duplicates = duplicatesOf(dto.duplicates),
        )
    }

    /** `[{id, name}]`, raw in the DTO (the recordings carry no duplicates, see `ImportDtos.kt`). */
    private fun duplicatesOf(element: JsonElement?): List<ImportDuplicate> = (element as? JsonArray).orEmpty().mapNotNull { item ->
        val duplicate = item as? JsonObject ?: return@mapNotNull null
        val id = (duplicate["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        ImportDuplicate(id, (duplicate["name"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    private fun toDraft(recipe: SourceImportRecipeDto, imageUrl: String?): RecipeDraft = RecipeDraft(
        name = recipe.name,
        description = recipe.description,
        servings = recipe.servings.coerceAtLeast(1),
        servingsText = recipe.servingsText,
        workingMinutes = recipe.workingTime.coerceAtLeast(0),
        waitingMinutes = recipe.waitingTime.coerceAtLeast(0),
        sourceUrl = recipe.sourceUrl,
        keywords = recipe.keywords
            .map { DraftKeyword(id = it.id?.takeIf { id -> id > 0 }, name = it.name.ifBlank { it.label }) }
            .filter { it.name.isNotBlank() },
        steps = recipe.steps.map { step ->
            DraftStep(
                instruction = step.instruction,
                showIngredientsTable = step.showIngredientsTable,
                ingredients = step.ingredients.map(::toIngredient),
            )
        },
        imageUrl = imageUrl,
    ).normalized()

    private fun toIngredient(line: SourceImportIngredientDto): DraftIngredient {
        val food = line.food?.name?.trim()?.takeIf { it.isNotEmpty() }
        val note = line.note.trim().ifEmpty { if (food == null) line.originalText.trim() else "" }
        return DraftIngredient(
            amount = line.amount.coerceAtLeast(0.0),
            unit = line.unit?.name?.trim()?.takeIf { it.isNotEmpty() },
            food = food,
            note = note,
            noAmount = line.amount <= 0.0,
        )
    }
}
