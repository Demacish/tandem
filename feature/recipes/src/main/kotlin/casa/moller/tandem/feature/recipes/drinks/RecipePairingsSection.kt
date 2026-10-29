package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.WineBar
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.recipes.R

/** Test tag of the detail's "Passar med" / "Passar till" section. */
const val RECIPE_PAIRINGS_TAG: String = "recipe:pairings"

/**
 * "Passar med" on a dish and "Passar till" on a drink (plan §8.8) in the recipe detail, with its
 * own ViewModel; messages go to the detail's [snackbarHostState].
 *
 * @param hasIngredients the recipe's detail lists ingredients: a drink without them gets its own
 *   "Lägg på inköpslistan" (the drink itself on the list); one with them uses the detail's sheet.
 * @param onOpenPicker the pairing picker (`PairingPickerKey`).
 * @param onOpenRecipe a drink or a dish.
 * @param onOpenUrl "Sök på Systembolaget".
 */
@Composable
fun RecipePairingsRoute(
    recipeId: Long,
    hasIngredients: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenPicker: (Long) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<RecipePairingsViewModel, RecipePairingsViewModel.Factory>(
        key = "recipe-pairings-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecipePairingsEffect.Message -> snackbarHostState.showSnackbar(
                    effect.argument?.let { resources.getString(effect.text, it) } ?: resources.getString(effect.text),
                )
            }
        }
    }
    RecipePairingsSection(
        state = state,
        onAction = viewModel::onAction,
        onOpenPicker = { onOpenPicker(recipeId) },
        onOpenRecipe = onOpenRecipe,
        onOpenUrl = onOpenUrl,
        canAddToList = !hasIngredients,
        modifier = modifier,
    )
}

/**
 * Stateless: a heading, the paired drinks (photo, name, type; tap opens), the kinds of drink as
 * text, or a drink's dishes, each with a 48 dp remove button (not for a guest), and "Välj dryck" /
 * "Välj rätter". A drink also gets "Sök på Systembolaget" (when on) and, without ingredients,
 * "Lägg på inköpslistan". Nothing until the state is loaded.
 */
@Composable
fun RecipePairingsSection(
    state: RecipePairingsUiState,
    onAction: (RecipePairingsAction) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    canAddToList: Boolean = false,
) {
    if (!state.loaded) return
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth().testTag(RECIPE_PAIRINGS_TAG), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(if (state.isDrink) R.string.drinks_pairs_to_heading else R.string.drinks_pairs_with_heading),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (state.isEmpty) {
            Text(
                text = stringResource(if (state.isDrink) R.string.drinks_pairs_to_empty else R.string.drinks_pairs_with_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.drinks.forEach { paired ->
            PairedRow(
                name = paired.drink.name,
                supporting = paired.drink.types.map { drinkTypeLabel(it) }.joinToString(", ").ifEmpty { null },
                imageUrl = paired.drink.imageUrl,
                openLabel = stringResource(R.string.drinks_open_drink, paired.drink.name),
                onOpen = { onOpenRecipe(paired.drink.id) }.takeUnless { paired.drink.pending },
                onRemove = { onAction(RecipePairingsAction.RemoveDrink(paired)) }.takeUnless { state.readOnly },
            )
        }
        if (state.kinds.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                state.kinds.forEach { kind ->
                    KindChip(name = kind.name, onRemove = { onAction(RecipePairingsAction.RemoveKind(kind)) }.takeUnless { state.readOnly })
                }
            }
        }
        state.dishes.forEach { dish ->
            PairedRow(
                name = dish.name,
                supporting = null,
                imageUrl = dish.imageUrl,
                openLabel = stringResource(R.string.drinks_open_dish, dish.name),
                onOpen = { onOpenRecipe(dish.id) },
                onRemove = { onAction(RecipePairingsAction.RemoveDish(dish.id)) }.takeUnless { state.readOnly },
            )
        }
        Actions(state = state, onAction = onAction, onOpenPicker = onOpenPicker, onOpenUrl = onOpenUrl, canAddToList = canAddToList)
    }
}

@Composable
private fun Actions(
    state: RecipePairingsUiState,
    onAction: (RecipePairingsAction) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenUrl: (String) -> Unit,
    canAddToList: Boolean,
) {
    val spacing = TandemTheme.spacing
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        if (!state.readOnly) {
            TandemSecondaryButton(
                text = stringResource(if (state.isDrink) R.string.drinks_pairs_to_add else R.string.drinks_pairs_with_add),
                onClick = onOpenPicker,
                leadingIcon = TandemIcons.Add,
            )
        }
        if (state.isDrink && canAddToList && !state.readOnly) {
            TandemSecondaryButton(
                text = stringResource(R.string.drinks_add_to_list),
                onClick = { onAction(RecipePairingsAction.AddToList) },
                leadingIcon = TandemIcons.ShoppingCart,
            )
        }
        state.systembolagetUrl?.let { url ->
            TandemTextButton(
                text = stringResource(R.string.drinks_systembolaget),
                onClick = { onOpenUrl(url) },
                leadingIcon = TandemIcons.OpenInNew,
            )
        }
    }
}

/** A paired drink or dish: one TalkBack item that opens it, and a separate remove button. */
@Composable
private fun PairedRow(
    name: String,
    supporting: String?,
    imageUrl: String?,
    openLabel: String,
    onOpen: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val spacing = TandemTheme.spacing
    val clickable = if (onOpen != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen) else Modifier
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large)
                    .then(clickable)
                    .semantics(mergeDescendants = true) {}
                    .heightIn(min = 64.dp)
                    .padding(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                RecipeImage(
                    imageUrl = imageUrl,
                    placeholderIconSize = TandemTheme.sizes.iconSmall,
                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.medium),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    supporting?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(TandemIcons.Close, contentDescription = stringResource(R.string.drinks_remove_pairing, name))
                }
            }
        }
    }
}

/** A pairing with a kind of drink ("Kraftigt rött"): text with a glass, and a remove button. */
@Composable
private fun KindChip(name: String, onRemove: (() -> Unit)?) {
    val description = stringResource(R.string.drinks_type_only_description, name)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(start = TandemTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        ) {
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
            ) {
                Icon(TandemIcons.WineBar, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.iconSmall))
                Text(text = name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(TandemIcons.Close, contentDescription = stringResource(R.string.drinks_remove_pairing, name))
                }
            } else {
                Spacer(Modifier.width(TandemTheme.spacing.xs))
            }
        }
    }
}
