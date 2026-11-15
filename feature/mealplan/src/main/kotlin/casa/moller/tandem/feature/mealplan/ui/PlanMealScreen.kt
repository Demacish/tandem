package casa.moller.tandem.feature.mealplan.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Alarm
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.layout.LabelRow
import casa.moller.tandem.core.ui.layout.MediaRow
import casa.moller.tandem.core.ui.layout.scaledMaxLines
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.core.ui.recipe.ServingsStepper
import casa.moller.tandem.feature.mealplan.MealKind
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.PlanMealAction
import casa.moller.tandem.feature.mealplan.PlanMealField
import casa.moller.tandem.feature.mealplan.PlanMealUiState
import casa.moller.tandem.feature.mealplan.R

/** Test tag of the plan-meal form (scrolling in tests). */
const val PLAN_MEAL_FORM_TAG = "plan-meal:form"

/** Test tag of "Lägg till i matsedeln" / "Spara" (enhetstestet B15). */
const val PLAN_MEAL_SAVE_TAG = "plan-meal:save"

/** Test tag of the missing field said beside "Lägg till i matsedeln" (enhetstestet B15). */
const val PLAN_MEAL_SAVE_ERROR_TAG = "plan-meal:save-error"

/** Test tag of the "Påminn kvällen innan" field (Fas 5). */
const val PLAN_MEAL_REMINDER_TAG = "plan-meal:reminder"

/** Test tag of a recipe in the search results: `plan-meal:recipe:<id>`. */
fun planMealRecipeTag(id: Long): String = "plan-meal:recipe:$id"

/** Test tag of a recipe in "Vill laga" (`want`) or "Favoriter" (`favorite`): `plan-meal:<group>:<id>` (fix-ui). */
fun planMealCollectionTag(group: String, id: Long): String = "plan-meal:$group:$id"

/**
 * "Planera måltid" (Stitch planera_m_ltid_ark, plan 6.2), stateless: the day (two weeks of chips or
 * any date), the meal type (or a new one), a recipe from the stored recipes or a title only, the
 * servings, a note, "Påminn kvällen innan" (Fas 5: a "Påminn:" line of the note, plan §8.3) and
 * "Lägg på listan"; "Lägg till i matsedeln (lördag)" at the bottom. Editing a
 * meal has "Spara" and "Ta bort från matsedeln" (asks first, fix-ui). A full page on every width
 * (the content stays at a readable width on tablets; on phones without the tab bar), so the
 * keyboard and TalkBack work as on any page. While typing, the form ends at the keyboard's top and
 * the save bar waits under the keyboard (fix-ui: the keyboard was subtracted twice before).
 * With an empty search, "Vill laga" and "Favoriter" are offered above the stored recipes (fix-ui,
 * plan 6.2 "välj recept (sök, Vill laga, Favoriter)").
 */
@Composable
fun PlanMealScreen(
    state: PlanMealUiState,
    onAction: (PlanMealAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    ReportContentReady(ready = !state.loading)
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { MealPlanFormat(locale) }
    var picking by rememberSaveable { mutableStateOf(false) }
    // Enhetstestet B15: a save that stopped at a missing field brings that field into view (and
    // focuses it when it is a text field); the bar says which one beside the button.
    val listState = rememberLazyListState()
    val focus = remember { PlanMealFocus() }
    LaunchedEffect(state.failedSaves) {
        val field = state.firstMissing ?: return@LaunchedEffect
        if (state.failedSaves == 0) return@LaunchedEffect
        listState.animateScrollToItem(planMealFieldIndex(state, field))
        focus.of(field)?.let { requester -> runCatching { requester.requestFocus() } }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TandemTopAppBar(
                title = stringResource(if (state.editing) R.string.mealplan_edit_meal_title else R.string.mealplan_plan_meal_title),
                onNavigateBack = { onAction(PlanMealAction.Close) },
            )
        },
        bottomBar = { if (!state.loading && !state.gone && !state.readOnly) SaveBar(state = state, format = format, onAction = onAction) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))

            else -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                PlanMealForm(
                    state = state,
                    format = format,
                    onAction = onAction,
                    onPickDate = { picking = true },
                    listState = listState,
                    focus = focus,
                    // fix-ui: the keyboard is handled here only. The Scaffold's padding already holds the
                    // save bar (which stays under the keyboard); consuming it leaves imePadding just
                    // the part of the keyboard above the bar, so the form ends at the keyboard's top.
                    modifier = Modifier
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                        .fillMaxSize()
                        .consumeWindowInsets(padding)
                        .imePadding(),
                )
            }
        }
    }
    if (picking) DatePickerFor(initial = state.date, onPick = { onAction(PlanMealAction.SetDate(it)) }, onDismiss = { picking = false })
    if (state.confirmDelete) {
        DeleteMealDialog(
            mealTitle = state.title.ifBlank { state.recipe?.name.orEmpty() },
            rowsOnList = 0.takeIf { state.onShoppingList },
            onConfirm = { onAction(PlanMealAction.ConfirmDelete(true)) },
            onDismiss = { onAction(PlanMealAction.ConfirmDelete(false)) },
        )
    }
}

@Composable
private fun PlanMealForm(
    state: PlanMealUiState,
    format: MealPlanFormat,
    onAction: (PlanMealAction) -> Unit,
    onPickDate: () -> Unit,
    listState: LazyListState,
    focus: PlanMealFocus,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val errors = state.showErrors
    // Keep the items in step with planMealFieldIndex (enhetstestet B15).
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(PLAN_MEAL_FORM_TAG),
        contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        if (state.gone) {
            item(key = "gone") { Notice(stringResource(R.string.mealplan_meal_gone)) }
            return@LazyColumn
        }
        if (state.readOnly) item(key = "read-only") { Notice(stringResource(R.string.mealplan_read_only)) }
        item(key = "day") {
            DayChooser(
                selected = state.date,
                around = maxOf(state.date, state.today),
                format = format,
                onSelect = { onAction(PlanMealAction.SetDate(it)) },
                onPickOther = onPickDate,
            )
        }
        item(key = "meal-type") { MealTypeChooser(state = state, onAction = onAction) }
        if (state.kinds.size > 1) item(key = "kind") { KindChooser(state = state, onAction = onAction) }
        if (state.kind == MealKind.RECIPE) {
            val recipe = state.recipe
            if (recipe != null) {
                item(key = "chosen") { ChosenRecipe(recipe = recipe, onChange = { onAction(PlanMealAction.ClearRecipe) }) }
            } else {
                item(key = "search") {
                    TandemTextField(
                        value = state.query,
                        onValueChange = { onAction(PlanMealAction.Query(it)) },
                        label = stringResource(R.string.mealplan_search_recipes),
                        leadingIcon = TandemIcons.Search,
                        imeAction = ImeAction.Search,
                        isError = errors && state.recipeMissing,
                        supportingText = stringResource(R.string.mealplan_recipe_missing).takeIf { errors && state.recipeMissing },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus.recipe),
                    )
                }
                if (state.showCollections) {
                    // fix-ui (plan 6.2): the household's "Vill laga" queue and favourites first.
                    collection(R.string.mealplan_from_want_to_cook, "want", state.wantToCook, onAction)
                    collection(R.string.mealplan_from_favorites, "favorite", state.favorites, onAction)
                    if (state.results.isNotEmpty()) {
                        item(key = "all-heading") { CollectionHeading(stringResource(R.string.mealplan_all_recipes)) }
                    }
                }
                if (state.results.isEmpty()) {
                    item(key = "no-results") {
                        Text(
                            text = stringResource(R.string.mealplan_no_recipes_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.results, key = { "recipe-${it.id}" }) { found ->
                    RecipeResult(recipe = found, onPick = { onAction(PlanMealAction.PickRecipe(found)) })
                }
            }
        }
        item(key = "title") {
            val missing = errors && state.titleMissing
            TandemTextField(
                value = state.title,
                onValueChange = { onAction(PlanMealAction.Title(it)) },
                label = stringResource(
                    if (state.kind == MealKind.TITLE) R.string.mealplan_title_label else R.string.mealplan_title_optional,
                ),
                placeholder = stringResource(R.string.mealplan_title_hint),
                isError = missing,
                supportingText = stringResource(R.string.mealplan_title_missing).takeIf { missing },
                modifier = Modifier.fillMaxWidth().focusRequester(focus.title),
            )
        }
        item(key = "servings") {
            val resources = LocalResources.current
            ServingsStepper(
                label = stringResource(R.string.mealplan_servings_label),
                valueText = format.servings(state.servings, resources),
                amountText = format.number(state.servings),
                onDecrease = { onAction(PlanMealAction.FewerServings) },
                onIncrease = { onAction(PlanMealAction.MoreServings) },
                decreaseEnabled = state.servings > 1.0,
            )
        }
        item(key = "note") {
            TandemTextField(
                value = state.note,
                onValueChange = { onAction(PlanMealAction.Note(it)) },
                label = stringResource(R.string.mealplan_note_label),
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Fas 5 (planera lane), plan §8.3: "Påminn kvällen innan", written into the note as "Påminn: …".
        item(key = "reminder") {
            val keyword = stringResource(R.string.mealplan_reminder_keyword)
            TandemTextField(
                value = state.reminder,
                onValueChange = { onAction(PlanMealAction.Reminder(it, keyword)) },
                label = stringResource(R.string.mealplan_reminder_label),
                placeholder = stringResource(R.string.mealplan_reminder_hint),
                supportingText = stringResource(R.string.mealplan_reminder_supporting),
                leadingIcon = TandemIcons.Alarm,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth().testTag(PLAN_MEAL_REMINDER_TAG),
            )
        }
        if (state.showAddToShopping) item(key = "add-to-shopping") { AddToShopping(checked = state.addToShopping, onAction = onAction) }
        if (state.editing && !state.readOnly) {
            item(key = "delete") {
                TandemTextButton(text = stringResource(R.string.mealplan_delete_meal), onClick = { onAction(PlanMealAction.Delete) })
            }
        }
    }
}

/** A "Vill laga" or "Favoriter" group of recipes to pick from (nothing when [recipes] is empty). */
private fun LazyListScope.collection(
    @StringRes heading: Int,
    key: String,
    recipes: List<RecipeOverview>,
    onAction: (PlanMealAction) -> Unit,
) {
    if (recipes.isEmpty()) return
    item(key = "$key-heading") { CollectionHeading(stringResource(heading)) }
    items(recipes, key = { "$key-${it.id}" }) { recipe ->
        RecipeResult(
            recipe = recipe,
            onPick = { onAction(PlanMealAction.PickRecipe(recipe)) },
            tag = planMealCollectionTag(key, recipe.id),
            group = stringResource(heading),
        )
    }
}

@Composable
private fun CollectionHeading(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun MealTypeChooser(state: PlanMealUiState, onAction: (PlanMealAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.mealplan_meal_type_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            state.mealTypes.forEach { type ->
                FilterChip(
                    selected = type.id == state.mealTypeId,
                    onClick = { onAction(PlanMealAction.SetMealType(type.id)) },
                    label = { Text(type.name) },
                )
            }
            if (state.newMealType == null && !state.readOnly) {
                AssistChip(
                    onClick = { onAction(PlanMealAction.OpenNewMealType) },
                    label = { Text(stringResource(R.string.mealplan_new_meal_type)) },
                    leadingIcon = { Icon(TandemIcons.Add, contentDescription = null) },
                )
            }
        }
        if (state.showErrors && state.typeMissing) {
            Text(
                text = stringResource(R.string.mealplan_type_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val newName = state.newMealType
        if (newName != null) {
            TandemTextField(
                value = newName,
                onValueChange = { onAction(PlanMealAction.NewMealTypeName(it)) },
                label = stringResource(R.string.mealplan_new_meal_type_name),
                placeholder = stringResource(R.string.mealplan_new_meal_type_hint),
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End)) {
                TandemTextButton(text = stringResource(R.string.mealplan_cancel), onClick = { onAction(PlanMealAction.CancelNewMealType) })
                TandemTextButton(
                    text = stringResource(R.string.mealplan_add),
                    onClick = { onAction(PlanMealAction.CreateMealType) },
                    enabled = newName.isNotBlank(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindChooser(state: PlanMealUiState, onAction: (PlanMealAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        Text(
            text = stringResource(R.string.mealplan_kind_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            state.kinds.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = state.kind == kind,
                    onClick = { onAction(PlanMealAction.SetKind(kind)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = state.kinds.size),
                    label = {
                        Text(stringResource(if (kind == MealKind.RECIPE) R.string.mealplan_kind_recipe else R.string.mealplan_kind_title))
                    },
                )
            }
        }
    }
}

@Composable
private fun ChosenRecipe(recipe: RecipeOverview, onChange: () -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // B22 sweep: at the largest text the name gets the whole width under the photo. S2 (the
        // owner's screenshot, "…köttfärsgry…"): the name has priority over "Byt recept", which moves
        // under it unless the name fits on one line beside it (docs/design/README.md "Textstorlek
        // och radbrytning").
        MediaRow(
            modifier = Modifier.padding(spacing.md),
            gap = spacing.md,
            leading = {
                RecipeImage(
                    imageUrl = recipe.imageUrl,
                    placeholderIconSize = 24.dp,
                    modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium),
                )
            },
            content = {
                LabelRow(
                    gap = spacing.sm,
                    stackedGap = 0.dp,
                    stackedAlignment = Alignment.End,
                    minLabelShare = 1f,
                    label = {
                        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            Text(text = stringResource(R.string.mealplan_chosen_recipe), style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = recipe.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = scaledMaxLines(2),
                                overflow = TextOverflow.Ellipsis,
                            )
                            durationText(recipe.totalMinutes)?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    },
                    trailing = { TandemTextButton(text = stringResource(R.string.mealplan_change_recipe), onClick = onChange) },
                )
            },
            trailing = {},
        )
    }
}

@Composable
private fun RecipeResult(recipe: RecipeOverview, onPick: () -> Unit, tag: String = planMealRecipeTag(recipe.id), group: String? = null) {
    val spacing = TandemTheme.spacing
    val duration = durationText(recipe.totalMinutes)
    // A recipe can be in "Vill laga", "Favoriter" and the search at once: the group tells them apart for TalkBack.
    val description = group?.let { listOfNotNull(recipe.name, duration, it).joinToString(", ") }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.mealplan_choose_recipe), role = Role.Button, onClick = onPick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget).padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            RecipeImage(
                imageUrl = recipe.imageUrl,
                placeholderIconSize = 20.dp,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = recipe.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                duration?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AddToShopping(checked: Boolean, onAction: (PlanMealAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onAction(PlanMealAction.AddToShopping(it)) })
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.mealplan_add_to_shopping), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.mealplan_add_to_shopping_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(TandemTheme.spacing.md))
    }
}

@Composable
private fun SaveBar(state: PlanMealUiState, format: MealPlanFormat, onAction: (PlanMealAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // Enhetstestet B15: what stopped the save, beside the button (the field itself may be a
            // screen height up); TalkBack says it when it appears.
            state.firstMissing?.let { field ->
                Text(
                    text = stringResource(field.message()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                        .fillMaxWidth()
                        .testTag(PLAN_MEAL_SAVE_ERROR_TAG)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            TandemPrimaryButton(
                text = if (state.editing) {
                    stringResource(R.string.mealplan_save)
                } else {
                    stringResource(R.string.mealplan_save_new, format.weekdayInText(state.date))
                },
                onClick = { onAction(PlanMealAction.Save) },
                enabled = state.canSave,
                modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth).fillMaxWidth().testTag(PLAN_MEAL_SAVE_TAG),
            )
        }
    }
}

/** Enhetstestet B15: the text fields a failed save focuses. */
internal class PlanMealFocus {
    val recipe = FocusRequester()
    val title = FocusRequester()

    fun of(field: PlanMealField): FocusRequester? = when (field) {
        PlanMealField.MEAL_TYPE -> null
        PlanMealField.RECIPE -> recipe
        PlanMealField.TITLE -> title
    }
}

@StringRes
private fun PlanMealField.message(): Int = when (this) {
    PlanMealField.MEAL_TYPE -> R.string.mealplan_type_missing
    PlanMealField.RECIPE -> R.string.mealplan_recipe_missing
    PlanMealField.TITLE -> R.string.mealplan_title_missing
}

/**
 * Enhetstestet B15: the index of [field]'s item in [PlanMealForm]'s list (its items in order: the
 * read-only notice, the day, the meal type, the kind, then the recipe search or the chosen recipe,
 * "Vill laga", "Favoriter" and the results, then the title).
 */
internal fun planMealFieldIndex(state: PlanMealUiState, field: PlanMealField): Int {
    var index = if (state.readOnly) 1 else 0
    index += 1 // The day.
    if (field == PlanMealField.MEAL_TYPE) return index
    index += 1 // The meal type.
    if (state.kinds.size > 1) index += 1
    if (field == PlanMealField.RECIPE || state.kind != MealKind.RECIPE) return index
    if (state.recipe != null) return index + 1
    index += 1 // The search.
    if (state.showCollections) {
        if (state.wantToCook.isNotEmpty()) index += 1 + state.wantToCook.size
        if (state.favorites.isNotEmpty()) index += 1 + state.favorites.size
        if (state.results.isNotEmpty()) index += 1
    }
    return index + if (state.results.isEmpty()) 1 else state.results.size
}
