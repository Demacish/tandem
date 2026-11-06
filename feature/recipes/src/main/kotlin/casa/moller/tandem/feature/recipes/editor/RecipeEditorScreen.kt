package casa.moller.tandem.feature.recipes.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.ArrowDownward
import casa.moller.tandem.core.designsystem.icon.ArrowUpward
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.Image
import casa.moller.tandem.core.designsystem.icon.PhotoCamera
import casa.moller.tandem.core.designsystem.icon.Remove
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.recipeimport.ORIGINAL_BESIDE_MIN_WIDTH
import casa.moller.tandem.feature.recipes.recipeimport.OriginalFold
import casa.moller.tandem.feature.recipes.recipeimport.OriginalPages
import casa.moller.tandem.feature.recipes.recipeimport.OriginalPane
import casa.moller.tandem.feature.recipes.recipeimport.OriginalView

/**
 * The recipe editor (Fas 3, redigerare lane; #22, plan §8.9; Stitch sketch
 * `redigera_recept_kr_mig_svamppasta`), full screen: the basics (name, photo, description,
 * servings, times, keywords) and the steps, each with its ingredients, in one scrolling form with
 * the save button fixed below it (above the keyboard and the navigation bar, as "Ny dryck"). Every
 * control is a plain text field or a 48 dp button, so it works with a large font and TalkBack
 * (every button that repeats names its step and line). Width-limited on tablets.
 *
 * At a large font or on a narrow phone the fields of a row stack ([stackedFields]). An import read
 * from a photo or PDF has the original above the form, or beside it from
 * [ORIGINAL_BESIDE_MIN_WIDTH] (plan §8.4, 6.5).
 *
 * @param onTakePhoto / [onPickPhoto] start the camera / the photo picker (the route owns the launchers).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(
    state: RecipeEditorUiState,
    onAction: (RecipeEditorAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    RecipeEditorLayout(
        state = state,
        onAction = onAction,
        onTakePhoto = onTakePhoto,
        onPickPhoto = onPickPhoto,
        onBack = onBack,
        keyboardOpen = WindowInsets.isImeVisible,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * [RecipeEditorScreen] with the keyboard state given ([keyboardOpen], tests have no keyboard).
 * In a low window (a phone in landscape, under [COMPACT_HEIGHT]) the fixed save bar steps aside
 * while the keyboard is open, or nothing is left of the form above it, and "Spara" stands last in
 * the form instead (the same rule as `PantryEntryLayout`).
 */
@Composable
internal fun RecipeEditorLayout(
    state: RecipeEditorUiState,
    onAction: (RecipeEditorAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onBack: () -> Unit,
    keyboardOpen: Boolean,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    originalView: OriginalView = { path, viewModifier -> OriginalPages(path, viewModifier) },
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val saveInForm = keyboardOpen && maxHeight < COMPACT_HEIGHT
        val canSave = state.loaded && !state.saving && !state.readOnly && !state.unknownRecipe
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TandemTopAppBar(
                    title = stringResource(if (state.isNew) R.string.recipe_editor_title_new else R.string.recipe_editor_title_edit),
                    onNavigateBack = onBack,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                EditorBody(
                    state = state,
                    onAction = onAction,
                    onTakePhoto = onTakePhoto,
                    onPickPhoto = onPickPhoto,
                    originalView = originalView,
                    save = if (saveInForm) ({ SaveButton(enabled = canSave, onAction = onAction) }) else null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (!saveInForm) {
                    HorizontalDivider()
                    Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                        SaveButton(
                            enabled = canSave,
                            onAction = onAction,
                            modifier = Modifier
                                .widthIn(max = FORM_MAX_WIDTH)
                                .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.md),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveButton(enabled: Boolean, onAction: (RecipeEditorAction) -> Unit, modifier: Modifier = Modifier) {
    TandemPrimaryButton(
        text = stringResource(R.string.recipe_editor_save),
        onClick = { onAction(RecipeEditorAction.Save) },
        enabled = enabled,
        leadingIcon = TandemIcons.Check,
        modifier = modifier.fillMaxWidth().testTag(EDITOR_SAVE_TAG),
    )
}

/** The form, with the original above it or, on a wide window, beside it. */
@Composable
private fun EditorBody(
    state: RecipeEditorUiState,
    onAction: (RecipeEditorAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    originalView: OriginalView,
    save: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        val stacked = stackedFields(maxWidth)
        val original = state.sourcePath
        val beside = original != null && maxWidth >= ORIGINAL_BESIDE_MIN_WIDTH
        val form: @Composable (Modifier) -> Unit = { formModifier ->
            when {
                state.unknownRecipe -> EmptyState(
                    icon = TandemIcons.MenuBook,
                    title = stringResource(R.string.recipe_editor_unknown_title),
                    body = stringResource(R.string.recipe_editor_unknown_body),
                )

                !state.loaded -> LoadingState(message = stringResource(R.string.recipe_editor_loading))

                else -> Form(
                    state = state,
                    onAction = onAction,
                    onTakePhoto = onTakePhoto,
                    onPickPhoto = onPickPhoto,
                    stacked = stacked,
                    originalView = originalView.takeUnless { beside },
                    save = save,
                    modifier = formModifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        if (beside && original != null) {
            Row(modifier = Modifier.fillMaxSize()) {
                OriginalPane(
                    path = original,
                    view = originalView,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.md),
                )
                form(Modifier.weight(1f))
            }
        } else {
            form(Modifier.widthIn(max = FORM_MAX_WIDTH))
        }
    }
}

/**
 * Plan 6.6 (font scale 2.0 without clipping): at a large font, or in a narrow window, fields that
 * share a row at their fixed widths break their labels mid-word and hide the value, so they stack.
 */
@Composable
private fun stackedFields(width: Dp): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE || width < NARROW_WIDTH

/** Leaving with unsaved changes: the draft lives only as long as the page. */
@Composable
internal fun LeaveEditorDialog(onSave: () -> Unit, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.recipe_editor_leave_title)) },
        text = { Text(stringResource(R.string.recipe_editor_leave_body)) },
        confirmButton = { TandemPrimaryButton(text = stringResource(R.string.recipe_editor_save), onClick = onSave) },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
                TandemTonalButton(text = stringResource(R.string.recipe_editor_leave_discard), onClick = onDiscard)
                TandemTonalButton(text = stringResource(R.string.recipe_editor_leave_keep), onClick = onKeepEditing)
            }
        },
    )
}

/** "Släng min ändring": a held edit cannot be got back (the detail's notice and the editor's). */
@Composable
internal fun DiscardEditDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recipe_editor_discard_title)) },
        text = { Text(stringResource(R.string.recipe_editor_discard_body)) },
        confirmButton = { TandemPrimaryButton(text = stringResource(R.string.recipe_editor_discard_confirm), onClick = onConfirm) },
        dismissButton = { TandemTonalButton(text = stringResource(R.string.recipe_editor_discard_cancel), onClick = onDismiss) },
    )
}

@Composable
private fun Form(
    state: RecipeEditorUiState,
    onAction: (RecipeEditorAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    stacked: Boolean,
    originalView: OriginalView?,
    save: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val spacing = TandemTheme.spacing
    val draft = state.draft
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    if (confirmingDiscard) {
        DiscardEditDialog(
            onConfirm = {
                confirmingDiscard = false
                onAction(RecipeEditorAction.DiscardKeptEdit)
            },
            onDismiss = { confirmingDiscard = false },
        )
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (state.readOnly) ReadOnlyBanner(compact = true)
        if (state.keptEdit) {
            CalloutCard(
                title = stringResource(R.string.recipe_editor_kept_edit_title),
                body = stringResource(R.string.recipe_editor_kept_edit_body),
                icon = TandemIcons.Info,
                tone = TandemTone.Warning,
                actions = {
                    TandemTonalButton(
                        text = stringResource(R.string.recipe_editor_kept_edit_discard),
                        onClick = { confirmingDiscard = true },
                    )
                },
            )
        }
        if (originalView != null) state.sourcePath?.let { OriginalFold(path = it, view = originalView) }
        Heading(stringResource(R.string.recipe_editor_basics))
        TandemTextField(
            value = draft.name,
            onValueChange = { onAction(RecipeEditorAction.SetName(it)) },
            label = stringResource(R.string.recipe_editor_name),
            supportingText = if (state.nameMissing) stringResource(R.string.recipe_editor_name_missing) else null,
            isError = state.nameMissing,
            leadingIcon = TandemIcons.MenuBook,
            modifier = Modifier.fillMaxWidth(),
        )
        MultilineField(
            value = draft.description,
            onValueChange = { onAction(RecipeEditorAction.SetDescription(it)) },
            label = stringResource(R.string.recipe_editor_description),
            placeholder = stringResource(R.string.recipe_editor_description_hint),
        )
        Photo(state = state, onAction = onAction, onTakePhoto = onTakePhoto, onPickPhoto = onPickPhoto)
        Servings(draft.servings, onAction)
        TandemTextField(
            value = draft.servingsText,
            onValueChange = { onAction(RecipeEditorAction.SetServingsText(it)) },
            label = stringResource(R.string.recipe_editor_servings_text),
            placeholder = stringResource(R.string.recipe_editor_servings_text_hint),
            leadingIcon = null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            MinutesField(
                minutes = draft.workingMinutes,
                onChange = { onAction(RecipeEditorAction.SetWorkingMinutes(it)) },
                label = stringResource(R.string.recipe_editor_working_time),
                modifier = Modifier.weight(1f),
            )
            MinutesField(
                minutes = draft.waitingMinutes,
                onChange = { onAction(RecipeEditorAction.SetWaitingMinutes(it)) },
                label = stringResource(R.string.recipe_editor_waiting_time),
                modifier = Modifier.weight(1f),
            )
        }
        TandemTextField(
            value = draft.sourceUrl,
            onValueChange = { onAction(RecipeEditorAction.SetSourceUrl(it)) },
            label = stringResource(R.string.recipe_editor_source_url),
            type = TandemTextFieldType.Url,
            leadingIcon = null,
            modifier = Modifier.fillMaxWidth(),
        )
        Heading(stringResource(R.string.recipe_editor_keywords))
        Keywords(state, onAction)
        Heading(stringResource(R.string.recipe_editor_steps))
        Text(
            text = stringResource(R.string.recipe_editor_steps_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        draft.steps.forEachIndexed { index, step ->
            StepEditor(index = index, step = step, count = draft.steps.size, state = state, stacked = stacked, onAction = onAction)
        }
        TandemSecondaryButton(
            text = stringResource(R.string.recipe_editor_add_step),
            onClick = { onAction(RecipeEditorAction.AddStep) },
            leadingIcon = TandemIcons.Add,
            modifier = Modifier.fillMaxWidth(),
        )
        save?.invoke()
    }
}

@Composable
private fun Servings(servings: Int, onAction: (RecipeEditorAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        // Rows that hold icon buttons are at least 48 dp tall: Compose clips a button's 48 dp touch
        // target to its row, and a row shorter than that (a large-font title) fails ATF.
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET).padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.recipe_editor_servings),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onAction(RecipeEditorAction.SetServings(servings - 1)) },
                enabled = servings > 1,
                modifier = Modifier.size(TOUCH_TARGET),
            ) {
                Icon(TandemIcons.Remove, contentDescription = stringResource(R.string.recipe_editor_servings_fewer))
            }
            Text(
                text = servings.toString(),
                style = MaterialTheme.typography.titleLarge,
                // A node that stays: TalkBack says the new value after + and −.
                modifier = Modifier.padding(horizontal = spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = { onAction(RecipeEditorAction.SetServings(servings + 1)) }, modifier = Modifier.size(TOUCH_TARGET)) {
                Icon(TandemIcons.Add, contentDescription = stringResource(R.string.recipe_editor_servings_more))
            }
        }
    }
}

@Composable
private fun Keywords(state: RecipeEditorUiState, onAction: (RecipeEditorAction) -> Unit) {
    val spacing = TandemTheme.spacing
    var typed by rememberSaveable { mutableStateOf("") }
    val add = {
        if (typed.isNotBlank()) {
            onAction(RecipeEditorAction.AddKeyword(DraftKeyword(name = typed.trim())))
            typed = ""
        }
    }
    if (state.draft.keywords.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            state.draft.keywords.forEachIndexed { index, keyword ->
                val remove = stringResource(R.string.recipe_editor_keyword_remove, keyword.name)
                InputChip(
                    selected = true,
                    onClick = { onAction(RecipeEditorAction.RemoveKeyword(index)) },
                    label = { Text(keyword.name) },
                    trailingIcon = {
                        Icon(TandemIcons.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = remove },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        TandemTextField(
            value = typed,
            onValueChange = { typed = it },
            label = stringResource(R.string.recipe_editor_keyword_add_label),
            leadingIcon = null,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        TandemTonalButton(text = stringResource(R.string.recipe_editor_keyword_add), onClick = add, enabled = typed.isNotBlank())
    }
    val suggestions = state.keywordSuggestions
        .filter { typed.isBlank() || it.name.contains(typed.trim(), ignoreCase = true) }
        .take(MAX_SUGGESTIONS)
    if (suggestions.isNotEmpty()) {
        Text(
            text = stringResource(R.string.recipe_editor_keyword_suggestions),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            suggestions.forEach { keyword ->
                SuggestionChip(
                    onClick = {
                        onAction(RecipeEditorAction.AddKeyword(keyword))
                        typed = ""
                    },
                    label = { Text(keyword.name) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun Photo(state: RecipeEditorUiState, onAction: (RecipeEditorAction) -> Unit, onTakePhoto: () -> Unit, onPickPhoto: () -> Unit) {
    val spacing = TandemTheme.spacing
    val chosen = stringResource(R.string.recipe_editor_photo_chosen)
    Heading(stringResource(R.string.recipe_editor_photo))
    val webImage = state.draft.imageUrl?.takeIf { state.draft.imageUri == null }
    state.draft.imageUri?.let { uri ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                RecipeImage(
                    imageUrl = uri,
                    contentDescription = chosen,
                    modifier = Modifier.size(PHOTO_PREVIEW).clip(MaterialTheme.shapes.medium),
                )
                TandemTextButton(
                    text = stringResource(R.string.recipe_editor_remove_photo),
                    onClick = { onAction(RecipeEditorAction.SetPhoto(null)) },
                    leadingIcon = TandemIcons.Delete,
                )
            }
        }
    }
    // Fas 3 (import lane): the picture the source offered; the server downloads it with the recipe.
    webImage?.let { url ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(modifier = Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                RecipeImage(
                    imageUrl = url,
                    contentDescription = stringResource(R.string.recipe_editor_web_photo),
                    modifier = Modifier.size(PHOTO_PREVIEW).clip(MaterialTheme.shapes.medium),
                )
                Text(stringResource(R.string.recipe_editor_web_photo), style = MaterialTheme.typography.bodySmall)
                TandemTextButton(
                    text = stringResource(R.string.recipe_editor_remove_web_photo),
                    onClick = { onAction(RecipeEditorAction.RemoveWebImage) },
                    leadingIcon = TandemIcons.Delete,
                )
            }
        }
    }
    CurrentImage(state, onAction)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        TandemSecondaryButton(
            text = stringResource(R.string.recipe_editor_take_photo),
            onClick = onTakePhoto,
            leadingIcon = TandemIcons.PhotoCamera,
        )
        TandemSecondaryButton(
            text = stringResource(R.string.recipe_editor_pick_photo),
            onClick = onPickPhoto,
            leadingIcon = TandemIcons.Image,
        )
    }
    Text(
        text = stringResource(if (state.isNew) R.string.recipe_editor_camera_rationale else R.string.recipe_editor_photo_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Fas 3's leftovers: the stored recipe's picture (no new photo or source picture chosen) with
 * "Ta bort bilden"; once removed, a line that says so with "Behåll bilden".
 */
@Composable
private fun CurrentImage(state: RecipeEditorUiState, onAction: (RecipeEditorAction) -> Unit) {
    val draft = state.draft
    val current = draft.currentImageUrl?.takeIf { draft.imageUri == null && draft.imageUrl == null } ?: return
    val spacing = TandemTheme.spacing
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (draft.keepImage) {
                RecipeImage(
                    imageUrl = current,
                    contentDescription = stringResource(R.string.recipe_editor_current_photo),
                    modifier = Modifier.size(PHOTO_PREVIEW).clip(MaterialTheme.shapes.medium),
                )
                TandemTextButton(
                    text = stringResource(R.string.recipe_editor_remove_current_photo),
                    onClick = { onAction(RecipeEditorAction.KeepImage(false)) },
                    leadingIcon = TandemIcons.Delete,
                )
            } else {
                Text(stringResource(R.string.recipe_editor_current_photo_removed), style = MaterialTheme.typography.bodyMedium)
                TandemTextButton(
                    text = stringResource(R.string.recipe_editor_keep_current_photo),
                    onClick = { onAction(RecipeEditorAction.KeepImage(true)) },
                )
            }
        }
    }
}

@Composable
private fun StepEditor(
    index: Int,
    step: DraftStep,
    count: Int,
    state: RecipeEditorUiState,
    stacked: Boolean,
    onAction: (RecipeEditorAction) -> Unit,
) {
    val spacing = TandemTheme.spacing
    val number = index + 1
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(modifier = Modifier.heightIn(min = TOUCH_TARGET), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.recipe_editor_step_number, number),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(
                    onClick = { onAction(RecipeEditorAction.MoveStep(index, up = true)) },
                    enabled = index > 0,
                    modifier = Modifier.size(TOUCH_TARGET),
                ) {
                    Icon(TandemIcons.ArrowUpward, contentDescription = stringResource(R.string.recipe_editor_step_move_up, number))
                }
                IconButton(
                    onClick = { onAction(RecipeEditorAction.MoveStep(index, up = false)) },
                    enabled = index < count - 1,
                    modifier = Modifier.size(TOUCH_TARGET),
                ) {
                    Icon(TandemIcons.ArrowDownward, contentDescription = stringResource(R.string.recipe_editor_step_move_down, number))
                }
                IconButton(onClick = { onAction(RecipeEditorAction.RemoveStep(index)) }, modifier = Modifier.size(TOUCH_TARGET)) {
                    Icon(TandemIcons.Delete, contentDescription = stringResource(R.string.recipe_editor_step_remove, number))
                }
            }
            val stepName: @Composable (Modifier) -> Unit = { fieldModifier ->
                TandemTextField(
                    value = step.name,
                    onValueChange = { onAction(RecipeEditorAction.SetStepName(index, it)) },
                    label = stringResource(R.string.recipe_editor_step_name),
                    leadingIcon = null,
                    modifier = fieldModifier,
                )
            }
            val stepMinutes: @Composable (Modifier) -> Unit = { fieldModifier ->
                MinutesField(
                    minutes = step.timeMinutes,
                    onChange = { onAction(RecipeEditorAction.SetStepMinutes(index, it)) },
                    label = stringResource(R.string.recipe_editor_step_minutes),
                    modifier = fieldModifier,
                )
            }
            if (stacked) {
                stepName(Modifier.fillMaxWidth())
                stepMinutes(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    stepName(Modifier.weight(1f))
                    stepMinutes(Modifier.width(MINUTES_WIDTH))
                }
            }
            Text(text = stringResource(R.string.recipe_editor_ingredients), style = MaterialTheme.typography.titleSmall)
            step.ingredients.forEachIndexed { position, line ->
                IngredientEditor(
                    stepIndex = index,
                    position = position,
                    line = line,
                    count = step.ingredients.size,
                    state = state,
                    stacked = stacked,
                    onAction = onAction,
                )
            }
            QuickAdd(index, onAction)
            MultilineField(
                value = step.instruction,
                onValueChange = { onAction(RecipeEditorAction.SetStepInstruction(index, it)) },
                label = stringResource(R.string.recipe_editor_step_instruction),
                placeholder = null,
            )
        }
    }
}

@Composable
private fun QuickAdd(stepIndex: Int, onAction: (RecipeEditorAction) -> Unit) {
    val spacing = TandemTheme.spacing
    var typed by rememberSaveable(stepIndex) { mutableStateOf("") }
    val add = {
        onAction(RecipeEditorAction.AddIngredient(stepIndex, typed))
        typed = ""
    }
    Row(
        modifier = Modifier.heightIn(min = TOUCH_TARGET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        TandemTextField(
            value = typed,
            onValueChange = { typed = it },
            label = stringResource(R.string.recipe_editor_ingredient_quick),
            leadingIcon = null,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = add, modifier = Modifier.size(TOUCH_TARGET)) {
            Icon(TandemIcons.Add, contentDescription = stringResource(R.string.recipe_editor_ingredient_add_to, stepIndex + 1))
        }
    }
    val addHeader = stringResource(R.string.recipe_editor_ingredient_add_header_to, stepIndex + 1)
    TandemTextButton(
        text = stringResource(R.string.recipe_editor_ingredient_add_header),
        onClick = { onAction(RecipeEditorAction.AddHeader(stepIndex)) },
        leadingIcon = TandemIcons.Add,
        modifier = Modifier.semantics { contentDescription = addHeader },
    )
}

@Composable
private fun IngredientEditor(
    stepIndex: Int,
    position: Int,
    line: DraftIngredient,
    count: Int,
    state: RecipeEditorUiState,
    stacked: Boolean,
    onAction: (RecipeEditorAction) -> Unit,
) {
    val spacing = TandemTheme.spacing
    val stepNumber = stepIndex + 1
    val lineNumber = position + 1
    val separator = LocalConfiguration.current.locales[0].toNumberStyle().decimalSeparator
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        if (line.isHeader) {
            TandemTextField(
                value = line.note,
                onValueChange = { onAction(RecipeEditorAction.SetIngredientNote(stepIndex, position, it)) },
                label = stringResource(R.string.recipe_editor_ingredient_header),
                leadingIcon = null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val amount: @Composable (Modifier) -> Unit = { fieldModifier ->
                OutlinedTextField(
                    value = state.amountText(stepIndex, position, separator),
                    onValueChange = { onAction(RecipeEditorAction.SetIngredientAmount(stepIndex, position, it)) },
                    label = { Text(stringResource(R.string.recipe_editor_ingredient_amount)) },
                    enabled = !line.noAmount,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = fieldModifier,
                )
            }
            val unit: @Composable (Modifier) -> Unit = { fieldModifier ->
                UnitField(
                    value = line.unit.orEmpty(),
                    units = state.units,
                    onChange = { onAction(RecipeEditorAction.SetIngredientUnit(stepIndex, position, it)) },
                    // The field and its menu button name the line, each in its own words (TalkBack: no two alike).
                    fieldDescription = stringResource(R.string.recipe_editor_ingredient_unit_for, lineNumber, stepNumber),
                    pickDescription = stringResource(R.string.recipe_editor_ingredient_unit_pick_for, lineNumber, stepNumber),
                    modifier = fieldModifier,
                )
            }
            val food: @Composable (Modifier) -> Unit = { fieldModifier ->
                FoodField(
                    value = line.food.orEmpty(),
                    suggestions = state.foodSuggestions(line.food.orEmpty()),
                    onChange = { onAction(RecipeEditorAction.SetIngredientFood(stepIndex, position, it)) },
                    suggestionDescription = { stringResource(R.string.recipe_editor_ingredient_food_pick, it, lineNumber, stepNumber) },
                    modifier = fieldModifier,
                )
            }
            if (stacked) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    amount(Modifier.weight(1f))
                    unit(Modifier.weight(1f))
                }
                food(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    amount(Modifier.width(AMOUNT_WIDTH))
                    unit(Modifier.width(UNIT_WIDTH))
                    food(Modifier.weight(1f))
                }
            }
            TandemTextField(
                value = line.note,
                onValueChange = { onAction(RecipeEditorAction.SetIngredientNote(stepIndex, position, it)) },
                label = stringResource(R.string.recipe_editor_ingredient_note),
                leadingIcon = null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(modifier = Modifier.heightIn(min = TOUCH_TARGET), verticalAlignment = Alignment.CenterVertically) {
            if (!line.isHeader) {
                val noAmount = stringResource(R.string.recipe_editor_ingredient_no_amount_for, lineNumber, stepNumber)
                FilterChip(
                    selected = line.noAmount,
                    onClick = { onAction(RecipeEditorAction.ToggleNoAmount(stepIndex, position)) },
                    label = { Text(stringResource(R.string.recipe_editor_ingredient_no_amount)) },
                    leadingIcon = if (line.noAmount) {
                        { Icon(TandemIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = noAmount },
                )
            }
            Row(modifier = Modifier.weight(1f).heightIn(min = TOUCH_TARGET), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = { onAction(RecipeEditorAction.MoveIngredient(stepIndex, position, up = true)) },
                    enabled = position > 0,
                    modifier = Modifier.size(TOUCH_TARGET),
                ) {
                    Icon(
                        TandemIcons.ArrowUpward,
                        contentDescription = stringResource(R.string.recipe_editor_ingredient_move_up, lineNumber, stepNumber),
                    )
                }
                IconButton(
                    onClick = { onAction(RecipeEditorAction.MoveIngredient(stepIndex, position, up = false)) },
                    enabled = position < count - 1,
                    modifier = Modifier.size(TOUCH_TARGET),
                ) {
                    Icon(
                        TandemIcons.ArrowDownward,
                        contentDescription = stringResource(R.string.recipe_editor_ingredient_move_down, lineNumber, stepNumber),
                    )
                }
                IconButton(
                    onClick = { onAction(RecipeEditorAction.RemoveIngredient(stepIndex, position)) },
                    modifier = Modifier.size(TOUCH_TARGET),
                ) {
                    Icon(
                        TandemIcons.Delete,
                        contentDescription = stringResource(R.string.recipe_editor_ingredient_remove, lineNumber, stepNumber),
                    )
                }
            }
        }
    }
}

/** The unit as free text, with the stored units in a menu behind the field's trailing button. */
@Composable
private fun UnitField(
    value: String,
    units: List<String>,
    onChange: (String) -> Unit,
    fieldDescription: String,
    pickDescription: String,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // The value goes first: a description stands in for the field's text.
    val description = listOf(value, fieldDescription).filter { it.isNotBlank() }.joinToString(", ")
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.recipe_editor_ingredient_unit)) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
            trailingIcon = if (units.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { open = true }, modifier = Modifier.size(TOUCH_TARGET)) {
                        Icon(TandemIcons.ExpandMore, contentDescription = pickDescription)
                    }
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            units.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit) },
                    onClick = {
                        open = false
                        onChange(unit)
                    },
                )
            }
        }
    }
}

/**
 * The food as free text, with the stored foods that match under the field (B9): foods go by name,
 * so a slip would make another food in the library. The menu does not take the focus, so typing
 * goes on; a new name is simply kept.
 */
@Composable
private fun FoodField(
    value: String,
    suggestions: List<String>,
    onChange: (String) -> Unit,
    suggestionDescription: @Composable (String) -> String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                dismissed = false
                onChange(it)
            },
            label = { Text(stringResource(R.string.recipe_editor_ingredient_food)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
        DropdownMenu(
            expanded = focused && !dismissed && suggestions.isNotEmpty(),
            onDismissRequest = { dismissed = true },
            properties = PopupProperties(focusable = false),
        ) {
            suggestions.forEach { food ->
                val description = suggestionDescription(food)
                DropdownMenuItem(
                    text = { Text(food) },
                    onClick = {
                        dismissed = true
                        onChange(food)
                    },
                    modifier = Modifier.testTag(foodSuggestionTag(food)).semantics { contentDescription = description },
                )
            }
        }
    }
}

/** A whole number of minutes; blank means 0. */
@Composable
private fun MinutesField(minutes: Int, onChange: (Int) -> Unit, label: String, modifier: Modifier = Modifier) {
    var typed by rememberSaveable(minutes) { mutableStateOf(if (minutes == 0) "" else minutes.toString()) }
    OutlinedTextField(
        value = typed,
        onValueChange = { text ->
            val digits = text.filter { it.isDigit() }.take(MAX_MINUTE_DIGITS)
            typed = digits
            onChange(digits.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

@Composable
private fun MultilineField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        minLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Heading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

/** The form's "Spara", wherever it stands. */
const val EDITOR_SAVE_TAG = "recipe-editor:save"

/** A stored food offered under the food field. */
fun foodSuggestionTag(food: String): String = "recipe-editor:food:$food"

private val FORM_MAX_WIDTH = 640.dp
private val TOUCH_TARGET = 48.dp

/** Below this height the keyboard leaves no room for the save bar (a phone in landscape). */
private val COMPACT_HEIGHT = 480.dp

/** Below this width the ingredient row's fixed fields leave the food no room (a 360 dp phone). */
private val NARROW_WIDTH = 400.dp
private const val LARGE_FONT_SCALE = 1.5f
private val PHOTO_PREVIEW = 160.dp
private val AMOUNT_WIDTH = 88.dp
private val UNIT_WIDTH = 112.dp
private val MINUTES_WIDTH = 120.dp
private const val MAX_SUGGESTIONS = 12
private const val MAX_MINUTE_DIGITS = 5
