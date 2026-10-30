package casa.moller.tandem.core.ui.drink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Fas S (drycker lane, plan §8.8 "Kvällens måltid i Idag och Veckan får ett dryckesförslag"): the
 * drink suggestion under a planned recipe, as a slot. feature:home and feature:mealplan never see
 * feature:recipes (checkModuleGraph), so the shell provides feature:recipes' suggestion
 * (`DrinkSuggestionRoute`) through [LocalDrinkSuggestion]; the cards call it for their recipe.
 * Null (the default, every test) shows nothing, and so does a recipe without pairings.
 */
fun interface DrinkSuggestionSlot {
    /** [compact]: one line without the arrow (the tablet's narrow week grid tile, fix-ui). */
    @Composable
    fun Content(recipeId: Long, modifier: Modifier, compact: Boolean)
}

/** The drink suggestion slot of the app, null outside the shell. */
val LocalDrinkSuggestion = staticCompositionLocalOf<DrinkSuggestionSlot?> { null }

/** The drink suggestion for [recipeId] when the shell provides one; nothing otherwise. */
@Composable
fun DrinkSuggestion(recipeId: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    LocalDrinkSuggestion.current?.Content(recipeId, modifier, compact)
}
