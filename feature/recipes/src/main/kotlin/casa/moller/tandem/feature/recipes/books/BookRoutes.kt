// Placeholder routes (Phase 2 grund lane): the parameters are the contract with feature:shell and
// are used once the books lane builds the real screens.
@file:Suppress("UnusedParameter")

package casa.moller.tandem.feature.recipes.books

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.feature.recipes.R

// Phase 2 (grund lane): the routes feature:shell shows for books (docs/BYGGE.md "Fas 2 – kontrakt").
// The books lane replaces the placeholder bodies with the real screens (ViewModels on
// RecipeBookRepository, write buttons only when RecipeBook.canEdit and not read-only).

/** All books; [onOpenBook], [onOpenFavorites] and [onOpenWantToCook] open one. */
@Composable
fun BooksRoute(
    onOpenBook: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWantToCook: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BookPlaceholder(stringResource(R.string.books_title), onBack, modifier)
}

/** One book's recipes. */
@Composable
fun BookDetailRoute(bookId: Long, onOpenRecipe: (Long) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BookPlaceholder(stringResource(R.string.books_title), onBack, modifier)
}

/** The merged favourites. */
@Composable
fun FavoritesRoute(onOpenRecipe: (Long) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BookPlaceholder(stringResource(R.string.books_favorites_title), onBack, modifier)
}

/** "Vill laga". [onPlanMeal] plans a recipe from the queue ("Planera", plan 8.3). */
@Composable
fun WantToCookRoute(onOpenRecipe: (Long) -> Unit, onPlanMeal: (Long) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BookPlaceholder(stringResource(R.string.books_want_to_cook_title), onBack, modifier)
}

@Composable
private fun BookPlaceholder(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { TandemTopAppBar(title = title, onNavigateBack = onBack, actions = { AppChromeStatusChip() }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AppChromeDemoBanner()
            EmptyState(
                icon = TandemIcons.MenuBook,
                title = title,
                body = stringResource(R.string.books_placeholder_body),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
