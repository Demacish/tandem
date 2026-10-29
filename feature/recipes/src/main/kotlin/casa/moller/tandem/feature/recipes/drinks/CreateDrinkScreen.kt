package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.Image
import casa.moller.tandem.core.designsystem.icon.PhotoCamera
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.WineBar
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.recipes.R

/**
 * "Ny dryck" (plan §8.8), full screen: name, type chips, note and photo in a scrolling form, the
 * save button fixed below it (above the keyboard and the navigation bar, like
 * RecipeShoppingSheetLayout). The photo comes from the camera or the system photo picker (no
 * storage permission); the camera is asked for only when "Ta foto" is tapped, with the reason
 * written under the buttons, and the picker works when it is denied. Width-limited on tablets.
 *
 * @param onTakePhoto / [onPickPhoto] start the camera / the photo picker (the route owns the launchers).
 */
@Composable
fun CreateDrinkScreen(
    state: CreateDrinkUiState,
    onAction: (CreateDrinkAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.drinks_create_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Form(
                    state = state,
                    onAction = onAction,
                    onTakePhoto = onTakePhoto,
                    onPickPhoto = onPickPhoto,
                    modifier = Modifier.widthIn(max = FORM_MAX_WIDTH).verticalScroll(rememberScrollState()),
                )
            }
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                TandemPrimaryButton(
                    text = stringResource(R.string.drinks_create_save),
                    onClick = { onAction(CreateDrinkAction.Save) },
                    enabled = !state.saving && !state.readOnly,
                    leadingIcon = TandemIcons.Check,
                    modifier = Modifier
                        .widthIn(max = FORM_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.md),
                )
            }
        }
    }
}

@Composable
private fun Form(
    state: CreateDrinkUiState,
    onAction: (CreateDrinkAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier,
) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (state.readOnly) ReadOnlyBanner(compact = true)
        state.pairWithName?.let { dish ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Icon(TandemIcons.WineBar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(text = stringResource(R.string.drinks_create_pair_with, dish), style = MaterialTheme.typography.bodyLarge)
            }
        }
        TandemTextField(
            value = state.name,
            onValueChange = { onAction(CreateDrinkAction.SetName(it)) },
            label = stringResource(R.string.drinks_create_name),
            supportingText = if (state.nameMissing) stringResource(R.string.drinks_create_name_missing) else null,
            isError = state.nameMissing,
            leadingIcon = TandemIcons.WineBar,
            modifier = Modifier.fillMaxWidth(),
        )
        Heading(stringResource(R.string.drinks_create_type))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            state.types.forEach { type ->
                val selected = type in state.selectedTypes
                FilterChip(
                    selected = selected,
                    onClick = { onAction(CreateDrinkAction.ToggleType(type)) },
                    label = { Text(drinkTypeLabel(type)) },
                    leadingIcon = if (selected) {
                        { Icon(TandemIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        TandemTextField(
            value = state.note,
            onValueChange = { onAction(CreateDrinkAction.SetNote(it)) },
            label = stringResource(R.string.drinks_create_note),
            placeholder = stringResource(R.string.drinks_create_note_hint),
            leadingIcon = null,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )
        Heading(stringResource(R.string.drinks_create_photo))
        Photo(state = state, onAction = onAction, onTakePhoto = onTakePhoto, onPickPhoto = onPickPhoto)
        Text(
            text = stringResource(R.string.drinks_create_web_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Photo(state: CreateDrinkUiState, onAction: (CreateDrinkAction) -> Unit, onTakePhoto: () -> Unit, onPickPhoto: () -> Unit) {
    val spacing = TandemTheme.spacing
    val chosen = stringResource(R.string.drinks_create_photo_chosen)
    state.photoUri?.let { uri ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                RecipeImage(
                    imageUrl = uri,
                    contentDescription = chosen,
                    modifier = Modifier.size(PHOTO_PREVIEW).clip(MaterialTheme.shapes.medium),
                )
                TandemTextButton(
                    text = stringResource(R.string.drinks_create_remove_photo),
                    onClick = { onAction(CreateDrinkAction.SetPhoto(null)) },
                    leadingIcon = TandemIcons.Delete,
                )
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        TandemSecondaryButton(
            text = stringResource(R.string.drinks_create_take_photo),
            onClick = onTakePhoto,
            leadingIcon = TandemIcons.PhotoCamera,
        )
        TandemSecondaryButton(
            text = stringResource(R.string.drinks_create_pick_photo),
            onClick = onPickPhoto,
            leadingIcon = TandemIcons.Image,
        )
    }
    Text(
        text = stringResource(R.string.drinks_create_camera_rationale),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Heading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

private val FORM_MAX_WIDTH = 640.dp
private val PHOTO_PREVIEW = 160.dp
