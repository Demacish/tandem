package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.makenow.ExpiringFood
import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

// Fas 4 "Förslag på veckans matsedel" (Stitch f_resl_veckan_notiser, the upper half): a local rule
// over the recipes the app already has. Nothing is sent anywhere; "Lägg i veckan" plans the meals
// as ordinary meal plans (so the web shows them). Plan §8.3 "Autoplan" (#31): the user picks the
// meal type and keywords; every free day of the shown week gets a recipe, never the same twice.
// Tandoor's own `auto-plan` is NOT used (contract-tests Phase4AutoPlanContractTest, docs/BYGGE.md
// "Smarta förslag": it repeats recipes, plans over existing meals and answers no ids, and it
// cannot work offline or in demo mode).

/**
 * One suggested meal: [recipe] as [mealType] on [date].
 *
 * @property expiring the pantry's soon-expiring foods the recipe uses ("Använder 3 som går ut
 *   snart"), from "Använd upp" (core:domain `UseUpRanking`); empty when none or unknown.
 */
data class SuggestedMeal(
    val date: LocalDate,
    val mealType: MealType,
    val recipe: RecipeOverview,
    val expiring: List<ExpiringFood> = emptyList(),
)

/**
 * The suggestion sheet, while open.
 *
 * @property meals the suggestions left (a removed day is not filled again).
 * @property mealType the meal type the suggestions fill (the chosen one, else
 *   [WeekSuggestions.mainMealType]).
 * @property noFreeDays the shown week has no day from today on without that meal.
 * @property noRecipes nothing to suggest: no recipes, or every one is already in the week.
 * @property saving "Lägg i veckan" is on its way.
 * @property mealTypes the meal types to choose from.
 * @property keywords the quick choices (the most used of the recipes, chosen ones included).
 * @property allKeywords every keyword that can be chosen ("Fler nyckelord"), by name.
 * @property keywordIds the chosen keywords: a suggestion has every one of them.
 * @property noKeywordMatch no recipe outside the week has every chosen keyword.
 * @property servings the servings the meals are planned with (plan §8.3 "nyckelord, portioner").
 * @property uncoveredDays free days without a suggestion, because too few recipes are left.
 * @property canSwap "Byt ut" has another recipe to offer.
 */
data class WeekSuggestionsState(
    val meals: List<SuggestedMeal>,
    val mealType: MealType?,
    val noFreeDays: Boolean = false,
    val noRecipes: Boolean = false,
    val saving: Boolean = false,
    val mealTypes: List<MealType> = emptyList(),
    val keywords: List<Keyword> = emptyList(),
    val keywordIds: Set<Long> = emptySet(),
    val noKeywordMatch: Boolean = false,
    val servings: Double = PlanMealUiState.DEFAULT_SERVINGS,
    val uncoveredDays: Int = 0,
    val canSwap: Boolean = false,
    val allKeywords: List<Keyword> = emptyList(),
)

internal object WeekSuggestions {

    /** A recipe cooked this recently is not suggested while there are others. */
    val RECENTLY_COOKED = 14.days

    /** How many of the best candidates "Slumpa om" and "Byt ut" draw from. */
    private const val POOL_PER_DAY = 2

    /**
     * The meal type the suggestions fill: the type with the latest time of day (dinner); without
     * times, the type most of the week's recipe meals have, else the last by order. Null without
     * meal types.
     */
    fun mainMealType(days: List<MealPlanDay>, mealTypes: List<MealType>): MealType? {
        if (mealTypes.isEmpty()) return null
        mealTypes.filter { it.time != null }.maxByOrNull { it.time!! }?.let { return it }
        val used = days.flatMap { it.meals }.filter { it.recipe != null }.groupingBy { it.mealType.id }.eachCount()
        val mostUsed = used.maxByOrNull { it.value }?.key?.let { id -> mealTypes.firstOrNull { it.id == id } }
        return mostUsed ?: mealTypes.maxBy { it.order }
    }

    /** The days of [days] from [today] on that have no [mealType] meal yet. */
    fun freeDays(days: List<MealPlanDay>, mealType: MealType, today: LocalDate): List<LocalDate> =
        days.filter { day -> day.date >= today && day.meals.none { it.mealType.id == mealType.id } }.map { it.date }

    /**
     * The candidates in the order they are worth suggesting: not in the week already, the ones
     * not cooked within [RECENTLY_COOKED] before the ones that were; then those that use the most
     * soon-expiring pantry foods ([expiring], by recipe id), the soonest first; then by how often
     * they were cooked and how they are rated, new recipes slightly ahead so they get a chance.
     */
    fun ranked(
        days: List<MealPlanDay>,
        candidates: List<RecipeOverview>,
        now: Instant,
        expiring: Map<Long, List<ExpiringFood>> = emptyMap(),
    ): List<RecipeOverview> {
        val planned = days.flatMap { it.meals }.mapNotNull { it.recipe?.id }.toSet()
        return candidates
            .filter { it.id !in planned }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<RecipeOverview> { it.isRecentlyCooked(now) }
                    .thenByDescending { expiring[it.id].orEmpty().size }
                    .thenBy(nullsLast()) { recipe -> expiring[recipe.id]?.minOfOrNull { it.expires } }
                    .thenByDescending { score(it) },
            )
    }

    /** The recipes of [candidates] that have every keyword of [keywordIds] (all of them when empty). */
    fun matching(candidates: List<RecipeOverview>, keywordIds: Set<Long>): List<RecipeOverview> =
        if (keywordIds.isEmpty()) candidates else candidates.filter { recipe -> recipe.keywords.map { it.id }.containsAll(keywordIds) }

    /**
     * The keywords to offer, by use (then by name): the drink and "Passar med" keywords a dish can
     * carry say nothing about what to cook, so [excluded] (pairing keyword ids) and the roots stay out.
     */
    private fun offered(candidates: List<RecipeOverview>, excluded: Set<Long>): List<List<Keyword>> =
        candidates.flatMap { it.keywords }
            .filterNot { it.id in excluded || DrinkKeywords.isDrinkRoot(it.name) || DrinkKeywords.isPairsWithRoot(it.name) }
            .groupBy { it.id }
            .values
            .sortedWith(compareByDescending<List<Keyword>> { it.size }.thenBy { it.first().name.lowercase() })

    /** The quick choices: the [MAX_KEYWORDS] most used, plus the [chosen] ones. */
    fun keywordChoices(candidates: List<RecipeOverview>, chosen: Set<Long>, excluded: Set<Long> = emptySet()): List<Keyword> {
        val all = offered(candidates, excluded).map { it.first() }
        val top = all.take(MAX_KEYWORDS)
        return top + all.drop(MAX_KEYWORDS).filter { it.id in chosen }
    }

    /** Every keyword that can be chosen, by name ("Fler nyckelord": the top [MAX_KEYWORDS] are only quick choices). */
    fun allKeywords(candidates: List<RecipeOverview>, excluded: Set<Long> = emptySet()): List<Keyword> =
        offered(candidates, excluded).map { it.first() }.sortedBy { it.name.lowercase() }

    private fun RecipeOverview.isRecentlyCooked(now: Instant): Boolean = lastCooked?.let { now - it < RECENTLY_COOKED } == true

    private fun score(recipe: RecipeOverview): Double =
        (recipe.timesCooked ?: 0) + (recipe.rating ?: 0.0) * RATING_WEIGHT + (if (recipe.isNew) NEW_BONUS else 0.0)

    /**
     * A suggestion per free day of the week, from [ranked]. [seed] 0 takes the best in order;
     * another seed draws the same days from a pool of the best ([POOL_PER_DAY] per day) in a
     * random order ("Slumpa om"), so the suggestions stay good but change. [mealTypeId] is the
     * chosen meal type (else [mainMealType]); [keywordIds] the chosen keywords ([matching]). A
     * recipe is never suggested twice: fewer recipes than free days leave days empty
     * ([WeekSuggestionsState.uncoveredDays]). [candidates] hold no drinks (the caller's rule, a
     * drink is never a dinner); [excludedKeywords] are the pairing keywords dishes carry too.
     */
    fun suggest(
        days: List<MealPlanDay>,
        mealTypes: List<MealType>,
        candidates: List<RecipeOverview>,
        today: LocalDate,
        now: Instant,
        seed: Int = 0,
        mealTypeId: Long? = null,
        keywordIds: Set<Long> = emptySet(),
        expiring: Map<Long, List<ExpiringFood>> = emptyMap(),
        excludedKeywords: Set<Long> = emptySet(),
        servings: Double = PlanMealUiState.DEFAULT_SERVINGS,
    ): WeekSuggestionsState {
        val choices = WeekSuggestionsState(
            meals = emptyList(),
            mealType = null,
            mealTypes = mealTypes,
            keywords = keywordChoices(candidates, keywordIds, excludedKeywords),
            allKeywords = allKeywords(candidates, excludedKeywords),
            keywordIds = keywordIds,
            servings = servings,
        )
        val mealType = mealTypes.firstOrNull { it.id == mealTypeId } ?: mainMealType(days, mealTypes)
            ?: return choices.copy(noRecipes = true)
        val free = freeDays(days, mealType, today)
        if (free.isEmpty()) return choices.copy(mealType = mealType, noFreeDays = true)
        val ranked = ranked(days, matching(candidates, keywordIds), now, expiring)
        if (ranked.isEmpty()) {
            // The keywords leave nothing (other recipes exist), or there is nothing at all.
            val noMatch = keywordIds.isNotEmpty() && ranked(days, candidates, now).isNotEmpty()
            return choices.copy(mealType = mealType, noRecipes = !noMatch, noKeywordMatch = noMatch)
        }
        val pool = ranked.take(free.size * POOL_PER_DAY)
        val picks = if (seed == 0) pool else pool.shuffled(Random(seed))
        val meals = free.zip(picks).map { (date, recipe) -> SuggestedMeal(date, mealType, recipe, expiring[recipe.id].orEmpty()) }
        return choices.copy(
            meals = meals,
            mealType = mealType,
            uncoveredDays = free.size - meals.size,
            canSwap = hasAnother(meals, ranked),
        )
    }

    /** True when a recipe of [ranked] is not among [meals]: "Byt ut" has something to offer. */
    private fun hasAnother(meals: List<SuggestedMeal>, ranked: List<RecipeOverview>): Boolean {
        val shown = meals.map { it.recipe.id }.toSet()
        return ranked.any { it.id !in shown }
    }

    /** [state] with the suggestion on [date] replaced by the next ranked recipe not suggested yet. */
    fun swap(
        state: WeekSuggestionsState,
        date: LocalDate,
        days: List<MealPlanDay>,
        candidates: List<RecipeOverview>,
        now: Instant,
        expiring: Map<Long, List<ExpiringFood>> = emptyMap(),
    ): WeekSuggestionsState {
        if (state.meals.none { it.date == date }) return state
        val shown = state.meals.map { it.recipe.id }.toSet()
        val ranked = ranked(days, matching(candidates, state.keywordIds), now, expiring)
        val next = ranked.firstOrNull { it.id !in shown } ?: return state
        val swapped = state.meals.map { if (it.date == date) it.copy(recipe = next, expiring = expiring[next.id].orEmpty()) else it }
        return state.copy(meals = swapped, canSwap = hasAnother(swapped, ranked))
    }

    /** [state] without the suggestion on [date]; its recipe can be swapped in again. */
    fun remove(
        state: WeekSuggestionsState,
        date: LocalDate,
        days: List<MealPlanDay>,
        candidates: List<RecipeOverview>,
        now: Instant,
    ): WeekSuggestionsState {
        val left = state.meals.filterNot { it.date == date }
        return state.copy(meals = left, canSwap = hasAnother(left, ranked(days, matching(candidates, state.keywordIds), now)))
    }

    /** Keywords offered in the sheet (the most used ones). */
    const val MAX_KEYWORDS: Int = 12

    private const val RATING_WEIGHT = 2.0
    private const val NEW_BONUS = 1.5
}
