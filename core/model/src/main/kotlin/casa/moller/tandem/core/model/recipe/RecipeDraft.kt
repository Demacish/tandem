package casa.moller.tandem.core.model.recipe

/**
 * Fas 3 (grund lane): what the recipe editor edits and saves (plan §8.9 "Skapa och redigera
 * recept", feature #22): a recipe as the user writes it, without server ids for anything new.
 * Foods, units and keywords go by NAME (Tandoor reuses or creates them by name, case ignored,
 * FACTS "Fas 1 a"); a keyword the device knows carries its id too, so a rename on the web never
 * duplicates it. Steps and ingredients keep the ids they had ([DraftStep.id], [DraftIngredient.id])
 * so an edit updates them in place and keeps what the app does not edit (a step's file, a linked
 * sub-recipe); new ones have none.
 *
 * Plain Kotlin (decision B2): the JSON of the request and of the saved state is built in :core:sync
 * and the feature, never here.
 *
 * @property imageUri a NEW photo (`content://` from the picker or camera, `file://` in tests), or
 *   null to keep the recipe's current image ([keepImage]).
 * @property imageUrl Fas 3 (import lane): a picture on the WEB the recipe should get when it is
 *   created (the source's picture of an import; Tandoor downloads it, `image_url`). Only used
 *   when [imageUri] is null; the editor shows it as the recipe's picture.
 * @property keepImage whether the recipe keeps its stored image when [imageUri] and [imageUrl]
 *   are null. False = Fas 3's leftovers "Ta bort bilden": the picture is removed on the server
 *   (`PUT /api/recipe/{id}/image/` with an empty `image_url`, FACTS "Fas 4: Fas 3:s rester" c).
 * @property currentImageUrl the stored recipe's picture when the edit started (shown in the
 *   editor, never sent); null for a new recipe or one without a picture.
 */
data class RecipeDraft(
    val name: String = "",
    val description: String = "",
    val servings: Int = 1,
    val servingsText: String = "",
    val workingMinutes: Int = 0,
    val waitingMinutes: Int = 0,
    val sourceUrl: String = "",
    val keywords: List<DraftKeyword> = emptyList(),
    val steps: List<DraftStep> = emptyList(),
    val imageUri: String? = null,
    val isPrivate: Boolean = false,
    val keepImage: Boolean = true,
    val imageUrl: String? = null,
    val currentImageUrl: String? = null,
) {
    /** Every ingredient of every step, in order (headers included). */
    val ingredients: List<DraftIngredient>
        get() = steps.flatMap { it.ingredients }

    /** The same draft with trimmed texts, empty steps dropped and the orders renumbered: what is saved. */
    fun normalized(): RecipeDraft = copy(
        name = name.trim(),
        description = description.trim(),
        servings = servings.coerceAtLeast(1),
        servingsText = servingsText.trim(),
        workingMinutes = workingMinutes.coerceAtLeast(0),
        waitingMinutes = waitingMinutes.coerceAtLeast(0),
        sourceUrl = sourceUrl.trim(),
        imageUrl = imageUrl?.trim()?.takeIf { it.isNotEmpty() },
        keywords = keywords.map { it.copy(name = it.name.trim()) }
            .filter { it.name.isNotEmpty() }
            .distinctBy { it.id ?: it.name.lowercase() },
        steps = steps.map { step -> step.normalized() }.filterNot { it.isEmpty },
    )

    companion object {
        /** The editor's starting point for a stored recipe. */
        fun from(recipe: Recipe): RecipeDraft = RecipeDraft(
            name = recipe.name,
            description = recipe.description.orEmpty(),
            servings = recipe.servings,
            servingsText = recipe.servingsText,
            workingMinutes = recipe.workingMinutes,
            waitingMinutes = recipe.waitingMinutes,
            sourceUrl = recipe.sourceUrl.orEmpty(),
            keywords = recipe.keywords.map { DraftKeyword(id = it.id, name = it.name) },
            steps = recipe.steps.sortedBy { it.order }.map { step ->
                DraftStep(
                    id = step.id.takeIf { it > 0 },
                    name = step.name,
                    instruction = step.instruction,
                    timeMinutes = step.timeMinutes,
                    ingredients = step.ingredients.sortedBy { it.order }.map { ingredient ->
                        DraftIngredient(
                            id = ingredient.id.takeIf { it > 0 },
                            amount = ingredient.amount,
                            unit = ingredient.unit?.name,
                            food = ingredient.food?.name,
                            note = ingredient.note.orEmpty(),
                            isHeader = ingredient.isHeader,
                            noAmount = ingredient.noAmount,
                        )
                    },
                    showAsHeader = step.showAsHeader,
                    showIngredientsTable = step.showIngredientsTable,
                )
            },
            isPrivate = recipe.isPrivate,
            currentImageUrl = recipe.imageUrl,
        )
    }
}

/** A keyword of a draft: by [id] when the device knows it, else created (or reused) by [name]. */
data class DraftKeyword(val id: Long? = null, val name: String)

/**
 * One step. [id] is the server's step id for a stored step (kept so an edit updates it in place),
 * null for a new one.
 */
data class DraftStep(
    val id: Long? = null,
    val name: String = "",
    val instruction: String = "",
    val timeMinutes: Int = 0,
    val ingredients: List<DraftIngredient> = emptyList(),
    val showAsHeader: Boolean = false,
    val showIngredientsTable: Boolean = true,
) {
    /** Nothing written in it: dropped when saving. */
    val isEmpty: Boolean
        get() = name.isBlank() && instruction.isBlank() && ingredients.none { !it.isEmpty }

    fun normalized(): DraftStep = copy(
        name = name.trim(),
        instruction = instruction.trim(),
        timeMinutes = timeMinutes.coerceAtLeast(0),
        ingredients = ingredients.map { it.normalized() }.filterNot { it.isEmpty },
    )
}

/**
 * One ingredient line. A header ([isHeader]) has its text in [note] and no food, unit or amount;
 * [noAmount] shows the food without an amount ("salt"). [unit] and [food] are names.
 */
data class DraftIngredient(
    val id: Long? = null,
    val amount: Double = 0.0,
    val unit: String? = null,
    val food: String? = null,
    val note: String = "",
    val isHeader: Boolean = false,
    val noAmount: Boolean = false,
) {
    /** Nothing written in it: dropped when saving. */
    val isEmpty: Boolean
        get() = if (isHeader) note.isBlank() else food.isNullOrBlank() && note.isBlank()

    fun normalized(): DraftIngredient = if (isHeader) {
        DraftIngredient(id = id, note = note.trim(), isHeader = true)
    } else {
        copy(
            amount = if (noAmount) 0.0 else amount.coerceAtLeast(0.0),
            unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            food = food?.trim()?.takeIf { it.isNotEmpty() },
            note = note.trim(),
        )
    }
}

/**
 * Where an edit of a recipe stands on this device (the detail's notice, the editor's warning).
 *
 * - [NONE]: nothing queued.
 * - [QUEUED]: a create or edit waits to be sent (offline, or not sent yet); a recipe created in the
 *   app has a temporary (negative) id until then.
 * - [CONFLICT]: the edit was not sent because the recipe changed on the server meanwhile
 *   (`updated_at` differs from the copy the edit started from); the server's version is shown and
 *   the edit is kept so it can be opened again in the editor.
 * - [FAILED]: the edit was refused (a 400, a guest's 403) and is kept in "Pending changes".
 * - [REFUSED_NEW]: Fas 4 review (fix-data): the server refused a recipe created in the app, so this
 *   device has the only copy. The way forward is the editor: a new save sends it again.
 */
enum class RecipeEditStatus { NONE, QUEUED, CONFLICT, FAILED, REFUSED_NEW }
