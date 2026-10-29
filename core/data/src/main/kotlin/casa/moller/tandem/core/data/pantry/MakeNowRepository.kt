package casa.moller.tandem.core.data.pantry

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.makenow.MissingFood
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * "Kan laga nu" (#23) and "Använd upp" (#28), plan §8.8. The groups come from the server:
 * `recipe?makenow=0`, `=1` and `=2` (an INTEGER, at most N missing foods; FACTS "Fas S a": on
 * hand and inventory amount > 0 in the household count, amount 0 does not, substitutes and
 * `ignore_shopping` foods are resolved, a recipe without ingredients is always in 0). A recipe is
 * put in the smallest group that lists it. Recipes with an entry expiring within 3 days of
 * [today] come first (core:domain `UseUpRanking`). Offline: the stored answer with `stale`.
 * Implemented by the hemma-data lane; read-only, so guests can use it.
 */
interface MakeNowRepository {
    fun observe(filter: MakeNowFilter, today: LocalDate): Flow<MakeNowState>

    /** The three makenow requests again (at most once a minute unless [force]). */
    suspend fun refresh(force: Boolean = false): Outcome<Unit>

    /**
     * The missing foods of one recipe, computed locally (its ingredients against on hand, the
     * pantry and substitutes, core:domain `MissingIngredients`); fetches the detail when needed.
     */
    suspend fun missingFoods(recipeId: Long): Outcome<List<MissingFood>>

    /** "Överraska mig": a random recipe of [group] with [filter] (`random=true` on the server), null when the group is empty. */
    suspend fun surprise(group: MakeNowGroup, filter: MakeNowFilter): Outcome<MakeNowRecipe?>
}
