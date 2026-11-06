package casa.moller.tandem.feature.recipes.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.model.recipe.DraftStep
import casa.moller.tandem.core.model.recipe.RecipeProperty
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import casa.moller.tandem.feature.recipes.R

/**
 * Fas 4 (#22, decision B9): what the web's recipe editor has beyond the plain form. Under each step
 * its two display flags, its sub-recipe and its file; last in the form the recipe's own nutrition
 * values. Everything repeats per step, so every control names its step for TalkBack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StepOptions(index: Int, step: DraftStep, onAction: (RecipeEditorAction) -> Unit, onPickFile: (Int) -> Unit) {
    val number = index + 1
    EditorSwitch(
        title = stringResource(R.string.recipe_editor_step_show_as_header),
        checked = step.showAsHeader,
        onChange = { onAction(RecipeEditorAction.SetStepShowAsHeader(index, it)) },
        description = stringResource(R.string.recipe_editor_step_show_as_header_for, number),
        modifier = Modifier.testTag(stepHeaderTag(index)),
    )
    EditorSwitch(
        title = stringResource(R.string.recipe_editor_step_show_ingredients),
        checked = step.showIngredientsTable,
        onChange = { onAction(RecipeEditorAction.SetStepShowIngredients(index, it)) },
        description = stringResource(R.string.recipe_editor_step_show_ingredients_for, number),
        modifier = Modifier.testTag(stepIngredientsTag(index)),
    )
    step.stepRecipe?.let { link ->
        AttachedRow(
            text = link.name?.let { stringResource(R.string.recipe_editor_step_linked, it) }
                ?: stringResource(R.string.recipe_editor_step_linked_unnamed),
            removeDescription = stringResource(R.string.recipe_editor_step_unlink_for, number),
            onRemove = { onAction(RecipeEditorAction.SetStepRecipe(index, null)) },
        )
    }
    step.file?.let { file ->
        AttachedRow(
            text = stringResource(R.string.recipe_editor_step_file, file.name),
            removeDescription = stringResource(R.string.recipe_editor_step_file_remove_for, number),
            onRemove = { onAction(RecipeEditorAction.SetStepFile(index, null)) },
        )
    }
    if (step.stepRecipe == null || step.file == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
            if (step.stepRecipe == null) {
                val link = stringResource(R.string.recipe_editor_step_link_for, number)
                TandemTextButton(
                    text = stringResource(R.string.recipe_editor_step_link),
                    onClick = { onAction(RecipeEditorAction.OpenStepLink(index)) },
                    leadingIcon = TandemIcons.Add,
                    modifier = Modifier.testTag(stepLinkTag(index)).semantics { contentDescription = link },
                )
            }
            if (step.file == null) {
                val attach = stringResource(R.string.recipe_editor_step_attach_for, number)
                TandemTextButton(
                    text = stringResource(R.string.recipe_editor_step_attach),
                    onClick = { onPickFile(index) },
                    leadingIcon = TandemIcons.Add,
                    modifier = Modifier.semantics { contentDescription = attach },
                )
            }
        }
    }
}

/** A sub-recipe or file on a step: what it is, and the way to take it off. */
@Composable
private fun AttachedRow(text: String, removeDescription: String, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH_TARGET), verticalAlignment = Alignment.CenterVertically) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(TOUCH_TARGET)) {
            Icon(TandemIcons.Delete, contentDescription = removeDescription)
        }
    }
}

/**
 * A setting of the form that is on or off: the whole row toggles, one TalkBack item with the switch
 * state; [description] replaces the title when the row repeats (it names its step).
 */
@Composable
internal fun EditorSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    description: String? = null,
) {
    val named = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH_TARGET)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .then(named),
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (body != null) {
                Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The recipe's own nutrition values per serving, one field per property type. Only with something
 * to show: a server without property types keeps the form as it was.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NutritionSection(state: RecipeEditorUiState, onAction: (RecipeEditorAction) -> Unit) {
    val present = state.draft.properties.orEmpty()
    if (present.isEmpty() && state.propertyTypes.isEmpty()) return
    val spacing = TandemTheme.spacing
    Heading(stringResource(R.string.recipe_editor_nutrition))
    Text(
        text = stringResource(R.string.recipe_editor_nutrition_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val separator = LocalConfiguration.current.locales[0].toNumberStyle().decimalSeparator
    present.forEach { property ->
        PropertyRow(property, text = state.propertyText(property.typeId, separator), onAction = onAction)
    }
    if (state.propertyTypes.isNotEmpty()) {
        Text(
            text = stringResource(R.string.recipe_editor_nutrition_add),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            state.propertyTypes.forEach { type ->
                SuggestionChip(
                    onClick = { onAction(RecipeEditorAction.AddProperty(type)) },
                    label = { Text(type.name) },
                    modifier = Modifier.heightIn(min = TOUCH_TARGET).testTag(propertyTypeTag(type.id)),
                )
            }
        }
    }
}

@Composable
private fun PropertyRow(property: RecipeProperty, text: String, onAction: (RecipeEditorAction) -> Unit) {
    val label = if (property.unit.isBlank()) {
        property.typeName
    } else {
        stringResource(R.string.recipe_editor_nutrition_label, property.typeName, property.unit)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { onAction(RecipeEditorAction.SetPropertyAmount(property.typeId, it)) },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.weight(1f).testTag(propertyFieldTag(property.typeId)),
        )
        IconButton(onClick = { onAction(RecipeEditorAction.RemoveProperty(property.typeId)) }, modifier = Modifier.size(TOUCH_TARGET)) {
            Icon(TandemIcons.Delete, contentDescription = stringResource(R.string.recipe_editor_nutrition_remove, property.typeName))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepLinkSheet(picker: StepLinkPicker, onAction: (RecipeEditorAction) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onAction(RecipeEditorAction.CloseStepLink) },
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        StepLinkContent(picker, onAction, modifier = Modifier.imePadding())
    }
}

/** The sheet itself: what it does, the search, and the stored recipes (one TalkBack item each). */
@Composable
internal fun StepLinkContent(picker: StepLinkPicker, onAction: (RecipeEditorAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    // Kept here, not read back from the ViewModel: typing never waits for the search's results.
    var text by rememberSaveable { mutableStateOf(picker.query) }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(EDITOR_LINK_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(start = spacing.screenHorizontal, end = spacing.screenHorizontal, bottom = spacing.lg),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.recipe_editor_link_title, picker.step + 1),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        item(key = "hint") {
            Text(
                text = stringResource(R.string.recipe_editor_link_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "search") {
            TandemTextField(
                value = text,
                onValueChange = {
                    text = it
                    onAction(RecipeEditorAction.SearchStepLink(it))
                },
                label = stringResource(R.string.recipe_editor_link_search),
                leadingIcon = TandemIcons.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (picker.results.isEmpty()) {
            item(key = "empty") {
                Text(text = stringResource(R.string.recipe_editor_link_empty), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(picker.results, key = { it.id }) { recipe ->
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TOUCH_TARGET)
                    .clickable(role = Role.Button) {
                        onAction(RecipeEditorAction.SetStepRecipe(picker.step, StepRecipeRef(recipe.id, recipe.name)))
                    }
                    .padding(vertical = spacing.sm)
                    .testTag(linkRecipeTag(recipe.id)),
            )
        }
    }
}

/** The sheet that chooses a sub-recipe. */
const val EDITOR_LINK_SHEET_TAG = "recipe-editor:link-sheet"

fun stepHeaderTag(step: Int): String = "recipe-editor:step:$step:header"

fun stepIngredientsTag(step: Int): String = "recipe-editor:step:$step:ingredients"

fun stepLinkTag(step: Int): String = "recipe-editor:step:$step:link"

fun linkRecipeTag(recipeId: Long): String = "recipe-editor:link:$recipeId"

fun propertyTypeTag(typeId: Long): String = "recipe-editor:property-type:$typeId"

fun propertyFieldTag(typeId: Long): String = "recipe-editor:property:$typeId"

private val TOUCH_TARGET = 48.dp
