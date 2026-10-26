package casa.moller.tandem.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.book.RecipeBookRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.model.recipe.RecipeOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Phase 2 (books lane): what Today shows from the books (plan 6.2 "Idag", 8.3, #17, #42): the
 * "Vill laga" row and, for a guest, the read-only banner. Phase 2 (integration lane): also the
 * household's favourites (every member's favourites book, like Recept → Favoriter).
 */
@Immutable
data class TodayBooksState(
    val wantToCook: List<RecipeOverview> = emptyList(),
    val readOnly: Boolean = false,
    val favorites: List<RecipeOverview> = emptyList(),
)

/** [TodayBooksState] from the books and the user's role (separate from [HomeViewModel], which serves the plan). */
@HiltViewModel
class TodayBooksViewModel @Inject constructor(books: RecipeBookRepository, permissions: SpacePermissionsRepository) : ViewModel() {
    val uiState: StateFlow<TodayBooksState> = combine(
        books.observeWantToCook(),
        books.observeFavorites(),
        permissions.permissions,
    ) { want, favorites, perms ->
        TodayBooksState(wantToCook = want.take(MAX_RECIPES), readOnly = perms.readOnly, favorites = favorites.take(MAX_RECIPES))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TodayBooksState())

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** As many as the other carousels show. */
        const val MAX_RECIPES = 12
    }
}
