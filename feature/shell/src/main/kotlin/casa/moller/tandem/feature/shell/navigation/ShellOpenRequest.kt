package casa.moller.tandem.feature.shell.navigation

import casa.moller.tandem.core.ui.navigation.AppOpenIntents

/**
 * Fas 3 (widgets lane): where something outside the shell wants the app opened (a launcher
 * shortcut, a widget, the quick settings tile; core:ui `AppOpenIntents`). The app turns the
 * intent's destination name into this ([of]) and `TandemShell` opens it once
 * ([ShellNavigationState.open]).
 */
sealed interface ShellOpenRequest {
    data class Tab(val tab: ShellTab) : ShellOpenRequest

    data class Recipe(val recipeId: Long) : ShellOpenRequest {
        init {
            require(recipeId != 0L) { "a recipe id" }
        }
    }

    data object Import : ShellOpenRequest

    data object BarcodeScan : ShellOpenRequest

    companion object {
        private const val TODAY = "today"
        private const val MORE = "more"

        /** The request [destination] (an `AppOpenIntents` name) stands for, or null for an unknown one (the app just opens). */
        fun of(destination: String?): ShellOpenRequest? = when {
            destination == null -> null
            destination == AppOpenIntents.SHOPPING -> Tab(ShellTab.SHOPPING)
            destination == AppOpenIntents.WEEK -> Tab(ShellTab.WEEK)
            destination == AppOpenIntents.RECIPES -> Tab(ShellTab.RECIPES)
            destination == TODAY -> Tab(ShellTab.TODAY)
            destination == MORE -> Tab(ShellTab.MORE)
            destination == AppOpenIntents.IMPORT -> Import
            destination == AppOpenIntents.SCAN -> BarcodeScan
            destination.startsWith(AppOpenIntents.RECIPE_PREFIX) ->
                destination.removePrefix(AppOpenIntents.RECIPE_PREFIX).toLongOrNull()?.takeIf { it != 0L }?.let { Recipe(it) }
            else -> null
        }

        /** The destination name of [request] (what a shortcut or widget would send). */
        fun nameOf(request: ShellOpenRequest): String = when (request) {
            is Tab -> when (request.tab) {
                ShellTab.SHOPPING -> AppOpenIntents.SHOPPING
                ShellTab.WEEK -> AppOpenIntents.WEEK
                ShellTab.RECIPES -> AppOpenIntents.RECIPES
                ShellTab.TODAY -> TODAY
                ShellTab.MORE -> MORE
            }

            is Recipe -> AppOpenIntents.recipe(request.recipeId)
            Import -> AppOpenIntents.IMPORT
            BarcodeScan -> AppOpenIntents.SCAN
        }
    }
}
