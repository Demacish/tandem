package casa.moller.tandem.core.data.recipe

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.PropertyType
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import kotlinx.coroutines.flow.Flow

/**
 * Fas 3 (grund lane): creating, editing and deleting recipes in the app (plan §8.9, feature #22),
 * offline first like every other write (docs/BYGGE.md "Fas 3 – kontrakt"):
 *
 * - A new recipe is stored at once under a temporary (negative) id and queued as a `recipe_edit`
 *   CREATE (`POST /api/recipe/` with the whole recipe; foods, units and keywords by name). It
 *   shows in the list and opens like any recipe; once sent it gets its server id.
 * - An edit replaces the stored copy at once and queues a PATCH with the WHOLE recipe (steps and
 *   ingredients with their ids, so Tandoor updates them in place and deletes the omitted ones).
 *   The sender reads the recipe fresh first: when it changed on the server since the edit started
 *   (`updated_at`), the edit is NOT sent ([RecipeEditStatus.CONFLICT]), the server's version is
 *   stored and the edit is kept so the editor can open it again.
 * - A delete removes the recipe locally at once and queues `DELETE /api/recipe/{id}/`.
 * - A photo chosen in the editor is copied (scaled) and uploaded after the recipe as
 *   `RECIPE_IMAGE`, like a drink's.
 * - A file chosen for a step is copied and stands in the row by its path; the sender uploads it
 *   (`POST /api/user-file/`) right before the recipe goes.
 * - Guests get `DataProblem.READ_ONLY` before anything is stored; demo mode writes the demo
 *   database directly. Implemented by the redigerare lane in :core:sync.
 */
interface RecipeEditRepository {
    /** The stored units (for the unit suggestions), by name. Works offline. */
    fun observeUnits(): Flow<List<MeasurementUnit>>

    /** The space's property types ("Energi", "kcal"), for the recipe's own nutrition. Works offline, from the last [refreshPropertyTypes]. */
    fun observePropertyTypes(): Flow<List<PropertyType>>

    /** Reads the property types from the server; nothing happens offline or in demo mode. */
    suspend fun refreshPropertyTypes(): Outcome<Unit>

    /**
     * The recipes that use recipe [recipeId], also through another one, as far as the device knows
     * (the steps of stored recipes). Tandoor takes a circle of sub-recipes, so the editor leaves them out.
     */
    fun observeRecipesUsing(recipeId: Long): Flow<Set<Long>>

    /**
     * The editor's starting point for recipe [recipeId]: the edit still queued or kept after a
     * conflict (so nothing typed is lost), else the stored recipe; null while the recipe is not
     * stored on the device (the editor then needs the detail first).
     */
    fun observeDraft(recipeId: Long): Flow<RecipeDraft?>

    /** Where this device's edit of recipe [recipeId] stands (a recipe with a temporary id is [RecipeEditStatus.QUEUED]). */
    fun observeEditStatus(recipeId: Long): Flow<RecipeEditStatus>

    /** Creates a recipe; the id is temporary (negative) until the server answered. */
    suspend fun createRecipe(draft: RecipeDraft): Outcome<Long>

    /** Replaces recipe [recipeId] with [draft] (locally at once, on the server when sent). */
    suspend fun updateRecipe(recipeId: Long, draft: RecipeDraft): Outcome<Unit>

    /** Deletes recipe [recipeId] (locally at once; a recipe not sent yet is simply dropped). */
    suspend fun deleteRecipe(recipeId: Long): Outcome<Unit>

    /** Forgets a kept edit of [recipeId] that was not sent (the user chose the server's version). */
    suspend fun discardEdit(recipeId: Long): Outcome<Unit>
}

/** Fas 3: why a recipe write was refused before anything was stored, or why it was not sent (`TandemError.Unknown(detail)`). */
enum class RecipeEditProblem(val detail: String) {
    /** The name is empty. */
    NAME_EMPTY("tandem.recipe.name-empty"),

    /** The recipe is not stored on this device (edit needs the detail). */
    UNKNOWN_RECIPE("tandem.recipe.unknown-recipe"),

    /** The chosen photo could not be read or copied; nothing was saved. */
    PHOTO_UNREADABLE("tandem.recipe.photo-unreadable"),

    /** A step's chosen file could not be read or copied; nothing was saved. */
    STEP_FILE_UNREADABLE("tandem.recipe.step-file-unreadable"),

    /** The sender's code: the recipe changed on the server since the edit started, so the edit was kept, not sent. */
    CONFLICT("tandem.recipe.conflict"),
    ;

    fun toError(): TandemError = TandemError.Unknown(detail)

    companion object {
        /** The [RecipeEditProblem] this error stands for, or null. */
        fun of(error: TandemError): RecipeEditProblem? = entries.firstOrNull { (error as? TandemError.Unknown)?.detail == it.detail }
    }
}
