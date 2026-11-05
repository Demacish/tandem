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

    /** Fas 4 (skafferinotis lane): the expiry notification opens "Kan laga nu" with "Använd upp" first. */
    data object UseUp : ShellOpenRequest

    companion object {
        private const val TODAY = "today"
        private const val MORE = "more"

        /** The request [destination] (an `AppOpenIntents` name) stands for, or null for an unknown one (the app just opens). */
        fun of(destination: String?): ShellOpenRequest? = when (destination) {
            null -> null
            AppOpenIntents.SHOPPING -> Tab(ShellTab.SHOPPING)
            AppOpenIntents.WEEK -> Tab(ShellTab.WEEK)
            AppOpenIntents.RECIPES -> Tab(ShellTab.RECIPES)
            TODAY -> Tab(ShellTab.TODAY)
            MORE -> Tab(ShellTab.MORE)
            AppOpenIntents.IMPORT -> Import
            AppOpenIntents.SCAN -> BarcodeScan
            AppOpenIntents.USE_UP -> UseUp
            else -> recipeOf(destination)
        }

        private fun recipeOf(destination: String): Recipe? = destination
            .takeIf { it.startsWith(AppOpenIntents.RECIPE_PREFIX) }
            ?.removePrefix(AppOpenIntents.RECIPE_PREFIX)
            ?.toLongOrNull()
            ?.takeIf { it != 0L }
            ?.let { Recipe(it) }

        /** The destination name of [request] (what a shortcut or widget would send). */
        fun nameOf(request: ShellOpenRequest): String = when (request) {
            is Tab -> tabName(request.tab)
            is Recipe -> AppOpenIntents.recipe(request.recipeId)
            Import -> AppOpenIntents.IMPORT
            BarcodeScan -> AppOpenIntents.SCAN
            UseUp -> AppOpenIntents.USE_UP
        }

        private fun tabName(tab: ShellTab): String = when (tab) {
            ShellTab.SHOPPING -> AppOpenIntents.SHOPPING
            ShellTab.WEEK -> AppOpenIntents.WEEK
            ShellTab.RECIPES -> AppOpenIntents.RECIPES
            ShellTab.TODAY -> TODAY
            ShellTab.MORE -> MORE
        }
    }
}
