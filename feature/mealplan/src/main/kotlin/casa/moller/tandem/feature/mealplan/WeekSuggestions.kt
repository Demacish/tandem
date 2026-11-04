package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

// Fas 4 "Förslag på veckans matsedel" (Stitch f_resl_veckan_notiser, the upper half): a local rule
// over the recipes the app already has. Nothing is sent anywhere; "Lägg i veckan" plans the meals.

/** One suggested meal: [recipe] as [mealType] on [date]. */
data class SuggestedMeal(val date: LocalDate, val mealType: MealType, val recipe: RecipeOverview)

/**
 * The suggestion sheet, while open.
 *
 * @property meals the suggestions left (a removed day is not filled again).
 * @property mealType the meal type the suggestions fill ([WeekSuggestions.mainMealType]).
 * @property noFreeDays the shown week has no day from today on without that meal.
 * @property noRecipes nothing to suggest: no recipes, or every one is already in the week.
 * @property saving "Lägg i veckan" is on its way.
 */
data class WeekSuggestionsState(
    val meals: List<SuggestedMeal>,
    val mealType: MealType?,
    val noFreeDays: Boolean = false,
    val noRecipes: Boolean = false,
    val saving: Boolean = false,
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
     * not cooked within [RECENTLY_COOKED] before the ones that were; then by how often they were
     * cooked and how they are rated, new recipes slightly ahead so they get a chance.
     */
    fun ranked(days: List<MealPlanDay>, candidates: List<RecipeOverview>, now: Instant): List<RecipeOverview> {
        val planned = days.flatMap { it.meals }.mapNotNull { it.recipe?.id }.toSet()
        return candidates
            .filter { it.id !in planned }
            .distinctBy { it.id }
            .sortedWith(compareBy<RecipeOverview> { it.isRecentlyCooked(now) }.thenByDescending { score(it) })
    }

    private fun RecipeOverview.isRecentlyCooked(now: Instant): Boolean = lastCooked?.let { now - it < RECENTLY_COOKED } == true

    private fun score(recipe: RecipeOverview): Double =
        (recipe.timesCooked ?: 0) + (recipe.rating ?: 0.0) * RATING_WEIGHT + (if (recipe.isNew) NEW_BONUS else 0.0)

    /**
     * A suggestion per free day of the week, from [ranked]. [seed] 0 takes the best in order;
     * another seed draws the same days from a pool of the best ([POOL_PER_DAY] per day) in a
     * random order ("Slumpa om"), so the suggestions stay good but change.
     */
    fun suggest(
        days: List<MealPlanDay>,
        mealTypes: List<MealType>,
        candidates: List<RecipeOverview>,
        today: LocalDate,
        now: Instant,
        seed: Int = 0,
    ): WeekSuggestionsState {
        val mealType = mainMealType(days, mealTypes) ?: return WeekSuggestionsState(emptyList(), null, noRecipes = true)
        val free = freeDays(days, mealType, today)
        if (free.isEmpty()) return WeekSuggestionsState(emptyList(), mealType, noFreeDays = true)
        val ranked = ranked(days, candidates, now)
        if (ranked.isEmpty()) return WeekSuggestionsState(emptyList(), mealType, noRecipes = true)
        val pool = ranked.take(free.size * POOL_PER_DAY)
        val picks = if (seed == 0) pool else pool.shuffled(Random(seed))
        val meals = free.zip(picks).map { (date, recipe) -> SuggestedMeal(date, mealType, recipe) }
        return WeekSuggestionsState(meals, mealType)
    }

    /** [state] with the suggestion on [date] replaced by the next ranked recipe not suggested yet. */
    fun swap(
        state: WeekSuggestionsState,
        date: LocalDate,
        days: List<MealPlanDay>,
        candidates: List<RecipeOverview>,
        now: Instant,
    ): WeekSuggestionsState {
        if (state.meals.none { it.date == date }) return state
        val shown = state.meals.map { it.recipe.id }.toSet()
        val next = ranked(days, candidates, now).firstOrNull { it.id !in shown } ?: return state
        return state.copy(meals = state.meals.map { if (it.date == date) it.copy(recipe = next) else it })
    }

    private const val RATING_WEIGHT = 2.0
    private const val NEW_BONUS = 1.5
}
