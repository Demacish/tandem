package casa.moller.tandem.feature.recipes.editor

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import casa.moller.tandem.core.model.recipe.DraftIngredient
import casa.moller.tandem.core.model.recipe.DraftKeyword
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.feature.recipes.R

/**
 * The recipe editor (Fas 3, redigerare lane; #22, plan §8.9; Stitch sketch
 * `redigera_recept_kr_mig_svamppasta`), full screen: the basics (name, photo, description,
 * servings, times, keywords) and the steps, each with its ingredients, in one scrolling form with
 * the save button fixed below it (above the keyboard and the navigation bar, as "Ny dryck"). Every
 * control is a plain text field or a 48 dp button, so it works with a large font and TalkBack
 * (every icon button names its step and line). Width-limited on tablets.
 *
 * @param onTakePhoto / [onPickPhoto] start the camera / the photo picker (the route owns the launchers).
 */
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
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(if (state.isNew) R.string.recipe_editor_title_new else R.string.recipe_editor_title_edit),
                onNavigateBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
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
                        modifier = Modifier.widthIn(max = FORM_MAX_WIDTH).verticalScroll(rememberScrollState()),
                    )
                }
            }
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                TandemPrimaryButton(
                    text = stringResource(R.string.recipe_editor_save),
                    onClick = { onAction(RecipeEditorAction.Save) },
                    enabled = state.loaded && !state.saving && !state.readOnly && !state.unknownRecipe,
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
    state: RecipeEditorUiState,
    onAction: (RecipeEditorAction) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier,
) {
    val spacing = TandemTheme.spacing
    val draft = state.draft
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
                        onClick = { onAction(RecipeEditorAction.DiscardKeptEdit) },
                    )
                },
            )
        }
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
            StepEditor(index = index, step = step, count = draft.steps.size, state = state, onAction = onAction)
        }
        TandemSecondaryButton(
            text = stringResource(R.string.recipe_editor_add_step),
            onClick = { onAction(RecipeEditorAction.AddStep) },
            leadingIcon = TandemIcons.Add,
            modifier = Modifier.fillMaxWidth(),
        )
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
                modifier = Modifier.padding(horizontal = spacing.sm),
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

@Composable
private fun StepEditor(index: Int, step: DraftStep, count: Int, state: RecipeEditorUiState, onAction: (RecipeEditorAction) -> Unit) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TandemTextField(
                    value = step.name,
                    onValueChange = { onAction(RecipeEditorAction.SetStepName(index, it)) },
                    label = stringResource(R.string.recipe_editor_step_name),
                    leadingIcon = null,
                    modifier = Modifier.weight(1f),
                )
                MinutesField(
                    minutes = step.timeMinutes,
                    onChange = { onAction(RecipeEditorAction.SetStepMinutes(index, it)) },
                    label = stringResource(R.string.recipe_editor_step_minutes),
                    modifier = Modifier.width(MINUTES_WIDTH),
                )
            }
            Text(text = stringResource(R.string.recipe_editor_ingredients), style = MaterialTheme.typography.titleSmall)
            step.ingredients.forEachIndexed { position, line ->
                IngredientEditor(
                    stepIndex = index,
                    position = position,
                    line = line,
                    count = step.ingredients.size,
                    state = state,
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
            Icon(TandemIcons.Add, contentDescription = stringResource(R.string.recipe_editor_ingredient_add))
        }
    }
    TandemTextButton(
        text = stringResource(R.string.recipe_editor_ingredient_add_header),
        onClick = { onAction(RecipeEditorAction.AddHeader(stepIndex)) },
        leadingIcon = TandemIcons.Add,
    )
}

@Composable
private fun IngredientEditor(
    stepIndex: Int,
    position: Int,
    line: DraftIngredient,
    count: Int,
    state: RecipeEditorUiState,
    onAction: (RecipeEditorAction) -> Unit,
) {
    val spacing = TandemTheme.spacing
    val stepNumber = stepIndex + 1
    val lineNumber = position + 1
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
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                OutlinedTextField(
                    value = state.amountText(stepIndex, position),
                    onValueChange = { onAction(RecipeEditorAction.SetIngredientAmount(stepIndex, position, it)) },
                    label = { Text(stringResource(R.string.recipe_editor_ingredient_amount)) },
                    enabled = !line.noAmount,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.width(AMOUNT_WIDTH),
                )
                UnitField(
                    value = line.unit.orEmpty(),
                    units = state.units,
                    onChange = { onAction(RecipeEditorAction.SetIngredientUnit(stepIndex, position, it)) },
                    // Every unit field and its menu button name their line (TalkBack: no two alike).
                    pickDescription = stringResource(R.string.recipe_editor_ingredient_unit_pick_for, lineNumber, stepNumber),
                    modifier = Modifier.width(UNIT_WIDTH),
                )
                OutlinedTextField(
                    value = line.food.orEmpty(),
                    onValueChange = { onAction(RecipeEditorAction.SetIngredientFood(stepIndex, position, it)) },
                    label = { Text(stringResource(R.string.recipe_editor_ingredient_food)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
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
                FilterChip(
                    selected = line.noAmount,
                    onClick = { onAction(RecipeEditorAction.ToggleNoAmount(stepIndex, position)) },
                    label = { Text(stringResource(R.string.recipe_editor_ingredient_no_amount)) },
                    leadingIcon = if (line.noAmount) {
                        { Icon(TandemIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
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
    pickDescription: String,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.recipe_editor_ingredient_unit)) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = pickDescription },
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

private val FORM_MAX_WIDTH = 640.dp
private val TOUCH_TARGET = 48.dp
private val PHOTO_PREVIEW = 160.dp
private val AMOUNT_WIDTH = 88.dp
private val UNIT_WIDTH = 112.dp
private val MINUTES_WIDTH = 120.dp
private const val MAX_SUGGESTIONS = 12
private const val MAX_MINUTE_DIGITS = 5
