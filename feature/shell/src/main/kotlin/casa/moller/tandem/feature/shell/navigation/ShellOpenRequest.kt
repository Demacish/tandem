package casa.moller.tandem.feature.shell.navigation

import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import kotlinx.datetime.LocalDate

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

    /** Cook mode for a recipe (the "I dag" widget, plan 8.7), through the shell's "Laga" so running timers come along. */
    data class Cook(val recipeId: Long) : ShellOpenRequest {
        init {
            require(recipeId != 0L) { "a recipe id" }
        }
    }

    /** Handla with the add panel open (the shortcut "Ny vara", plan 8.7). */
    data object AddItem : ShellOpenRequest

    data object Import : ShellOpenRequest

    /** Fas 4, komplettering 3: the notification of a finished AI import. */
    data object ImportResult : ShellOpenRequest

    data object BarcodeScan : ShellOpenRequest

    /** Fas 4 (skafferinotis lane): the expiry notification opens "Kan laga nu" with "Använd upp" first. */
    data object UseUp : ShellOpenRequest

    /** Fas 5 (planera lane): the evening reminder opens Veckan at the meal's day ([date] `yyyy-MM-dd`). */
    data class WeekDay(val date: String) : ShellOpenRequest {
        init {
            require(runCatching { LocalDate.parse(date) }.isSuccess) { "date is yyyy-MM-dd" }
        }
    }

    /** Fas 5 (butik lane): the reminder near a store opens store mode sorted for that store. */
    data class StoreMode(val storeId: Long) : ShellOpenRequest {
        init {
            require(storeId != 0L) { "a store id" }
        }
    }

    companion object {
        private const val TODAY = "today"
        private const val MORE = "more"

        /** The request [destination] (an `AppOpenIntents` name) stands for, or null for an unknown one (the app just opens). */
        fun of(destination: String?): ShellOpenRequest? = when (destination) {
            null -> null
            AppOpenIntents.SHOPPING -> Tab(ShellTab.SHOPPING)
            AppOpenIntents.ADD_ITEM -> AddItem
            AppOpenIntents.WEEK -> Tab(ShellTab.WEEK)
            AppOpenIntents.RECIPES -> Tab(ShellTab.RECIPES)
            TODAY -> Tab(ShellTab.TODAY)
            MORE -> Tab(ShellTab.MORE)
            AppOpenIntents.IMPORT -> Import
            AppOpenIntents.IMPORT_RESULT -> ImportResult
            AppOpenIntents.SCAN -> BarcodeScan
            AppOpenIntents.USE_UP -> UseUp
            else -> withId(destination)
        }

        private fun withId(destination: String): ShellOpenRequest? {
            idOf(destination, AppOpenIntents.RECIPE_PREFIX)?.let { return Recipe(it) }
            if (destination.startsWith(AppOpenIntents.WEEK_DAY_PREFIX)) {
                val date = destination.removePrefix(AppOpenIntents.WEEK_DAY_PREFIX)
                return runCatching { LocalDate.parse(date) }.getOrNull()?.let { WeekDay(it.toString()) }
            }
            idOf(destination, AppOpenIntents.STORE_MODE_PREFIX)?.let { return StoreMode(it) }
            return idOf(destination, AppOpenIntents.COOK_PREFIX)?.let { Cook(it) }
        }

        private fun idOf(destination: String, prefix: String): Long? = destination
            .takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.toLongOrNull()
            ?.takeIf { it != 0L }

        /** The destination name of [request] (what a shortcut or widget would send). */
        fun nameOf(request: ShellOpenRequest): String = when (request) {
            is Tab -> tabName(request.tab)
            is Recipe -> AppOpenIntents.recipe(request.recipeId)
            is Cook -> AppOpenIntents.cook(request.recipeId)
            AddItem -> AppOpenIntents.ADD_ITEM
            Import -> AppOpenIntents.IMPORT
            ImportResult -> AppOpenIntents.IMPORT_RESULT
            BarcodeScan -> AppOpenIntents.SCAN
            UseUp -> AppOpenIntents.USE_UP
            is WeekDay -> AppOpenIntents.weekDay(request.date)
            is StoreMode -> AppOpenIntents.storeMode(request.storeId)
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
