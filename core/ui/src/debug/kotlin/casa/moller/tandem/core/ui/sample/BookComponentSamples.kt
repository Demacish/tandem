package casa.moller.tandem.core.ui.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.R
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.readonly.ReadOnlyNote
import casa.moller.tandem.core.ui.recipe.RecipeCard
import casa.moller.tandem.core.ui.recipe.RecipeFavorite
import casa.moller.tandem.core.ui.recipe.RecipeListItem

// Phase 2 (books lane): the hearts on recipe cards (#17) and the guest mode's shared pieces (#42),
// for the screenshot and ATF tests and the IDE.

private val favorite = RecipeOverview(
    id = 21,
    name = "Krämig svamppasta",
    workingMinutes = 20,
    waitingMinutes = 10,
    rating = 4.5,
    keywords = listOf(Keyword(1, "Pasta")),
    availableOffline = true,
)
private val other = favorite.copy(
    id = 22,
    name = "Ärtsoppa med fläsk",
    rating = 4.0,
    keywords = listOf(Keyword(2, "Soppa")),
    availableOffline = false,
)

/** Cards and rows with the heart: a favourite, not a favourite, and a guest's (no button). */
@Composable
fun RecipeFavoritesSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RecipeCard(recipe = favorite, onClick = {}, modifier = Modifier.width(170.dp), favorite = RecipeFavorite(true) {})
        RecipeCard(recipe = other, onClick = {}, modifier = Modifier.width(170.dp), favorite = RecipeFavorite(false) {})
    }
    RecipeListItem(recipe = favorite, onClick = {}, favorite = RecipeFavorite(true) {})
    RecipeListItem(recipe = other, onClick = {}, favorite = RecipeFavorite(false) {})
    RecipeListItem(recipe = favorite, onClick = {}, favorite = RecipeFavorite(isFavorite = true, onToggle = null))
}

/** The guest's read-only banner and the in-place note (the compact banner is shown by the books screens). */
@Composable
fun ReadOnlySample() {
    ReadOnlyBanner()
    ReadOnlyNote(text = stringResource(R.string.ui_read_only_short))
}
