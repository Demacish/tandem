package casa.moller.tandem.core.model.makenow

import casa.moller.tandem.core.model.recipe.RecipeOverview
import kotlinx.datetime.LocalDate

// Fas S (grund lane): "Kan laga nu" (#23) and "Använd upp" (#28), plan §8.8. The server answers
// `recipe?makenow=N` (an INTEGER: at most N missing foods, FACTS "Fas S a"); the app names the
// missing foods itself (core:domain MissingIngredients) when a row is shown.

/** The three groups of "Kan laga nu": the number of missing foods. */
enum class MakeNowGroup(val missing: Int) {
    HAS_ALL(0),
    MISSING_1(1),
    MISSING_2(2),
    ;

    companion object {
        /** The group for [missing] foods, null above 2 (not shown). */
        fun of(missing: Int): MakeNowGroup? = entries.firstOrNull { it.missing == missing }
    }
}

/** A food a recipe needs and the household does not have. */
data class MissingFood(val id: Long, val name: String, val pluralName: String? = null)

/** A pantry entry that expires soon and that a recipe uses ("Grädden går ut i morgon"). */
data class ExpiringFood(val foodId: Long, val name: String, val expires: LocalDate)

/**
 * One recipe in "Kan laga nu".
 *
 * @property missingFoods the missing foods, null until they were computed (only when the row is
 *   shown, at most about 20 at a time through the RateLimiter, plan §8.8).
 * @property expiring the soon-expiring foods it uses; non-empty lifts it to the top (#28).
 */
data class MakeNowRecipe(
    val recipe: RecipeOverview,
    val group: MakeNowGroup,
    val missingFoods: List<MissingFood>? = null,
    val expiring: List<ExpiringFood> = emptyList(),
)

/** Filters of "Kan laga nu" (plan §8.8: måltidstyp, nyckelord och tid). */
data class MakeNowFilter(
    val keywordIds: Set<Long> = emptySet(),
    val maxMinutes: Int? = null,
    val mealTypeId: Long? = null,
)

/**
 * The groups as last fetched. [refreshing] while a new answer is on its way; [stale] when the
 * last refresh failed and the stored answer is shown (offline).
 */
data class MakeNowState(
    val groups: Map<MakeNowGroup, List<MakeNowRecipe>> = emptyMap(),
    val refreshing: Boolean = false,
    val stale: Boolean = false,
) {
    fun recipes(group: MakeNowGroup): List<MakeNowRecipe> = groups[group].orEmpty()
}
