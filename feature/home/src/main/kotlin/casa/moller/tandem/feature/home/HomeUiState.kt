package casa.moller.tandem.feature.home

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The part of the day, for the greeting above Today ("Good evening"). */
enum class DayPart {
    MORNING,
    DAY,
    EVENING,
    NIGHT,
    ;

    companion object {
        /** 05-10 morning, 10-17 day, 17-22 evening, otherwise night. */
        fun of(hour: Int): DayPart = when (hour) {
            in MORNING_START until DAY_START -> MORNING
            in DAY_START until EVENING_START -> DAY
            in EVENING_START until NIGHT_START -> EVENING
            else -> NIGHT
        }

        private const val MORNING_START = 5
        private const val DAY_START = 10
        private const val EVENING_START = 17
        private const val NIGHT_START = 22
    }
}

/**
 * What the Today tab shows (plan 6.2 "Idag", Stitch idag_*).
 *
 * @property loading nothing has been read from the device yet (first frame only).
 * @property today the current day in the server's time zone, null while loading.
 * @property tonight the evening meal planned today ("Ikväll"), null when none.
 * @property alsoToday the other meals planned today (e.g. lunch).
 * @property tomorrow the meals planned tomorrow ("Imorgon").
 * @property sections the recipe carousels that have recipes, in the repository's order.
 * @property refreshing a pull to refresh is running.
 * @property offline the last refresh could not reach the server (or the sync is paused offline):
 *   what is shown is what was fetched last.
 * @property lastSynced when the last sync finished successfully, null when never.
 * @property syncRunning a sync is running now.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val dayPart: DayPart = DayPart.DAY,
    val today: LocalDate? = null,
    val tonight: MealPlanEntry? = null,
    val alsoToday: List<MealPlanEntry> = emptyList(),
    val tomorrow: List<MealPlanEntry> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val lastSynced: Instant? = null,
    val syncRunning: Boolean = false,
) {
    /** Nothing planned today: the "Nothing planned today. Plan the week →" card. */
    val nothingPlannedToday: Boolean
        get() = tonight == null && alsoToday.isEmpty()
}

/** What the Today screen can ask for. */
sealed interface HomeAction {
    /** Pull to refresh, or "Sync now". */
    data object Refresh : HomeAction

    data class OpenRecipe(val recipeId: Long) : HomeAction

    /** "All recipes": switch to the Recipes tab. */
    data object OpenRecipes : HomeAction

    /** "Plan the week": switch to the Week tab. */
    data object OpenWeek : HomeAction
}

/** One-off effects of [HomeViewModel] (plan 5.3). */
sealed interface HomeEffect {
    /** A refresh the user asked for failed; the screen keeps showing what it has. */
    data class RefreshFailed(val error: TandemError) : HomeEffect
}

/** From this time on a meal counts as "tonight" (Tandoor meal types carry an optional time). */
internal val EveningStart: LocalTime = LocalTime(15, 0)

/**
 * Splits today's meals into "tonight" and the rest: the first meal whose type starts in the
 * evening (from 15:00); otherwise the last meal of the day whose type has no time (a household's
 * dinner usually sorts after breakfast and lunch). Meals with a daytime type (lunch at 12:00) are
 * never "tonight".
 */
internal fun UpcomingMeals.splitToday(): Pair<MealPlanEntry?, List<MealPlanEntry>> {
    val meals = todayMeals.sortedBy { it.mealType.order }
    val evening = meals
        .mapNotNull { entry -> entry.mealType.time?.takeIf { it >= EveningStart }?.let { time -> entry to time } }
        .minByOrNull { it.second }
        ?.first
    val tonight = evening ?: meals.lastOrNull { it.mealType.time == null }
    return tonight to meals.filter { it != tonight }
}
