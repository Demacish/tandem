package casa.moller.tandem.feature.recipes.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.DownloadForOffline
import casa.moller.tandem.core.designsystem.icon.DownloadForOfflineFilled
import casa.moller.tandem.core.designsystem.icon.History
import casa.moller.tandem.core.designsystem.icon.Link
import casa.moller.tandem.core.designsystem.icon.MoreVert
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.needsSpokenNumbers
import casa.moller.tandem.core.domain.format.spokenNumbers
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow as DisplayRow
import casa.moller.tandem.core.domain.recipe.UnitAmount
import casa.moller.tandem.core.ui.ErrorState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.recipe.IconLabel
import casa.moller.tandem.core.ui.recipe.IngredientHeader
import casa.moller.tandem.core.ui.recipe.IngredientRow
import casa.moller.tandem.core.ui.recipe.KeywordPill
import casa.moller.tandem.core.ui.recipe.MarkdownText
import casa.moller.tandem.core.ui.recipe.RatingStars
import casa.moller.tandem.core.ui.recipe.cappedAspectRatio
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.core.ui.recipe.ServingsStepper
import casa.moller.tandem.core.ui.recipe.StepCard
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.core.ui.recipe.formatMinutes
import casa.moller.tandem.core.ui.recipe.formatRating
import casa.moller.tandem.feature.recipes.R
import java.net.URI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant

/** Windows at least this wide show ingredients and steps side by side (plan 6.5). */
internal val TwoPaneMinWidth = 720.dp

/**
 * The recipe detail screen (plan 6.2 "Receptdetalj", designs recept_kr_mig_svamppasta and
 * receptvy_kr_mig_svamppasta_surfplatta). Stateless: [state] in, [onAction] out.
 *
 * - Compact: photo, title and facts, servings, ingredients, then the steps in one column.
 * - At least [TwoPaneMinWidth] wide (tablet, list-detail pane; plan 6.5, design
 *   receptvy_kr_mig_svamppasta_surfplatta): two panes that scroll on their own. The left pane
 *   holds the photo (height capped), the servings picker and the ingredients, so they stay in
 *   view while the right pane (title, facts, description, steps) scrolls through the steps.
 *
 * Phase 1a shows no cooking, shopping, planning or favourite actions (Phase 1b/2).
 *
 * Amounts and step texts arrive ready to show (core:domain `RecipeDisplay`, web parity); the
 * screen only reports how numbers are written in its locale ([RecipeDetailAction.SetNumberStyle]).
 *
 * @param onOpenUrl opens a web page (the recipe in Tandoor, the source, links in steps go
 *   through Compose's own link handling).
 * @param onOpenRecipe opens another recipe (a step's sub-recipe).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    state: RecipeDetailUiState,
    onAction: (RecipeDetailAction) -> Unit,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollState = rememberScrollState()
    // The name moves into the top bar once the big title has scrolled away.
    val titleThreshold = with(LocalDensity.current) { TITLE_SCROLL_THRESHOLD.roundToPx() }
    val showNameInBar by remember { derivedStateOf { scrollState.value > titleThreshold } }
    // Decimal comma and fraction glyphs follow the (per-app) language; resent when it changes.
    val numberStyle = currentLocale().toNumberStyle()
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(numberStyle) { currentOnAction(RecipeDetailAction.SetNumberStyle(numberStyle)) }
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TandemTopAppBar(
                title = state.header?.name?.takeIf { showNameInBar }
                    ?: stringResource(R.string.recipe_detail_title),
                onNavigateBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = { DetailActions(state = state, onAction = onAction, onOpenUrl = onOpenUrl) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            state.loading -> LoadingState(modifier = contentModifier, message = stringResource(R.string.recipe_detail_loading))

            state.isEmpty && state.error != null -> Column(contentModifier.verticalScroll(rememberScrollState())) {
                ErrorState(error = state.error, onRetry = { onAction(RecipeDetailAction.Refresh) })
            }

            state.isEmpty -> LoadingState(modifier = contentModifier, message = stringResource(R.string.recipe_detail_loading))

            else -> DetailContent(
                state = state,
                scrollState = scrollState,
                onAction = onAction,
                onOpenUrl = onOpenUrl,
                onOpenRecipe = onOpenRecipe,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun DetailActions(state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit, onOpenUrl: (String) -> Unit) {
    AppChromeStatusChip(Modifier.padding(end = TandemTheme.spacing.xs))
    if (state.canPin) {
        IconButton(onClick = { onAction(RecipeDetailAction.ToggleOffline) }) {
            Icon(
                imageVector = if (state.pinned) TandemIcons.DownloadForOfflineFilled else TandemIcons.DownloadForOffline,
                contentDescription = stringResource(
                    if (state.pinned) R.string.recipe_detail_action_remove_offline else R.string.recipe_detail_action_download_offline,
                ),
                tint = if (state.pinned) TandemTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(imageVector = TandemIcons.MoreVert, contentDescription = stringResource(R.string.recipe_detail_action_more))
        }
        OverflowMenu(state = state, expanded = menuOpen, onDismiss = { menuOpen = false }, onAction = onAction, onOpenUrl = onOpenUrl)
    }
}

@Composable
private fun OverflowMenu(
    state: RecipeDetailUiState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (RecipeDetailAction) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        state.webUrl?.let { url ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recipe_detail_action_open_on_web)) },
                leadingIcon = { Icon(TandemIcons.OpenInNew, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onOpenUrl(url)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.recipe_detail_action_refresh)) },
            leadingIcon = { Icon(TandemIcons.Refresh, contentDescription = null) },
            enabled = !state.refreshing,
            onClick = {
                onDismiss()
                onAction(RecipeDetailAction.Refresh)
            },
        )
    }
}

@Composable
private fun DetailContent(
    state: RecipeDetailUiState,
    scrollState: ScrollState,
    onAction: (RecipeDetailAction) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth >= TwoPaneMinWidth && state.hasDetail) {
            TwoPaneContent(state, scrollState, onAction, onOpenUrl, onOpenRecipe)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = spacing.screenHorizontal)
                    .padding(bottom = spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                if (state.refreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Notices(state)
                HeaderImage(state, Modifier.fillMaxWidth().aspectRatio(PHOTO_ASPECT_RATIO))
                Header(state)
                if (!state.hasDetail) {
                    MissingDetail(state = state, onAction = onAction)
                } else {
                    Servings(state, onAction)
                    Ingredients(state, onAction)
                    Steps(state, onAction, onOpenRecipe)
                    Source(state, onOpenUrl)
                }
            }
        }
    }
}

/**
 * Tablet layout (plan 6.5 "ingredienser (fasta, med portionsväljare) till vänster, steg till
 * höger"): each pane scrolls on its own, so the ingredients stay in view while the steps scroll.
 * [scrollState] belongs to the right pane, which holds the title (it moves into the top bar).
 */
@Composable
private fun TwoPaneContent(
    state: RecipeDetailUiState,
    scrollState: ScrollState,
    onAction: (RecipeDetailAction) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenRecipe: (Long) -> Unit,
) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(spacing.xl),
    ) {
        Column(
            modifier = Modifier
                .weight(INGREDIENT_PANE_WEIGHT)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.xxl)
                .testTag(DETAIL_INGREDIENT_PANE_TAG),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            HeaderImage(state, Modifier.cappedAspectRatio(PHOTO_ASPECT_RATIO, PANE_PHOTO_MAX_HEIGHT))
            Servings(state, onAction)
            Ingredients(state, onAction)
        }
        Column(
            modifier = Modifier
                .weight(STEP_PANE_WEIGHT)
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .padding(bottom = spacing.xxl)
                .testTag(DETAIL_STEP_PANE_TAG),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            if (state.refreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Notices(state)
            Header(state)
            Steps(state, onAction, onOpenRecipe)
            Source(state, onOpenUrl)
        }
    }
}

@Composable
private fun Notices(state: RecipeDetailUiState) {
    when {
        state.isDeleted && state.hasDetail -> CalloutCard(
            title = stringResource(R.string.recipe_detail_deleted_title),
            body = stringResource(R.string.recipe_detail_deleted_body),
            icon = TandemIcons.Info,
            tone = TandemTone.Warning,
        )

        // A line that wraps rather than a one-line chip: at font scale 2.0 the date must stay
        // visible ("Saved version from Oct 1, 2026" is wider than a phone).
        state.showsSavedVersion -> {
            val fetched = state.fetchedAt?.let { formatDate(it, currentLocale()) }
            IconLabel(
                icon = if (state.offline) TandemIcons.CloudOff else TandemIcons.Info,
                text = if (fetched != null) {
                    stringResource(R.string.recipe_detail_saved_version_from, fetched)
                } else {
                    stringResource(R.string.recipe_detail_saved_version)
                },
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The recipe's photo (or its placeholder), sized by [modifier]. */
@Composable
private fun HeaderImage(state: RecipeDetailUiState, modifier: Modifier) {
    val header = state.header ?: return
    RecipeImage(imageUrl = header.imageUrl, modifier = modifier.clip(MaterialTheme.shapes.extraLarge))
}

/** Keywords, name, rating and times, how often it was cooked, and the description. */
@Composable
private fun Header(state: RecipeDetailUiState) {
    val header = state.header ?: return
    val spacing = TandemTheme.spacing
    val locale = currentLocale()
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (header.keywords.isNotEmpty() || header.isPrivate) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                header.keywords.forEach { KeywordPill(it.name) }
                if (header.isPrivate) {
                    StatusChip(label = stringResource(R.string.recipe_private), icon = TandemIcons.Lock)
                }
            }
        }
        Text(
            text = header.name,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (header.rating != null && header.rating > 0.0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    RatingStars(rating = header.rating)
                    // The stars already tell TalkBack the rating.
                    Text(
                        text = formatRating(header.rating, locale),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            TimeFacts(header)
        }
        CookedLine(header, locale)
        header.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Readable line length, about 70 characters (plan 6.5).
                modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth),
            )
        }
    }
}

/**
 * Active, waiting and total time. Each fact is its own item of the surrounding FlowRow, so a long
 * line (English "Active 15 min  Waiting 15 min  Total 30 min" on a phone) wraps between the facts
 * instead of cutting the last one off.
 */
@Composable
private fun TimeFacts(header: RecipeHeader) {
    if (header.totalMinutes <= 0) return
    if (header.workingMinutes > 0 && header.waitingMinutes > 0) {
        Fact(R.string.recipe_detail_working_time, header.workingMinutes)
        Fact(R.string.recipe_detail_waiting_time, header.waitingMinutes)
    }
    Fact(R.string.recipe_detail_total_time, header.totalMinutes)
}

@Composable
private fun Fact(@StringRes label: Int, minutes: Int) {
    val value = formatMinutes(minutes)
    val text = stringResource(label, value)
    IconLabel(icon = TandemIcons.Schedule, text = text)
}

@Composable
private fun CookedLine(header: RecipeHeader, locale: Locale) {
    val times = header.timesCooked?.takeIf { it > 0 }
    val last = header.lastCooked?.let { formatDate(it, locale) }
    val text = when {
        times != null && last != null -> pluralStringResource(R.plurals.recipe_detail_cooked_times_last, times, times, last)
        times != null -> pluralStringResource(R.plurals.recipe_detail_cooked_times, times, times)
        last != null -> stringResource(R.string.recipe_detail_cooked_last, last)
        else -> return
    }
    IconLabel(icon = TandemIcons.History, text = text)
}

@Composable
private fun MissingDetail(state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit) {
    when {
        state.refreshing -> LoadingState(message = stringResource(R.string.recipe_detail_loading))

        state.offline -> CalloutCard(
            title = stringResource(R.string.recipe_detail_not_downloaded_title),
            body = stringResource(R.string.recipe_detail_not_downloaded_body),
            icon = TandemIcons.CloudOff,
            tone = TandemTone.Neutral,
            actions = {
                TandemTextButton(
                    text = stringResource(R.string.recipe_detail_action_retry),
                    onClick = { onAction(RecipeDetailAction.Refresh) },
                )
            },
        )

        state.error != null -> ErrorState(error = state.error, onRetry = { onAction(RecipeDetailAction.Refresh) })

        else -> LoadingState(message = stringResource(R.string.recipe_detail_loading))
    }
}

@Composable
private fun Servings(state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit) {
    val servings = state.servings ?: return
    val amount = servings.amountText
    val valueText = if (servings.text.isNotBlank()) {
        stringResource(R.string.recipe_detail_servings_with_text, amount, servings.text)
    } else {
        pluralStringResource(R.plurals.recipe_detail_servings, if (servings.current == 1.0) 1 else PLURAL_OTHER, amount)
    }
    ServingsStepper(
        label = stringResource(R.string.recipe_detail_servings_label),
        valueText = valueText,
        amountText = amount,
        onDecrease = { onAction(RecipeDetailAction.DecreaseServings) },
        onIncrease = { onAction(RecipeDetailAction.IncreaseServings) },
        decreaseEnabled = servings.canDecrease,
        onReset = if (servings.isChanged) ({ onAction(RecipeDetailAction.ResetServings) }) else null,
        resetText = stringResource(R.string.recipe_detail_servings_reset, servings.original),
    )
}

@Composable
private fun Ingredients(state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit) {
    if (state.ingredients.isEmpty()) return
    Column {
        SectionHeading(
            title = stringResource(R.string.recipe_detail_ingredients),
            hint = stringResource(R.string.recipe_detail_ingredients_hint),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(top = TandemTheme.spacing.sm),
        ) {
            Column(modifier = Modifier.padding(vertical = TandemTheme.spacing.xs)) {
                IngredientRows(state.ingredients, state, onAction)
            }
        }
    }
}

@Composable
private fun IngredientRows(rows: List<DisplayRow>, state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit) {
    rows.forEach { row ->
        when (row) {
            is DisplayRow.Header -> IngredientHeader(row.title)

            is DisplayRow.Item -> {
                val amount = row.unitAt(state.unitChoices[row.id] ?: 0)?.text()
                IngredientRow(
                    amountText = amount,
                    // "3/16 tsk" instead of the typographic "³⁄₁₆ tsk" for TalkBack (plan 6.6).
                    amountDescription = amount?.let(::spokenNumbers),
                    name = row.food,
                    note = row.note.ifEmpty { null },
                    struck = row.id in state.struckIngredientIds,
                    onToggle = { onAction(RecipeDetailAction.ToggleIngredient(row.id)) },
                    onAmountClick = if (row.canCycleUnits) ({ onAction(RecipeDetailAction.SwitchUnit(row.id)) }) else null,
                )
            }
        }
    }
}

/** "2,5 dl", "3" (no unit), "1 ½ cups": the amount and unit the web shows, in one text. */
private fun UnitAmount.text(): String = if (unit.isEmpty()) amount else "$amount $unit"

/** Shown under a step whose templates Tandoor could not parse: its text is as written, not scaled. */
@Composable
private fun BrokenTemplateNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
        Icon(
            imageVector = TandemIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(TandemTheme.sizes.iconSmall),
        )
        Text(
            text = stringResource(R.string.recipe_detail_step_template_broken),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Steps(state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit, onOpenRecipe: (Long) -> Unit) {
    if (state.steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md)) {
        SectionHeading(
            title = stringResource(R.string.recipe_detail_steps),
            hint = pluralStringResource(R.plurals.recipe_detail_step_count, state.steps.size, state.steps.size),
        )
        state.steps.forEach { step ->
            val stepRecipe = step.stepRecipe
            val stepRecipeName = stepRecipe?.name
            StepCard(
                number = step.number,
                title = step.name,
                minutes = step.minutes,
                ingredients = if (step.showIngredients) ({ IngredientRows(step.ingredients, state, onAction) }) else null,
                footer = if (stepRecipe != null) {
                    {
                        TandemTonalButton(
                            text = if (stepRecipeName != null) {
                                stringResource(R.string.recipe_detail_open_step_recipe_named, stepRecipeName)
                            } else {
                                stringResource(R.string.recipe_detail_open_step_recipe)
                            },
                            onClick = { onOpenRecipe(stepRecipe.recipeId) },
                            trailingIcon = TandemIcons.ArrowForward,
                        )
                    }
                } else {
                    null
                },
            ) {
                if (step.instruction.isNotBlank()) {
                    MarkdownText(
                        markdown = step.instruction,
                        modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth),
                        spokenText = SpokenFractions,
                    )
                }
                if (step.hasBrokenTemplate) BrokenTemplateNote()
            }
        }
    }
}

@Composable
private fun Source(state: RecipeDetailUiState, onOpenUrl: (String) -> Unit) {
    val url = state.sourceUrl ?: return
    val host = remember(url) { runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url }
    TandemTextButton(
        text = stringResource(R.string.recipe_detail_source, host),
        onClick = { onOpenUrl(url) },
        leadingIcon = TandemIcons.Link,
    )
}

@Composable
private fun SectionHeading(title: String, hint: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (hint != null) {
            Text(text = hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "1 okt. 2026" / "Oct 1, 2026" in the device's time zone. */
internal fun formatDate(instant: Instant, locale: Locale): String {
    val dateTime = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(dateTime)
}

/** How far the content scrolls before the recipe name shows in the top bar. */
private val TITLE_SCROLL_THRESHOLD = 280.dp

private const val PHOTO_ASPECT_RATIO = 16f / 10f

/** The photo at the top of the ingredient pane never pushes the ingredients far down. */
private val PANE_PHOTO_MAX_HEIGHT = 240.dp
private const val INGREDIENT_PANE_WEIGHT = 0.42f
private const val STEP_PANE_WEIGHT = 0.58f

/** Test tags of the two scrolling panes on wide windows. */
internal const val DETAIL_INGREDIENT_PANE_TAG = "recipe-detail:ingredient-pane"
internal const val DETAIL_STEP_PANE_TAG = "recipe-detail:step-pane"

/** Step texts with scaled typographic fractions get a spoken form; others stay as they are. */
private val SpokenFractions: (String) -> String = { text -> if (needsSpokenNumbers(text)) spokenNumbers(text) else text }

/** Any quantity that selects the "other" plural form in English and Swedish. */
private const val PLURAL_OTHER = 2
