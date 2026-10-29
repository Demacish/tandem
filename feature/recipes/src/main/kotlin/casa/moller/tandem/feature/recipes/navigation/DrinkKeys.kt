package casa.moller.tandem.feature.recipes.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

// Fas S (grund lane): drinks and "Passar med" (#43, B20, plan §8.8 "Drycker"). A drink is a recipe
// with the keyword `Dryck`, so drinks live in feature:recipes next to the recipe detail. Recipe ids
// may be temporary (negative) for a drink created offline.

/** Every drink destination (the shell shows them through `drinks/DrinkRoutes.kt` `DrinkRoute`). */
sealed interface DrinkDestination : NavKey

/** "Drycker": every drink, filterable by type (Vin, Öl, Cider, Alkoholfritt, Drink). */
@Serializable
data class DrinksKey(val type: String? = null) : DrinkDestination

/** One drink: the recipe detail plus "Passar till" (the dishes it goes with). */
@Serializable
data class DrinkDetailKey(val recipeId: Long) : DrinkDestination

/**
 * The pairing picker for [recipeId] (a dish or a drink): pick drinks (for a dish), dishes (for a
 * drink) or a pairing type ("Kraftigt rött"); writes the `Passar med` keyword from both sides.
 */
@Serializable
data class PairingPickerKey(val recipeId: Long) : DrinkDestination

/** "Skapa dryck": name, type, photo, note, pairings; full screen (a form). */
@Serializable
data class CreateDrinkKey(val pairWithRecipeId: Long? = null) :
    DrinkDestination,
    FullScreenDestination
