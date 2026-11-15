package casa.moller.tandem.core.sync.assistant

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.assistant.AssistantActions
import casa.moller.tandem.core.data.assistant.AssistantLockCheck
import casa.moller.tandem.core.data.assistant.AssistantSettingsRepository
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.shopping.IngredientParser
import casa.moller.tandem.core.data.shopping.ShoppingInput
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.model.assistant.AssistantAddition
import casa.moller.tandem.core.model.assistant.AssistantMeal
import casa.moller.tandem.core.model.assistant.AssistantMealDay
import casa.moller.tandem.core.model.assistant.AssistantRecipe
import casa.moller.tandem.core.model.assistant.AssistantRefusal
import casa.moller.tandem.core.model.assistant.AssistantResult
import casa.moller.tandem.core.model.assistant.AssistantShoppingItem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import casa.moller.tandem.core.model.shopping.ShoppingList
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * Fas 5 (funktioner lane, plan §8.7): [AssistantActions] on the screens' own repositories, so an
 * assistant's "lägg till mjölk på listan" is the same outbox entry as typing "mjölk" into Handla's
 * "Lägg till" (core:data [ShoppingInput]), and reading works offline and in demo mode. See
 * docs/BYGGE.md "App Functions".
 */
internal class DefaultAssistantActions @Inject constructor(
    private val settings: AssistantSettingsRepository,
    private val lock: AssistantLockCheck,
    private val sessions: SessionRepository,
    private val permissions: SpacePermissionsRepository,
    private val shopping: ShoppingRepository,
    private val parser: IngredientParser,
    private val mealPlans: MealPlanRepository,
    private val recipes: RecipeRepository,
    private val days: CalendarDays,
    private val clock: Clock,
) : AssistantActions {

    override suspend fun addToShoppingList(
        text: String,
        amount: Double?,
        unit: String?,
        list: String?,
    ): AssistantResult<AssistantAddition> = guarded(write = true) {
        val items = ShoppingInput.splitItems(text)
        val unitName = unit?.trim()?.takeIf { it.isNotEmpty() }
        when {
            items.isEmpty() -> refuse(AssistantRefusal.NOTHING_TO_ADD)

            (amount != null || unitName != null) && items.size > 1 -> refuse(AssistantRefusal.AMOUNT_FOR_SEVERAL_ITEMS)

            amount != null && (!amount.isFinite() || amount < 0.0) -> refuse(AssistantRefusal.INVALID_AMOUNT)

            else -> withList(list) { target ->
                val listIds = target?.let { setOf(it.id) }.orEmpty()
                val drafts = ShoppingInput.parse(parser, items).map { parsed ->
                    ShoppingInput.draft(parsed, listIds).let { draft ->
                        draft.copy(
                            amount = amount ?: draft.amount,
                            // A unit given by name replaces the parsed one (and its id).
                            unitName = unitName ?: draft.unitName,
                            unitId = if (unitName != null) null else draft.unitId,
                        )
                    }
                }
                when (val added = shopping.addAll(drafts)) {
                    is Outcome.Success -> AssistantResult.Done(AssistantAddition(drafts.map { it.toItem(target) }, target?.name))
                    is Outcome.Failure -> failure(added.error)
                }
            }
        }
    }

    override suspend fun shoppingList(list: String?): AssistantResult<List<AssistantShoppingItem>> = guarded(write = false) {
        withList(list) { target ->
            val lists = shopping.observeLists().first().associate { it.id to it.name }
            val now = clock.now()
            val entries = shopping.observeEntries(ShoppingFilter(listId = target?.id)).first()
            AssistantResult.Done(
                entries.asSequence()
                    .filter { !it.checked }
                    .sortedWith(compareBy<ShoppingEntry> { it.isDelayed(now) }.thenBy { it.order }.thenBy { it.food.name.lowercase() })
                    .take(AssistantActions.MAX_ITEMS)
                    .map { it.toItem(lists, now) }
                    .toList(),
            )
        }
    }

    override suspend fun mealPlan(date: LocalDate?): AssistantResult<AssistantMealDay> = guarded(write = false) {
        val day = date ?: days.today()
        val week = mealPlans.observeWeek(MealPlanWeek.startOf(day)).first()
        val meals = week.days.firstOrNull { it.date == day }?.meals.orEmpty()
            .filter { it.isOn(day) }
            .sortedWith(compareBy({ it.mealType.order }, { it.mealType.time }))
            .map { meal ->
                AssistantMeal(
                    title = meal.displayTitle,
                    mealType = meal.mealType.name,
                    time = meal.mealType.time,
                    recipeId = meal.recipe?.id,
                    servings = meal.servings,
                    note = meal.note,
                )
            }
        AssistantResult.Done(AssistantMealDay(day, meals))
    }

    override suspend fun searchRecipes(query: String, limit: Int): AssistantResult<List<AssistantRecipe>> = guarded(write = false) {
        val text = query.trim()
        if (text.isEmpty()) return@guarded refuse(AssistantRefusal.EMPTY_QUERY)
        val count = if (limit <= 0) AssistantActions.DEFAULT_RECIPES else limit.coerceAtMost(AssistantActions.MAX_RECIPES)
        val found = recipes.observeRecipes(RecipeQuery(text = text)).first()
        AssistantResult.Done(
            found.take(count).map { recipe ->
                AssistantRecipe(recipe.id, recipe.name, recipe.totalMinutes.takeIf { it > 0 }, recipe.rating)
            },
        )
    }

    override suspend fun recipe(recipeId: Long): AssistantResult<AssistantRecipe> = guarded(write = false) {
        val stored = recipes.observeStoredRecipe(recipeId).first()
        val name = stored.overview?.name ?: stored.recipe?.name
        if (name == null || stored.isDeleted) {
            refuse(AssistantRefusal.RECIPE_NOT_FOUND)
        } else {
            val minutes = stored.overview?.totalMinutes ?: stored.recipe?.let { it.workingMinutes + it.waitingMinutes }
            val rating = stored.overview?.rating ?: stored.recipe?.rating
            AssistantResult.Done(AssistantRecipe(recipeId, name, minutes?.takeIf { it > 0 }, rating))
        }
    }

    /** The checks every function makes first (see [AssistantActions]), then [block]. */
    private suspend fun <T> guarded(write: Boolean, block: suspend () -> AssistantResult<T>): AssistantResult<T> {
        val choice = settings.settings.first()
        if (!choice.enabled) return refuse(AssistantRefusal.TURNED_OFF)
        if (!choice.allowWhenLocked && !lock.contentAllowed()) return refuse(AssistantRefusal.LOCKED)
        val session = sessions.session.first()
        val loggedIn = session as? Session.LoggedIn
        if (loggedIn == null && session != Session.Demo) return refuse(AssistantRefusal.NOT_LOGGED_IN)
        if (write) {
            if (permissions.permissions.first().readOnly) return refuse(AssistantRefusal.READ_ONLY)
            if (loggedIn?.compatibility?.isSafeMode == true) return refuse(AssistantRefusal.SAFE_MODE)
        }
        return block()
    }

    /** [block] with the list named [name] (any case), null for none; refuses when no list has that name. */
    private suspend fun <T> withList(name: String?, block: suspend (ShoppingList?) -> AssistantResult<T>): AssistantResult<T> {
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: return block(null)
        val lists = shopping.observeLists().first()
        val match = lists.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
            ?: return AssistantResult.Refused(AssistantRefusal.LIST_NOT_FOUND, lists.joinToString(", ") { it.name }.ifEmpty { null })
        return block(match)
    }

    private fun failure(error: TandemError): AssistantResult<Nothing> = when (error.dataProblem()) {
        DataProblem.READ_ONLY -> refuse(AssistantRefusal.READ_ONLY)
        else -> refuse(AssistantRefusal.NOT_SAVED)
    }

    private fun refuse(refusal: AssistantRefusal): AssistantResult<Nothing> = AssistantResult.Refused(refusal)

    private fun ShoppingEntryDraft.toItem(list: ShoppingList?): AssistantShoppingItem = AssistantShoppingItem(
        food = foodName,
        amount = amount.takeIf { it > 0.0 },
        unit = unitName,
        note = note,
        lists = listOfNotNull(list?.name),
        waitingToSync = true,
    )

    private fun ShoppingEntry.toItem(lists: Map<Long, String>, now: Instant): AssistantShoppingItem = AssistantShoppingItem(
        food = food.name,
        amount = amount.takeIf { it > 0.0 },
        unit = unit?.name,
        lists = listIds.mapNotNull { lists[it] }.sorted(),
        later = isDelayed(now),
        waitingToSync = pending || isLocalOnly,
    )
}
