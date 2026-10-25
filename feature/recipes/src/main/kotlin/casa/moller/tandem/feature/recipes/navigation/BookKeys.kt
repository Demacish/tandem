package casa.moller.tandem.feature.recipes.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

// Phase 2 (grund lane): recipe books, favourites and "Vill laga" (plan 6.1: books live with the
// recipes; 6.2 "Böcker/Favoriter", 3.7, feature #17). Pushed on the tab they are opened from (the
// Recipes tab's chips, Mer → Böcker). @Serializable so back stacks survive process death.

/** Every book the user can see (own and shared), with Favoriter and "Vill laga" first. */
@Serializable
data object BooksKey : NavKey, FullScreenDestination

/** One book's recipes; write buttons only for an own book (plan 3.7). [bookId] may be a temporary (negative) id. */
@Serializable
data class BookDetailKey(val bookId: Long) :
    NavKey,
    FullScreenDestination

/** Every household member's favourites, merged (one book per person, plan 3.7 A). */
@Serializable
data object FavoritesKey : NavKey, FullScreenDestination

/** "Vill laga": recipes with the shared keyword (plan 3.7 B, §8.3). */
@Serializable
data object WantToCookKey : NavKey, FullScreenDestination
