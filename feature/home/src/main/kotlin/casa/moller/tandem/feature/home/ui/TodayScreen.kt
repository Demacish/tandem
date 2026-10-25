package casa.moller.tandem.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.home.HomeSection
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.diagnostics.ReportContentReady
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.core.ui.status.OfflineNotice
import casa.moller.tandem.feature.home.DayPart
import casa.moller.tandem.feature.home.HomeAction
import casa.moller.tandem.feature.home.HomeUiState
import casa.moller.tandem.feature.home.R
import casa.moller.tandem.feature.home.TodayBooksState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate

/** Test tag of the Today list (scrolling in tests). */
const val TODAY_LIST_TAG = "today:list"

/** From this width Today uses the two-column layout of design idag_surfplatta_* (M3 expanded). */
private val TwoColumnMinWidth = 840.dp

/**
 * The Today tab (plan 6.2 "Idag", Stitch idag_*, idag_demol_ge, idag_surfplatta_*), stateless.
 *
 * - Phones and medium windows: one column. Header (date, greeting), demo banner, offline notice,
 *   "Tonight" (or "Nothing planned today"), the rest of today, tomorrow, then the carousels.
 * - Expanded windows (>= 840 dp, tablets in landscape): tonight and the rest of today on the left,
 *   tomorrow and the sync card on the right, the carousels across the full width below.
 *
 * Pull to refresh refreshes the sections and the meal plan; offline, the last fetched content
 * stays with a notice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    books: TodayBooksState = TodayBooksState(),
    onOpenWantToCook: () -> Unit = {},
) {
    // Phase 1b (inställningar lane): the cold start ends when the stored day is shown (Diagnostics).
    ReportContentReady(ready = !state.loading)
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.home_title),
                actions = { AppChromeStatusChip(Modifier.padding(end = spacing.md)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { onAction(HomeAction.Refresh) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val twoColumns = maxWidth >= TwoColumnMinWidth
                    TodayContent(
                        state = state,
                        onAction = onAction,
                        twoColumns = twoColumns,
                        books = books,
                        onOpenWantToCook = onOpenWantToCook,
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayContent(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    twoColumns: Boolean,
    books: TodayBooksState,
    onOpenWantToCook: () -> Unit,
) {
    val spacing = TandemTheme.spacing
    val horizontal = if (twoColumns) spacing.xxl else spacing.screenHorizontal
    val isDemo = LocalAppChrome.current.status == AppStatus.Demo
    val openRecipe: (Long) -> Unit = { onAction(HomeAction.OpenRecipe(it)) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TODAY_LIST_TAG),
        contentPadding = PaddingValues(top = spacing.sm, bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        item(key = "header") {
            TodayHeader(
                today = state.today,
                dayPart = state.dayPart,
                modifier = Modifier.padding(horizontal = horizontal),
            )
        }
        item(key = "banners") {
            Column(
                modifier = Modifier.padding(horizontal = horizontal),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                AppChromeDemoBanner()
                // Phase 2 (books lane, #42): a guest sees once, at the top, why nothing can be changed.
                if (books.readOnly) ReadOnlyBanner()
                if (state.offline) OfflineNotice()
            }
        }
        if (twoColumns) {
            item(key = "plan") {
                Row(
                    modifier = Modifier.padding(horizontal = horizontal),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xl),
                ) {
                    Column(modifier = Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        TodayMeals(state = state, onAction = onAction)
                    }
                    Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
                        TomorrowMeals(meals = state.tomorrow, onOpenRecipe = openRecipe)
                        if (!isDemo) TodaySyncCard(state = state, onAction = onAction)
                    }
                }
            }
        } else {
            item(key = "plan") {
                Column(
                    modifier = Modifier
                        .padding(horizontal = horizontal)
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    TodayMeals(state = state, onAction = onAction)
                    TomorrowMeals(meals = state.tomorrow, onOpenRecipe = openRecipe)
                }
            }
        }
        // Phase 2 (books lane): "Vill laga" before the other carousels (plan 8.3: the queue to plan from).
        if (books.wantToCook.isNotEmpty()) {
            item(key = "want-to-cook") {
                RecipeRow(
                    title = stringResource(R.string.home_section_want_to_cook),
                    subtitle = stringResource(R.string.home_section_want_to_cook_subtitle),
                    recipes = books.wantToCook,
                    horizontal = horizontal,
                    cardWidth = if (twoColumns) 240.dp else 168.dp,
                    onOpenRecipe = openRecipe,
                    allText = stringResource(R.string.home_want_to_cook_all),
                    onOpenAll = onOpenWantToCook,
                )
            }
        }
        state.sections.forEachIndexed { index, section ->
            item(key = "section-${section.type}") {
                SectionCarousel(
                    section = section,
                    horizontal = horizontal,
                    cardWidth = if (twoColumns) 240.dp else 168.dp,
                    onOpenRecipe = openRecipe,
                    onOpenAll = if (index == 0) ({ onAction(HomeAction.OpenRecipes) }) else null,
                )
            }
        }
        if (state.sections.isEmpty()) {
            item(key = "sections-empty") {
                Text(
                    text = stringResource(R.string.home_empty_sections),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = horizontal)
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth),
                )
            }
        }
        if (!twoColumns && !isDemo) {
            item(key = "sync") {
                TodaySyncCard(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier
                        .padding(horizontal = horizontal)
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth),
                )
            }
        }
    }
}

/** Date and greeting above the plan ("Tuesday 6 October" / "Good evening"). */
@Composable
private fun TodayHeader(today: LocalDate?, dayPart: DayPart, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xxs)) {
        if (today != null) {
            val date = remember(today, locale) {
                val formatted = DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(today.toJavaLocalDate())
                formatted.replaceFirstChar { it.titlecase(locale) }
            }
            Text(text = date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = stringResource(
                when (dayPart) {
                    DayPart.MORNING -> R.string.home_greeting_morning
                    DayPart.DAY -> R.string.home_greeting_day
                    DayPart.EVENING, DayPart.NIGHT -> R.string.home_greeting_evening
                },
            ),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Tonight (or "Nothing planned today") and the rest of today. */
@Composable
private fun TodayMeals(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    val openRecipe: (Long) -> Unit = { onAction(HomeAction.OpenRecipe(it)) }
    val tonight = state.tonight
    when {
        tonight != null -> TonightCard(
            entry = tonight,
            onOpenRecipe = openRecipe,
            onStartCooking = { id, servings -> onAction(HomeAction.StartCooking(id, servings)) },
        )

        // No "Plan the week" until the Week tab can plan (Phase 2): it shows a placeholder now.
        state.nothingPlannedToday -> NothingPlannedCard(onBrowseRecipes = { onAction(HomeAction.OpenRecipes) })
    }
    if (state.alsoToday.isNotEmpty()) {
        MealGroup(
            title = stringResource(R.string.home_also_today),
            label = stringResource(R.string.home_also_today),
            meals = state.alsoToday,
            onOpenRecipe = openRecipe,
        )
    }
}

@Composable
private fun TomorrowMeals(meals: List<MealPlanEntry>, onOpenRecipe: (Long) -> Unit) {
    if (meals.isEmpty()) return
    val tomorrow = stringResource(R.string.home_tomorrow)
    MealGroup(title = tomorrow, label = tomorrow, meals = meals, onOpenRecipe = onOpenRecipe)
}

@Composable
private fun MealGroup(title: String, label: String, meals: List<MealPlanEntry>, onOpenRecipe: (Long) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        meals.forEach { entry ->
            MealRow(entry = entry, label = label, onOpenRecipe = onOpenRecipe)
        }
    }
}

/** One carousel: heading, subtitle, "All recipes" on the first one, and a row of cards. */
@Composable
private fun SectionCarousel(section: HomeSection, horizontal: Dp, cardWidth: Dp, onOpenRecipe: (Long) -> Unit, onOpenAll: (() -> Unit)?) {
    val texts = section.type.texts()
    RecipeRow(
        title = stringResource(texts.first),
        subtitle = stringResource(texts.second),
        recipes = section.recipes,
        horizontal = horizontal,
        cardWidth = cardWidth,
        onOpenRecipe = onOpenRecipe,
        allText = stringResource(R.string.home_all_recipes),
        onOpenAll = onOpenAll,
    )
}

/** A heading with a subtitle and an optional "all" button, then a row of recipe cards. */
@Composable
private fun RecipeRow(
    title: String,
    subtitle: String,
    recipes: List<RecipeOverview>,
    horizontal: Dp,
    cardWidth: Dp,
    onOpenRecipe: (Long) -> Unit,
    allText: String,
    onOpenAll: (() -> Unit)?,
) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizontal, end = horizontal - spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onOpenAll != null) {
                TandemTextButton(
                    text = allText,
                    onClick = onOpenAll,
                    trailingIcon = TandemIcons.ArrowForward,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = horizontal),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            items(recipes, key = { it.id }) { recipe ->
                HomeRecipeCard(recipe = recipe, width = cardWidth, onClick = { onOpenRecipe(recipe.id) })
            }
        }
    }
}

/** Title and subtitle of a carousel. */
private fun HomeSectionType.texts(): Pair<Int, Int> = when (this) {
    HomeSectionType.PINNED -> R.string.home_section_pinned to R.string.home_section_pinned_subtitle

    HomeSectionType.OFTEN_COOKED -> R.string.home_section_often_cooked to R.string.home_section_often_cooked_subtitle

    HomeSectionType.NEW -> R.string.home_section_new to R.string.home_section_new_subtitle

    HomeSectionType.RECENTLY_VIEWED -> R.string.home_section_recently_viewed to R.string.home_section_recently_viewed_subtitle

    HomeSectionType.NOT_COOKED_LATELY ->
        R.string.home_section_not_cooked_lately to R.string.home_section_not_cooked_lately_subtitle
}

@Composable
private fun TodaySyncCard(state: HomeUiState, onAction: (HomeAction) -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val syncTime = state.lastSynced?.let { instant -> remember(instant, locale, state.today) { formatSyncTime(instant, locale) } }
    val lastSynced = syncTime?.let { (sameDay, text) ->
        stringResource(if (sameDay) R.string.home_sync_last_today else R.string.home_sync_last_date, text)
    }
    SyncStatusCard(
        lastSynced = lastSynced,
        running = state.syncRunning || state.refreshing,
        onSyncNow = { onAction(HomeAction.Refresh) },
        modifier = modifier,
    )
}

/**
 * "18:14" today, "5 Oct 2026, 18:14" on an earlier day, in the device's time zone; first whether
 * it was today ("at 18:14" / "kl. 18:14" versus "on …").
 */
private fun formatSyncTime(instant: Instant, locale: Locale): Pair<Boolean, String> {
    val zone = ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(zone)
    val sameDay = time.toLocalDate() == java.time.LocalDate.now(zone)
    val formatter = if (sameDay) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
    return sameDay to formatter.withLocale(locale).format(time)
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
