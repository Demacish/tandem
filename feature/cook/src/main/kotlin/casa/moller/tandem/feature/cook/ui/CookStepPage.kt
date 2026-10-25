package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.FormatListBulleted
import casa.moller.tandem.core.designsystem.icon.Remove
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.spokenNumbers
import casa.moller.tandem.core.domain.recipe.IngredientRow as DisplayRow
import casa.moller.tandem.core.domain.recipe.UnitAmount
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerSuggestionSource
import casa.moller.tandem.core.ui.recipe.IconLabel
import casa.moller.tandem.core.ui.recipe.IngredientHeader
import casa.moller.tandem.core.ui.recipe.IngredientRow
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.core.ui.timer.KitchenIconButton
import casa.moller.tandem.core.ui.timer.TimerActions
import casa.moller.tandem.core.ui.timer.TimerBarItem
import casa.moller.tandem.core.ui.timer.TimerCard
import casa.moller.tandem.core.ui.timer.TimerStartButton
import casa.moller.tandem.core.ui.timer.sortedForDisplay
import casa.moller.tandem.core.ui.timer.timerDurationText
import casa.moller.tandem.core.ui.timer.timerStartDescription
import casa.moller.tandem.feature.cook.CookAction
import casa.moller.tandem.feature.cook.CookServings
import casa.moller.tandem.feature.cook.CookStep
import casa.moller.tandem.feature.cook.CookUiState
import casa.moller.tandem.feature.cook.R
import kotlin.time.Instant

/**
 * One step (plan 6.2 "Pager med ett steg per sida", Stitch kokl_ge_kr_mig_svamppasta_steg_3):
 * the step's name, its text large (A−/A+), its timers, and on phones its ingredients in an
 * expandable section with the servings.
 *
 * @param showIngredients false where the side pane shows them (two panes).
 * @param largeText a wide window: the step text starts larger.
 */
@Composable
internal fun CookStepPage(
    state: CookUiState,
    step: CookStep,
    onAction: (CookAction) -> Unit,
    showIngredients: Boolean,
    largeText: Boolean,
) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        StepTitle(step)
        if (step.instruction.isNotBlank()) {
            StepInstruction(
                markdown = step.instruction,
                suggestions = step.suggestions,
                style = cookTextStyle(state.textScale, largeText),
                onTimerTap = { index -> onAction(CookAction.StartTimer(step.index, step.suggestions[index])) },
                modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth),
            )
        } else if (state.steps.size == 1 && step.ingredients.isNotEmpty()) {
            Text(text = stringResource(R.string.cook_no_steps), style = MaterialTheme.typography.bodyLarge)
        }
        step.imageUrl?.let { url ->
            // Decorative: the step's text says what to do; the photo only shows how it looks.
            RecipeImage(
                imageUrl = url,
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                    .fillMaxWidth()
                    .aspectRatio(STEP_IMAGE_ASPECT_RATIO)
                    .clip(MaterialTheme.shapes.large)
                    .testTag(COOK_STEP_IMAGE_TAG),
            )
        }
        if (step.hasBrokenTemplate) {
            IconLabel(
                icon = TandemIcons.Info,
                text = stringResource(R.string.cook_step_template_broken),
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        step.stepRecipeName?.let { name ->
            IconLabel(
                icon = TandemIcons.MenuBook,
                text = if (name.isNotBlank()) {
                    stringResource(R.string.cook_step_recipe, name)
                } else {
                    stringResource(R.string.cook_step_recipe_unnamed)
                },
            )
        }
        TimerButtons(step = step, onAction = onAction)
        if (showIngredients && step.ingredients.isNotEmpty()) {
            ExpandableIngredients(state = state, step = step, onAction = onAction)
        }
    }
}

/** "STEG 3" and the step's own name ("Stekning i panna"), a TalkBack heading. */
@Composable
private fun StepTitle(step: CookStep) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { heading() },
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.cook_step_heading, step.number).uppercase(),
            style = TandemTheme.extendedTypography.overline,
            color = MaterialTheme.colorScheme.primary,
        )
        if (step.name.isNotBlank()) {
            Text(text = step.name, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/**
 * The step text in cook mode: `cookStep` (28 sp, 34 sp on wide windows) × A−/A+, the same style
 * Settings → Appearance previews.
 */
@Composable
private fun cookTextStyle(scale: Float, largeText: Boolean): TextStyle =
    TandemTheme.extendedTypography.cookStepScaled(scale, wide = largeText)

/** The step's timers as big start buttons (Stitch "Starta 5 min"). */
@Composable
private fun TimerButtons(step: CookStep, onAction: (CookAction) -> Unit) {
    if (step.suggestions.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
    ) {
        step.suggestions.forEach { suggestion ->
            val durationText = timerDurationText(suggestion.duration)
            // The words of the text when they say more than the duration ("ca 20–25 minuter").
            val detail = suggestion.label.takeIf { suggestion.source == TimerSuggestionSource.TEXT && it != durationText }
            TimerStartButton(
                durationText = durationText,
                detail = detail,
                description = timerStartDescription(durationText, detail),
                onClick = { onAction(CookAction.StartTimer(step.index, suggestion)) },
            )
        }
    }
}

/**
 * Phone: the step's ingredients behind a 64 dp header that opens and closes them (plan 6.5
 * "ingredienser i utdragbart ark"), with the servings at the top. Open by default; the choice is
 * kept for the whole cook session.
 */
@Composable
private fun ExpandableIngredients(state: CookUiState, step: CookStep, onAction: (CookAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val expanded = state.ingredientsExpanded
    val count = step.ingredients.count { it is DisplayRow.Item }
    val stateText = stringResource(if (expanded) R.string.cook_expanded else R.string.cook_collapsed)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                    .clickable(role = Role.Button) { onAction(CookAction.ToggleIngredients) }
                    .semantics(mergeDescendants = true) {
                        heading()
                        stateDescription = stateText
                    }
                    .padding(horizontal = spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Icon(TandemIcons.FormatListBulleted, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(vertical = spacing.sm)) {
                    Text(
                        text = stringResource(
                            if (state.steps.size == 1) R.string.cook_all_ingredients else R.string.cook_ingredients_for_step,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.cook_ingredient_count, count, count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = TandemIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
            if (expanded) {
                state.servings?.let { servings ->
                    CookServingsControl(servings = servings, onAction = onAction, modifier = Modifier.padding(horizontal = spacing.lg))
                }
                CookIngredientRows(
                    rows = step.ingredients,
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.padding(bottom = spacing.sm),
                )
            }
        }
    }
}

/** The step's ingredient rows (64 dp, tap strikes through, tap on the amount switches the unit). */
@Composable
internal fun CookIngredientRows(rows: List<DisplayRow>, state: CookUiState, onAction: (CookAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        rows.forEach { row ->
            when (row) {
                is DisplayRow.Header -> IngredientHeader(row.title)

                is DisplayRow.Item -> {
                    val amount = row.unitAt(state.unitChoices[row.id] ?: 0)?.text()
                    IngredientRow(
                        amountText = amount,
                        amountDescription = amount?.let(::spokenNumbers),
                        name = row.food,
                        note = row.note.ifEmpty { null },
                        struck = row.id in state.struckIngredientIds,
                        onToggle = { onAction(CookAction.ToggleIngredient(row.id)) },
                        onAmountClick = if (row.canCycleUnits) ({ onAction(CookAction.SwitchUnit(row.id)) }) else null,
                        // The unit pill is as tall as the 64 dp row (plan 6.6: a tap just above
                        // or below it switches the unit, it does not tick the row off).
                        amountMinHeight = TandemTheme.sizes.kitchenTouchTarget,
                        modifier = Modifier.heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
                    )
                }
            }
        }
    }
}

/** "2,5 dl", "3" (no unit): the amount and unit the web shows, in one text. */
private fun UnitAmount.text(): String = if (unit.isEmpty()) amount else "$amount $unit"

/**
 * Servings in cook mode (same as the detail screen, changeable here): the value and − / + with
 * 64 dp targets. The value is a live region, so TalkBack says the new servings.
 */
@Composable
internal fun CookServingsControl(servings: CookServings, onAction: (CookAction) -> Unit, modifier: Modifier = Modifier) {
    val valueText = if (servings.text.isNotBlank()) {
        stringResource(R.string.cook_servings_with_text, servings.amountText, servings.text)
    } else {
        pluralStringResource(R.plurals.cook_servings, if (servings.current == 1.0) 1 else PLURAL_OTHER, servings.amountText)
    }
    StepperRow(
        label = stringResource(R.string.cook_servings_label),
        valueText = valueText,
        onDecrease = { onAction(CookAction.DecreaseServings) },
        onIncrease = { onAction(CookAction.IncreaseServings) },
        decreaseEnabled = servings.canDecrease,
        decreaseDescription = stringResource(R.string.cook_fewer_servings),
        increaseDescription = stringResource(R.string.cook_more_servings),
        modifier = modifier,
    )
}

/** A label, a value and − / + buttons of 64 dp. */
@Composable
internal fun StepperRow(
    label: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    decreaseDescription: String,
    increaseDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = TandemTheme.extendedTypography.overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        KitchenIconButton(icon = TandemIcons.Remove, description = decreaseDescription, onClick = onDecrease, enabled = decreaseEnabled)
        KitchenIconButton(icon = TandemIcons.Add, description = increaseDescription, onClick = onIncrease)
    }
}

/**
 * Two panes (plan 6.5, Stitch kokl_ge_surfplatta_*): servings and the current step's ingredients
 * on the left, scrolling on their own, and below them the active timers, pinned to the bottom of
 * the pane so they stay in view while the step changes on the right (plan 6.5 "i liggande läge
 * hela tiden synligt"). Ringing timers come first. The timer section takes at most
 * [TIMER_SECTION_SHARE] of the pane and scrolls inside; a short pane (phone in landscape) shows
 * the timers as compact rows instead of cards.
 */
@Composable
internal fun CookSidePane(
    state: CookUiState,
    now: Instant,
    timerActions: TimerActions,
    onOpenTimer: (CookTimer) -> Unit,
    onAction: (CookAction) -> Unit,
    warningActions: WarningActions,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val step = state.currentStep
    BoxWithConstraints(modifier) {
        val shortPane = maxHeight < SidePaneCardsMinHeight
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                state.servings?.let { CookServingsControl(servings = it, onAction = onAction) }
                if (step != null && step.ingredients.isNotEmpty()) {
                    Column {
                        PaneHeading(
                            text = if (state.steps.size == 1) {
                                stringResource(R.string.cook_all_ingredients)
                            } else {
                                stringResource(R.string.cook_ingredients_for_step_number, step.number)
                            },
                        )
                        CookIngredientRows(rows = step.ingredients, state = state, onAction = onAction)
                    }
                }
            }
            if (state.timers.isNotEmpty()) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * TIMER_SECTION_SHARE)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.lg, vertical = spacing.md)
                        .testTag(COOK_SIDE_TIMERS_TAG),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    PaneHeading(text = stringResource(R.string.cook_active_timers))
                    state.timers.sortedForDisplay().forEach { timer ->
                        if (shortPane) {
                            TimerBarItem(timer = timer, now = now, actions = timerActions, onOpen = { onOpenTimer(timer) })
                        } else {
                            TimerCard(
                                timer = timer,
                                now = now,
                                actions = timerActions,
                                overline = timer.stepIndex?.takeIf { timer.recipeId == state.recipeId }?.let {
                                    stringResource(R.string.cook_step_heading, it + 1)
                                },
                                onOpen = { onOpenTimer(timer) },
                            )
                        }
                    }
                    AlarmWarnings(state = state, onAction = onAction, actions = warningActions, compact = shortPane)
                }
            }
        }
    }
}

@Composable
private fun PaneHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(vertical = TandemTheme.spacing.sm)
            .semantics { heading() },
    )
}

/** A step photo's shape (Tandoor's images are mostly landscape). */
private const val STEP_IMAGE_ASPECT_RATIO = 4f / 3f

/** At most this share of the side pane goes to the timers; the ingredients keep the rest. */
private const val TIMER_SECTION_SHARE = 0.6f

/** Side panes at least this tall show timers as cards; shorter ones (phones in landscape) as rows. */
private val SidePaneCardsMinHeight = 560.dp

/** Test tags. */
internal const val COOK_SIDE_TIMERS_TAG = "cook:side-timers"
internal const val COOK_STEP_IMAGE_TAG = "cook:step-image"

/** Any quantity that selects the "other" plural form in English and Swedish. */
private const val PLURAL_OTHER = 2
