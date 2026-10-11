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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
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
import casa.moller.tandem.core.domain.scaling.FormatAmount
import casa.moller.tandem.core.ui.ErrorState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.recipe.IconLabel
import casa.moller.tandem.core.ui.recipe.IngredientHeader
import casa.moller.tandem.core.ui.recipe.IngredientRow
import casa.moller.tandem.core.ui.recipe.KeywordPill
import casa.moller.tandem.core.ui.recipe.MarkdownText
import casa.moller.tandem.core.ui.recipe.RatingStars
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
 * - At least [TwoPaneMinWidth] wide (tablet, list-detail pane): photo as a banner, then
 *   ingredients with the servings picker on the left and the steps on the right.
 *
 * Phase 1a shows no cooking, shopping, planning or favourite actions (Phase 1b/2).
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
        val twoPane = maxWidth >= TwoPaneMinWidth
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
            Header(state = state, twoPane = twoPane)
            if (!state.hasDetail) {
                MissingDetail(state = state, onAction = onAction)
            } else if (twoPane) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xl)) {
                    Column(modifier = Modifier.weight(INGREDIENT_PANE_WEIGHT), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        Servings(state, onAction)
                        Ingredients(state, onAction)
                    }
                    Column(modifier = Modifier.weight(STEP_PANE_WEIGHT), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        Steps(state, onAction, onOpenRecipe)
                        Source(state, onOpenUrl)
                    }
                }
            } else {
                Servings(state, onAction)
                Ingredients(state, onAction)
                Steps(state, onAction, onOpenRecipe)
                Source(state, onOpenUrl)
            }
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

        state.showsSavedVersion -> {
            val fetched = state.fetchedAt?.let { formatDate(it, currentLocale()) }
            StatusChip(
                label = if (fetched != null) {
                    stringResource(R.string.recipe_detail_saved_version_from, fetched)
                } else {
                    stringResource(R.string.recipe_detail_saved_version)
                },
                icon = if (state.offline) TandemIcons.CloudOff else TandemIcons.Info,
                tone = TandemTone.Neutral,
            )
        }
    }
}

@Composable
private fun Header(state: RecipeDetailUiState, twoPane: Boolean) {
    val header = state.header ?: return
    val spacing = TandemTheme.spacing
    val locale = currentLocale()
    RecipeImage(
        imageUrl = header.imageUrl,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (twoPane) BANNER_ASPECT_RATIO else PHOTO_ASPECT_RATIO)
            .clip(MaterialTheme.shapes.extraLarge),
    )
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
            Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimeFacts(header: RecipeHeader) {
    val spacing = TandemTheme.spacing
    if (header.totalMinutes <= 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.lg)) {
        if (header.workingMinutes > 0 && header.waitingMinutes > 0) {
            Fact(R.string.recipe_detail_working_time, header.workingMinutes)
            Fact(R.string.recipe_detail_waiting_time, header.waitingMinutes)
        }
        Fact(R.string.recipe_detail_total_time, header.totalMinutes)
    }
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
    val locale = currentLocale()
    val amount = FormatAmount(servings.current, locale, useFractions = true, decimals = 2)
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
private fun IngredientRows(items: List<IngredientItem>, state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit) {
    val locale = currentLocale()
    items.forEach { item ->
        if (item.isHeader) {
            IngredientHeader(item.title)
        } else {
            val unitIndex = state.unitChoices[item.id] ?: 0
            IngredientRow(
                amountText = item.amountText(unitIndex, locale, state.preferences),
                name = item.nameText(),
                note = item.note,
                struck = item.id in state.struckIngredientIds,
                onToggle = { onAction(RecipeDetailAction.ToggleIngredient(item.id)) },
                onAmountClick = if (item.canSwitchUnit) ({ onAction(RecipeDetailAction.SwitchUnit(item.id)) }) else null,
            )
        }
    }
}

@Composable
private fun Steps(state: RecipeDetailUiState, onAction: (RecipeDetailAction) -> Unit, onOpenRecipe: (Long) -> Unit) {
    if (state.steps.isEmpty()) return
    val locale = currentLocale()
    val factor = state.servings?.factor ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md)) {
        SectionHeading(
            title = stringResource(R.string.recipe_detail_steps),
            hint = pluralStringResource(R.plurals.recipe_detail_step_count, state.steps.size, state.steps.size),
        )
        state.steps.forEach { step ->
            val stepRecipe = step.stepRecipe
            val stepRecipeName = stepRecipe?.name
            val instruction = remember(step.instruction, step.ingredients, factor, locale, state.preferences) {
                StepTemplates.render(step.instruction, step.ingredients, factor, locale, state.preferences)
            }
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
                if (instruction.isNotBlank()) MarkdownText(markdown = instruction)
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
private const val BANNER_ASPECT_RATIO = 21f / 8f
private const val INGREDIENT_PANE_WEIGHT = 0.42f
private const val STEP_PANE_WEIGHT = 0.58f

/** Any quantity that selects the "other" plural form in English and Swedish. */
private const val PLURAL_OTHER = 2
