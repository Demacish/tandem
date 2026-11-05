package casa.moller.tandem.feature.cook.navigation

import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState

/**
 * Fas 4 (two recipes at once, plan 8.1, Kitshn #433): the cook mode that "Laga" on a recipe opens.
 *
 * When another recipe of the active source is being cooked – it has a timer that is running,
 * paused or ringing – cook mode opens with BOTH: that recipe as the first tab (at its newest
 * timer's step) and [recipeId] as the second, selected tab, so the timers of the first meal stay in
 * view while the second one starts ("Laga ett recept till" from a recipe). Otherwise it is cook
 * mode for [recipeId] alone. Timers of another source (demo mode, another account) or without a
 * recorded source never pull their recipe in.
 *
 * @param activeSource the app's source now (core:model `TimerSource.of`); null: none.
 */
fun cookKeyAlongside(recipeId: Long, servings: Double?, timers: List<CookTimer>, activeSource: String?): CookKey {
    val cooking = timers
        .filter { timer ->
            val other = timer.recipeId
            timer.state != TimerState.FINISHED &&
                other != null &&
                other > 0 &&
                other != recipeId &&
                activeSource != null &&
                timer.sourceKey == activeSource
        }
        .maxWithOrNull(compareBy<CookTimer>({ it.createdAt }, { it.id }))
    return CookKey(
        recipeId = recipeId,
        servings = servings,
        alongsideRecipeId = cooking?.recipeId,
        alongsideStep = cooking?.stepIndex ?: 0,
    )
}
