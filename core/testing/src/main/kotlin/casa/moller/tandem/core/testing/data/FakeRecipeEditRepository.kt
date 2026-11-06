package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.data.recipe.RecipeEditRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.PropertyType
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// Fas 3 (grund lane): the fake of the recipe editor's contract in core:data (docs/BYGGE.md
// "Fas 3 – kontrakt"). In memory, deterministic and synchronous like the Fas S fakes: a new
// recipe gets a negative id (-1, -2, ...), `readOnly = true` makes every write fail with
// DataProblem.READ_ONLY, `failure` makes the next writes fail with that error.

/**
 * In-memory [RecipeEditRepository] over [SampleRecipes]: [drafts] holds the editor's starting
 * points by recipe id (the samples' recipes at first; a saved edit replaces its entry), [statuses]
 * what the detail shows.
 */
class FakeRecipeEditRepository(
    recipes: Map<Long, RecipeDraft> = SampleRecipes.details.associate { it.id to RecipeDraft.from(it) },
    units: List<MeasurementUnit> = SampleRecipes.units,
    var readOnly: Boolean = false,
) : RecipeEditRepository {
    val drafts = MutableStateFlow(recipes)
    val units = MutableStateFlow(units)
    val statuses = MutableStateFlow<Map<Long, RecipeEditStatus>>(emptyMap())
    val propertyTypes = MutableStateFlow<List<PropertyType>>(emptyList())
    var propertyTypeRefreshes = 0
    val recipesUsing = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())

    /** What was written, in order. */
    val created = mutableListOf<RecipeDraft>()
    val updated = mutableListOf<Pair<Long, RecipeDraft>>()
    val deleted = mutableListOf<Long>()
    val discarded = mutableListOf<Long>()
    var failure: TandemError? = null
    private var nextId = -1L

    override fun observeUnits(): Flow<List<MeasurementUnit>> = units.map { list -> list.sortedBy { it.name } }

    override fun observePropertyTypes(): Flow<List<PropertyType>> = propertyTypes

    override suspend fun refreshPropertyTypes(): Outcome<Unit> {
        propertyTypeRefreshes++
        return Outcome.Success(Unit)
    }

    override fun observeRecipesUsing(recipeId: Long): Flow<Set<Long>> = recipesUsing.map { it[recipeId].orEmpty() }

    override fun observeDraft(recipeId: Long): Flow<RecipeDraft?> = drafts.map { it[recipeId] }

    override fun observeEditStatus(recipeId: Long): Flow<RecipeEditStatus> = combine(statuses, drafts) { all, _ ->
        all[recipeId] ?: if (recipeId < 0) RecipeEditStatus.QUEUED else RecipeEditStatus.NONE
    }

    override suspend fun createRecipe(draft: RecipeDraft): Outcome<Long> = write {
        if (draft.name.isBlank()) return Outcome.Failure(RecipeEditProblem.NAME_EMPTY.toError())
        val id = nextId--
        created += draft.normalized()
        drafts.update { it + (id to draft.normalized()) }
        id
    }

    override suspend fun updateRecipe(recipeId: Long, draft: RecipeDraft): Outcome<Unit> = write {
        if (draft.name.isBlank()) return Outcome.Failure(RecipeEditProblem.NAME_EMPTY.toError())
        if (recipeId !in drafts.value) return Outcome.Failure(RecipeEditProblem.UNKNOWN_RECIPE.toError())
        updated += recipeId to draft.normalized()
        drafts.update { it + (recipeId to draft.normalized()) }
        statuses.update { it + (recipeId to RecipeEditStatus.QUEUED) }
    }

    override suspend fun deleteRecipe(recipeId: Long): Outcome<Unit> = write {
        deleted += recipeId
        drafts.update { it - recipeId }
        statuses.update { it - recipeId }
    }

    override suspend fun discardEdit(recipeId: Long): Outcome<Unit> = write {
        discarded += recipeId
        statuses.update { it - recipeId }
    }

    private inline fun <T> write(block: () -> T): Outcome<T> = when {
        readOnly -> Outcome.Failure(DataProblem.READ_ONLY.toError())
        failure != null -> Outcome.Failure(failure!!)
        else -> Outcome.Success(block())
    }
}
