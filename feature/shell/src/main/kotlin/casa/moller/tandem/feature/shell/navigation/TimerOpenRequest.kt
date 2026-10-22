package casa.moller.tandem.feature.shell.navigation

/**
 * A request from outside the shell to show a timer (Phase 1b integration): the app turns the
 * timer notification's or the lock-screen alarm's intent (core:timer `TimerIntents.ACTION_OPEN_TIMER`)
 * into this and hands it to `TandemShell`, which opens [ShellNavigationState.openTimer].
 *
 * @property recipeId the timer's recipe, or null for a timer without one (the timer list opens).
 * @property stepIndex the 0-based step the timer belongs to, or null.
 */
data class TimerOpenRequest(val recipeId: Long?, val stepIndex: Int?) {
    init {
        require(recipeId == null || recipeId > 0) { "recipeId must be positive" }
        require(stepIndex == null || stepIndex >= 0) { "stepIndex must not be negative" }
    }
}
