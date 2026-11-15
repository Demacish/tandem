package casa.moller.tandem.feature.recipes.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

// Fas 5 (rss lane, plan §7 #35, §8.4): "Inkorg för recept". @Serializable so back stacks survive
// process death.

/**
 * The recipe inbox, on the Recipes tab (from the list's top bar and Idag's "Nytt i inkorgen").
 *
 * @property entryId a post whose preview opens at once (a post tapped on Idag), null for the list.
 */
@Serializable
data class RecipeInboxKey(val entryId: Long? = null) :
    NavKey,
    FullScreenDestination

/** Mer → Inställningar → Inkorg för recept (beside Mer on wide windows, like the other settings pages). */
@Serializable
data object RecipeInboxSettingsKey : NavKey
