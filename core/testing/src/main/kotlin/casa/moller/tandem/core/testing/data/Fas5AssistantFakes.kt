package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.assistant.AssistantActions
import casa.moller.tandem.core.data.assistant.AssistantSettingsRepository
import casa.moller.tandem.core.model.assistant.AssistantAddition
import casa.moller.tandem.core.model.assistant.AssistantMealDay
import casa.moller.tandem.core.model.assistant.AssistantRecipe
import casa.moller.tandem.core.model.assistant.AssistantResult
import casa.moller.tandem.core.model.assistant.AssistantSettings
import casa.moller.tandem.core.model.assistant.AssistantShoppingItem
import casa.moller.tandem.core.model.error.Outcome
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

// Fas 5 (funktioner lane): the App Functions' switches and actions (plan §8.7, docs/BYGGE.md "App Functions").

/** [AssistantSettingsRepository] in memory; [failWrites] makes the setters fail like a full disk. */
class FakeAssistantSettingsRepository(initial: AssistantSettings = AssistantSettings()) : AssistantSettingsRepository {
    val state = MutableStateFlow(initial)

    @Volatile
    var failWrites: Boolean = false

    override val settings: Flow<AssistantSettings> = state

    override suspend fun setEnabled(enabled: Boolean): Outcome<Unit> = write { it.copy(enabled = enabled) }

    override suspend fun setAllowWhenLocked(allowed: Boolean): Outcome<Unit> = write { it.copy(allowWhenLocked = allowed) }

    private fun write(transform: (AssistantSettings) -> AssistantSettings): Outcome<Unit> {
        if (failWrites) return Outcome.Failure(DataProblem.SETTINGS_NOT_SAVED.toError())
        state.update(transform)
        return Outcome.Success(Unit)
    }
}

/**
 * [AssistantActions] with fixed answers, for the App Functions' own tests in :app (the real rules
 * are tested in core:sync's `DefaultAssistantActionsTest`). Every call is recorded in [calls].
 */
class FakeAssistantActions : AssistantActions {
    val calls: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var addition: AssistantResult<AssistantAddition> = AssistantResult.Done(AssistantAddition(emptyList()))

    @Volatile
    var items: AssistantResult<List<AssistantShoppingItem>> = AssistantResult.Done(emptyList())

    @Volatile
    var meals: (LocalDate?) -> AssistantResult<AssistantMealDay> = { date ->
        AssistantResult.Done(AssistantMealDay(date ?: LocalDate(2026, 11, 15), emptyList()))
    }

    @Volatile
    var recipes: AssistantResult<List<AssistantRecipe>> = AssistantResult.Done(emptyList())

    @Volatile
    var recipe: AssistantResult<AssistantRecipe> = AssistantResult.Done(AssistantRecipe(1, "Pannkakor"))

    override suspend fun addToShoppingList(
        text: String,
        amount: Double?,
        unit: String?,
        list: String?,
    ): AssistantResult<AssistantAddition> {
        calls += "add($text, $amount, $unit, $list)"
        return addition
    }

    override suspend fun shoppingList(list: String?): AssistantResult<List<AssistantShoppingItem>> {
        calls += "list($list)"
        return items
    }

    override suspend fun mealPlan(date: LocalDate?): AssistantResult<AssistantMealDay> {
        calls += "meals($date)"
        return meals(date)
    }

    override suspend fun searchRecipes(query: String, limit: Int): AssistantResult<List<AssistantRecipe>> {
        calls += "search($query, $limit)"
        return recipes
    }

    override suspend fun recipe(recipeId: Long): AssistantResult<AssistantRecipe> {
        calls += "recipe($recipeId)"
        return recipe
    }
}
