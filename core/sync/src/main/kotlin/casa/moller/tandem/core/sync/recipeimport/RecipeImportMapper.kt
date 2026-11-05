package casa.moller.tandem.core.sync.recipeimport

import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.ImportDuplicate
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.network.dto.RecipeFromSourceResponseDto
import casa.moller.tandem.core.network.dto.SourceImportIngredientDto
import casa.moller.tandem.core.network.dto.SourceImportRecipeDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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

    // --- #40: ai-step-sort (Fas 3's leftovers) -----------------------------------------------------

    /**
     * [draft] in the import's own shape (`SourceImportRecipe`, what Tandoor's import page sends to
     * `ai-step-sort`): foods and units by name, every line's text as `original_text`, and the
     * `order` of every step and line, which the server's `RecipeSerializer` requires.
     */
    fun toSourceRecipe(draft: RecipeDraft): JsonObject = buildJsonObject {
        put("name", draft.name)
        put("description", draft.description)
        put("servings", draft.servings)
        put("servings_text", draft.servingsText)
        put("working_time", draft.workingMinutes)
        put("waiting_time", draft.waitingMinutes)
        put("source_url", draft.sourceUrl)
        put("internal", true)
        put("keywords", buildJsonArray { draft.keywords.forEach { add(buildJsonObject { put("name", it.name) }) } })
        put(
            "steps",
            buildJsonArray {
                draft.steps.forEachIndexed { stepIndex, step ->
                    add(
                        buildJsonObject {
                            put("instruction", step.instruction)
                            put("order", stepIndex)
                            put("show_ingredients_table", step.showIngredientsTable)
                            val lines = step.ingredients.filterNot { it.isHeader }
                            // `order` is required (400 "This field may not be null.", FACTS "Fas 4: Fas 3:s rester" b).
                            put("ingredients", buildJsonArray { lines.forEachIndexed { order, line -> add(sourceLine(line, order)) } })
                        },
                    )
                }
            },
        )
    }

    private fun sourceLine(line: DraftIngredient, order: Int): JsonObject = buildJsonObject {
        put("amount", if (line.noAmount) 0.0 else line.amount)
        put("order", order)
        put("food", line.food?.let { food -> buildJsonObject { put("name", food) } } ?: JsonNull)
        put("unit", line.unit?.let { unit -> buildJsonObject { put("name", unit) } } ?: JsonNull)
        put("note", line.note)
        val amount = line.amount.takeIf { !line.noAmount && it > 0 }?.toString()
        put("original_text", listOfNotNull(amount, line.unit, line.food).joinToString(" "))
    }

    /**
     * The AI's answer as [before] with its steps replaced, or [RecipeImportProblem.STEP_SORT_CHANGED]
     * when the answer is not the same recipe sorted: the number of steps and the ingredient lines
     * (food, unit, amount) must be the same, only their places may change (plan §9: AI that drops
     * or invents steps is the most common complaint). `{error: true}` is the AI being unavailable.
     */
    fun sortedDraft(answer: JsonObject, before: RecipeDraft): Outcome<RecipeDraft> {
        if ((answer["error"] as? JsonPrimitive)?.booleanOrNull == true) {
            return Outcome.Failure(RecipeImportProblem.AI_UNAVAILABLE.toError())
        }
        val parsed = runCatching { lenient.decodeFromJsonElement(SourceImportRecipeDto.serializer(), answer) }.getOrNull()
            ?: return Outcome.Failure(RecipeImportProblem.STEP_SORT_CHANGED.toError())
        val steps = parsed.steps.map { step ->
            DraftStep(
                instruction = step.instruction,
                showIngredientsTable = step.showIngredientsTable,
                ingredients = step.ingredients.map(::toIngredient),
            )
        }
        val beforeSteps = before.steps.filterNot { it.isEmpty }
        val sorted = before.copy(
            steps = steps.mapIndexed { index, step ->
                val old = beforeSteps.getOrNull(index)
                // The step's own texts stay the user's: only the lines move.
                old?.copy(ingredients = step.ingredients) ?: step
            },
        )
        val same = steps.size == beforeSteps.size && lineKeys(sorted) == lineKeys(before)
        return if (same) Outcome.Success(sorted) else Outcome.Failure(RecipeImportProblem.STEP_SORT_CHANGED.toError())
    }

    private fun lineKeys(draft: RecipeDraft): List<String> = draft.steps.flatMap { it.ingredients }
        .filterNot { it.isHeader }
        .map { line ->
            val amount = if (line.noAmount) "" else line.amount.toString()
            listOf(line.food?.lowercase().orEmpty(), line.unit?.lowercase().orEmpty(), amount).joinToString("|")
        }
        .sorted()

    @OptIn(ExperimentalSerializationApi::class)
    private val lenient = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }
}
