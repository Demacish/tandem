package casa.moller.tandem.feature.cook.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.core.ui.recipe.formatMinutes
import casa.moller.tandem.core.ui.timer.KitchenIconButton
import casa.moller.tandem.feature.cook.R

/**
 * "Laga ett recept till" as a bottom sheet over cook mode (Fas 4, plan 8.1): the recipe picker
 * ([CookRecipePickerContent]) until a recipe is picked or the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookRecipePickerSheet(state: CookPickerState, onAction: (CookSessionAction) -> Unit, offline: Boolean = false) {
    if (!state.open) return
    ModalBottomSheet(
        onDismissRequest = { onAction(CookSessionAction.ClosePicker) },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        CookRecipePickerContent(state = state, onAction = onAction, offline = offline, modifier = Modifier.imePadding())
    }
}

/**
 * The picker itself: title and ✕, what happens, the search, and the stored recipes (most recently
 * cooked first, or the matches by name; the open recipe is left out). Works offline and in demo
 * mode (the stored recipes). One TalkBack item per recipe ("Kanelbullar, 1 h 30 min").
 */
@Composable
internal fun CookRecipePickerContent(
    state: CookPickerState,
    onAction: (CookSessionAction) -> Unit,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
) {
    val spacing = TandemTheme.spacing
    // Offline a recipe without a stored detail cannot open: the ones that can come first.
    val recipes = if (offline) state.results.sortedBy { !it.availableOffline } else state.results
    // Kept here, not read back from the ViewModel: typing never waits for the search's results.
    var text by rememberSaveable { mutableStateOf(state.query) }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(COOK_PICKER_TAG),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            bottom = spacing.lg,
        ),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.cook_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                KitchenIconButton(
                    icon = TandemIcons.Close,
                    description = stringResource(R.string.cook_picker_close),
                    onClick = { onAction(CookSessionAction.ClosePicker) },
                )
            }
        }
        // One node that stays, so TalkBack says how a search went (a new item would not be announced).
        item(key = "status") {
            Text(
                text = pickerStatus(state, text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        item(key = "search") {
            TandemTextField(
                value = text,
                onValueChange = {
                    text = it
                    onAction(CookSessionAction.SetQuery(it))
                },
                label = stringResource(R.string.cook_picker_search),
                leadingIcon = TandemIcons.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(recipes, key = { "recipe-${it.id}" }) { recipe ->
            PickerRecipe(
                recipe = recipe,
                missingOffline = offline && !recipe.availableOffline,
                onPick = { onAction(CookSessionAction.AddRecipe(recipe.id)) },
            )
        }
    }
}

/** What happens here; while searching, how the search went. */
@Composable
private fun pickerStatus(state: CookPickerState, text: String): String {
    val count = state.results.size
    return when {
        state.loading -> stringResource(R.string.cook_picker_hint)
        count == 0 && text.isBlank() -> stringResource(R.string.cook_picker_no_recipes)
        count == 0 -> stringResource(R.string.cook_picker_no_matches)
        text.isBlank() -> stringResource(R.string.cook_picker_hint)
        else -> pluralStringResource(R.plurals.cook_picker_matches, count, count)
    }
}

@Composable
private fun PickerRecipe(recipe: RecipeOverview, missingOffline: Boolean, onPick: () -> Unit) {
    val spacing = TandemTheme.spacing
    val minutes = recipe.totalMinutes.takeIf { it > 0 }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(cookPickerRecipeTag(recipe.id))
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.cook_picker_choose), role = Role.Button, onClick = onPick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                .padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            RecipeImage(
                imageUrl = recipe.imageUrl,
                placeholderIconSize = 20.dp,
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = recipe.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                minutes?.let {
                    Text(
                        text = formatMinutes(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (missingOffline) {
                    Text(
                        text = stringResource(R.string.cook_picker_not_downloaded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Test tags. */
internal const val COOK_PICKER_TAG = "cook:picker"

internal fun cookPickerRecipeTag(recipeId: Long): String = "cook:picker:recipe:$recipeId"
