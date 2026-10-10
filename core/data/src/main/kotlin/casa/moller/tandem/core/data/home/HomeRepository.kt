package casa.moller.tandem.core.data.home

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import kotlinx.coroutines.flow.Flow

/**
 * Content of the Today tab (plan 6.2 "Idag", 3.3 "Hemsektioner"). Offline first like
 * [casa.moller.tandem.core.data.recipe.RecipeRepository]: both flows read Room and keep showing
 * the last stored result offline.
 *
 * - **Threading:** main-safe; flows may be collected on the main thread.
 * - **Errors:** flows never throw for server or network problems; [refresh] returns them.
 */
interface HomeRepository {

    /**
     * The carousels in display order (pinned, often cooked, new, recently viewed, not cooked
     * lately). Sections without recipes are left out; an empty list means there is nothing to
     * show yet (first sync not done, or an empty server).
     */
    fun observeSections(): Flow<List<HomeSection>>

    /**
     * Tonight and tomorrow from the stored meal plan window. "Today" is the current day in the
     * server's time zone (plan 3.4) and changes at midnight while the flow is collected.
     */
    fun observeUpcomingMeals(): Flow<UpcomingMeals>

    /**
     * Refreshes the section queries and the meal plan window from the server. The flows above
     * emit the result. Fails with the network or server error when offline or refused.
     */
    suspend fun refresh(): Outcome<Unit>
}
