package casa.moller.tandem.feature.recipes.books

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.icon.Bookmark
import casa.moller.tandem.core.designsystem.icon.BookmarkFilled
import casa.moller.tandem.core.designsystem.icon.CollectionsBookmark
import casa.moller.tandem.core.designsystem.icon.Favorite
import casa.moller.tandem.core.designsystem.icon.FavoriteFilled
import casa.moller.tandem.core.designsystem.icon.LibraryAdd
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.WineBar
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.recipes.R

/**
 * The recipe detail's "Favorit", "Vill laga" and "Lägg i bok" (plan 6.2 "Receptdetalj":
 * "Favorit/bok (bara i böcker du äger, §3.7)"): two toggle chips and a chip that opens the sheet
 * of the user's OWN books. Who else has the recipe as a favourite is said below ("Favorit hos
 * Alex"). A guest (#42) sees only that line. fix-ui (plan 6.2 "Receptdetalj: Åtgärder … Planera",
 * the 6.3 wireframe's "[Planera]"): [onPlanMeal] adds a "Planera" chip that plans the recipe in
 * Veckan; null hides it, and a guest never gets it.
 */
@Composable
fun RecipeBookButtons(
    state: RecipeBookButtonsState,
    onAction: (RecipeBookButtonsAction) -> Unit,
    modifier: Modifier = Modifier,
    onPlanMeal: (() -> Unit)? = null,
) {
    if (!state.loaded) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
        if (!state.readOnly) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs),
            ) {
                ToggleChip(
                    label = stringResource(R.string.books_action_favorite),
                    selected = state.isFavorite,
                    icon = if (state.isFavorite) TandemIcons.FavoriteFilled else TandemIcons.Favorite,
                    onClick = { onAction(RecipeBookButtonsAction.ToggleFavorite) },
                )
                ToggleChip(
                    label = stringResource(R.string.books_action_want_to_cook),
                    selected = state.wantToCook,
                    icon = if (state.wantToCook) TandemIcons.BookmarkFilled else TandemIcons.Bookmark,
                    onClick = { onAction(RecipeBookButtonsAction.ToggleWantToCook) },
                )
                AssistChip(
                    onClick = { onAction(RecipeBookButtonsAction.OpenBooks) },
                    label = { Text(stringResource(R.string.books_action_add_to_book)) },
                    leadingIcon = {
                        Icon(TandemIcons.LibraryAdd, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                if (onPlanMeal != null) {
                    AssistChip(
                        onClick = onPlanMeal,
                        label = { Text(stringResource(R.string.books_action_plan)) },
                        leadingIcon = {
                            val size = Modifier.size(AssistChipDefaults.IconSize)
                            Icon(TandemIcons.CalendarMonth, contentDescription = null, modifier = size)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(RECIPE_PLAN_MEAL_TAG),
                    )
                }
            }
        }
        if (state.favoriteOf.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
                Icon(
                    imageVector = TandemIcons.FavoriteFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                )
                Text(
                    text = stringResource(R.string.books_favorite_of, state.favoriteOf.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (state.sheetOpen) {
        AddToBookSheet(state = state, onAction = onAction)
    }
}

/** Test tag of the recipe detail's "Planera" chip (fix-ui). */
const val RECIPE_PLAN_MEAL_TAG = "recipe:plan-meal"

/** A filter chip that toggles (its state is said by TalkBack and shown by the filled icon, not colour alone). */
@Composable
private fun ToggleChip(label: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

/** "Lägg i bok": the user's own books with a check each, and a new book (plan 3.7: never a shared one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToBookSheet(state: RecipeBookButtonsState, onAction: (RecipeBookButtonsAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = { onAction(RecipeBookButtonsAction.DismissBooks) }) {
        AddToBookContent(state = state, onAction = onAction)
    }
}

/** The sheet's content (also shown by the screenshot tests). */
@Composable
internal fun AddToBookContent(state: RecipeBookButtonsState, onAction: (RecipeBookButtonsAction) -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.lg)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.books_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(if (state.books.isEmpty()) R.string.books_sheet_empty else R.string.books_sheet_only_own),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.books.forEach { book ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(
                        value = book.contains,
                        role = Role.Checkbox,
                        onValueChange = { onAction(RecipeBookButtonsAction.ToggleBook(book.id)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Checkbox(checked = book.contains, onCheckedChange = null)
                Icon(TandemIcons.CollectionsBookmark, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = book.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.books_new_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (name.isNotBlank()) {
                    onAction(RecipeBookButtonsAction.CreateBook(name))
                    name = ""
                }
            }),
            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        )
        TandemPrimaryButton(
            text = stringResource(R.string.books_sheet_create),
            onClick = {
                onAction(RecipeBookButtonsAction.CreateBook(name))
                name = ""
            },
            enabled = name.isNotBlank(),
            leadingIcon = TandemIcons.LibraryAdd,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The collections above the recipe list (Stitch recept_s_k_filter_samlingar, plan 6.1 "böcker som
 * chips"): "Favoriter 24", "Vill laga 5" and "Böcker". Each opens its page.
 */
@Composable
fun RecipeCollectionsRow(state: RecipeCollectionsState, onOpen: (RecipeCollection) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
    ) {
        CollectionChip(
            label = stringResource(R.string.books_favorites_title),
            shortCount = state.favoriteCount.toString(),
            icon = TandemIcons.FavoriteFilled,
            onClick = { onOpen(RecipeCollection.FAVORITES) },
        )
        CollectionChip(
            label = stringResource(R.string.books_want_to_cook_title),
            shortCount = state.wantToCookCount.toString(),
            icon = TandemIcons.BookmarkFilled,
            onClick = { onOpen(RecipeCollection.WANT_TO_COOK) },
        )
        CollectionChip(
            label = stringResource(R.string.books_collection_books),
            shortCount = null,
            icon = TandemIcons.CollectionsBookmark,
            onClick = { onOpen(RecipeCollection.BOOKS) },
        )
        // Fas S (drycker lane, plan §8.8 "Recept får en Drycker-vy").
        CollectionChip(
            label = stringResource(R.string.drinks_collection),
            shortCount = null,
            icon = TandemIcons.WineBar,
            onClick = { onOpen(RecipeCollection.DRINKS) },
        )
    }
}

@Composable
private fun CollectionChip(label: String, shortCount: String?, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(if (shortCount == null) label else "$label  $shortCount") },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}
