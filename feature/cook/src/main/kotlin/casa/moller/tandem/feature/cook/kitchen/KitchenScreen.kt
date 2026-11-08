package casa.moller.tandem.feature.cook.kitchen

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.icon.Cooking
import casa.moller.tandem.core.designsystem.icon.Countertops
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Timer
import casa.moller.tandem.core.designsystem.theme.TandemDarkTheme
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.recipe.ServingsText
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.core.ui.timer.KitchenIconButton
import casa.moller.tandem.core.ui.timer.LocalTimerClock
import casa.moller.tandem.core.ui.timer.LocalTimerTicks
import casa.moller.tandem.core.ui.timer.TimerActions
import casa.moller.tandem.core.ui.timer.TimerCard
import casa.moller.tandem.core.ui.timer.rememberTimerNow
import casa.moller.tandem.core.ui.window.KeepScreenOn
import casa.moller.tandem.feature.cook.R
import casa.moller.tandem.feature.cook.ui.DarkSystemBars
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.time.Instant
import kotlinx.coroutines.delay

/**
 * How the kitchen display lays itself out, from the window (plan §6.5, never from the device type).
 *
 * - [ONE_COLUMN]: phones and tablets in portrait: the time, today's meals, the timers, tomorrow.
 * - [TWO_COLUMNS]: wide windows (from 840 dp, or from 600 dp in landscape): the timers on the
 *   left, the meals on the right (Stitch k_kssk_rmsl_get_ambient_k_kshub).
 */
enum class KitchenLayout { ONE_COLUMN, TWO_COLUMNS }

internal fun kitchenLayoutFor(width: Dp, height: Dp): KitchenLayout =
    if (width >= ExpandedWidth || (width >= MediumWidth && width > height)) KitchenLayout.TWO_COLUMNS else KitchenLayout.ONE_COLUMN

/**
 * Test hook (the 3-hour test): called with the part's name every time the clock, the meals or the
 * timers recompose. A no-op in the app.
 */
internal val LocalKitchenRecompositions = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Fas 5 (kok lane, plan §8.7 "Köksskärmsläge", #41, §6.5): the kitchen display, stateless. Always
 * dark (with light system bar icons), very large text, the screen kept on while it is shown; the
 * time, today's meals with "Laga" (one tap into cook mode with the planned servings), tomorrow's,
 * and the active timers as large cards with their buttons. Leaving it (✕ or back) puts the window
 * back as it was: both the dark bars and the screen-on flag are reference counted per window.
 *
 * @param onStartCooking cook mode for a meal's recipe at its planned servings.
 * @param onOpenTimer cook mode at a timer's recipe and step (the shell checks the timer's source).
 * @param layout forces a layout (tests); null decides from the window.
 */
@Composable
fun KitchenScreen(
    state: KitchenUiState,
    onAction: (KitchenAction) -> Unit,
    onClose: () -> Unit,
    onStartCooking: (recipeId: Long, servings: Double?) -> Unit,
    onOpenTimer: (CookTimer) -> Unit,
    modifier: Modifier = Modifier,
    layout: KitchenLayout? = null,
) {
    KeepScreenOn()
    DarkSystemBars(enabled = true)
    TandemDarkTheme(enabled = true) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = kitchenTypography()) {
            Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .testTag(KITCHEN_TAG),
                ) {
                    val chosen = layout ?: kitchenLayoutFor(maxWidth, maxHeight)
                    Column(Modifier.fillMaxSize()) {
                        KitchenHeader(onClose = onClose)
                        if (state.loading) {
                            LoadingState(modifier = Modifier.fillMaxSize())
                        } else {
                            KitchenBody(state, onAction, onStartCooking, onOpenTimer, chosen)
                        }
                    }
                }
            }
        }
    }
}

/** The kitchen's type: the timers' countdown (`displaySmall` in [TimerCard]) and their names large. */
@Composable
private fun kitchenTypography(): Typography {
    val base = MaterialTheme.typography
    return base.copy(displaySmall = base.displayLarge, titleMedium = base.headlineSmall, bodyMedium = base.titleMedium)
}

@Composable
private fun KitchenBody(
    state: KitchenUiState,
    onAction: (KitchenAction) -> Unit,
    onStartCooking: (Long, Double?) -> Unit,
    onOpenTimer: (CookTimer) -> Unit,
    layout: KitchenLayout,
) {
    val spacing = TandemTheme.spacing
    val actions = remember(onAction) {
        TimerActions(
            onPause = { onAction(KitchenAction.PauseTimer(it)) },
            onResume = { onAction(KitchenAction.ResumeTimer(it)) },
            onAddMinute = { onAction(KitchenAction.AddMinute(it)) },
            onCancel = { onAction(KitchenAction.CancelTimer(it)) },
            onStopRinging = { onAction(KitchenAction.StopRinging(it)) },
        )
    }
    val padding = PaddingValues(horizontal = spacing.xl, vertical = spacing.lg)
    when (layout) {
        KitchenLayout.ONE_COLUMN -> LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(KITCHEN_MEALS_TAG),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            meals(state, onStartCooking)
            timers(state, actions, onOpenTimer)
            tomorrow(state)
        }

        KitchenLayout.TWO_COLUMNS -> Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(TIMERS_WEIGHT).fillMaxHeight().testTag(KITCHEN_TIMERS_TAG),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                timers(state, actions, onOpenTimer)
            }
            LazyColumn(
                modifier = Modifier.weight(MEALS_WEIGHT).fillMaxHeight().testTag(KITCHEN_MEALS_TAG),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                meals(state, onStartCooking)
                tomorrow(state)
            }
        }
    }
}

/** The time, large, and the date; "Skärmen hålls på"; ✕ leaves. */
@Composable
private fun KitchenHeader(onClose: () -> Unit) {
    val spacing = TandemTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.xl, end = spacing.sm, top = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        KitchenClock(Modifier.weight(1f))
        StatusChip(label = stringResource(R.string.kitchen_screen_on), icon = TandemIcons.Countertops)
        KitchenIconButton(
            icon = TandemIcons.Close,
            description = stringResource(R.string.kitchen_leave),
            onClick = onClose,
            modifier = Modifier.testTag(KITCHEN_CLOSE_TAG),
        )
    }
}

/** "14:16" and "torsdag 24 oktober" in the phone's time zone and 12/24 hour choice, updated once a minute. */
@Composable
private fun KitchenClock(modifier: Modifier = Modifier) {
    val minute by rememberMinute()
    val report = LocalKitchenRecompositions.current
    SideEffect { report(PART_CLOCK) }
    val context = LocalContext.current
    val locale = currentLocale()
    val time = remember(minute, locale) { DateFormat.getTimeFormat(context).format(Date(minute.toEpochMilliseconds())) }
    val date = remember(minute, locale) {
        java.time.Instant.ofEpochMilli(minute.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(DATE_PATTERN, locale))
    }
    Column(modifier = modifier.semantics(mergeDescendants = true) { heading() }) {
        Text(
            text = stringResource(R.string.kitchen_title),
            style = TandemTheme.extendedTypography.overline,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = time, style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag(KITCHEN_CLOCK_TAG))
        Text(text = date, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The wall clock to the minute, ticking at each new minute while shown (tests: `LocalTimerTicks`). */
@Composable
private fun rememberMinute(): State<Instant> {
    val clock = LocalTimerClock.current
    val ticks = LocalTimerTicks.current
    val minute = remember(clock) { mutableStateOf(clock.now().toMinute()) }
    if (ticks) {
        LaunchedEffect(clock) {
            while (true) {
                val now = clock.now()
                minute.value = now.toMinute()
                delay(MILLIS_PER_MINUTE - now.toEpochMilliseconds().mod(MILLIS_PER_MINUTE) + TICK_SLACK_MILLIS)
            }
        }
    }
    return minute
}

private fun Instant.toMinute(): Instant {
    val millis = toEpochMilliseconds()
    return Instant.fromEpochMilliseconds(millis - millis.mod(MILLIS_PER_MINUTE))
}

private fun LazyListScope.meals(state: KitchenUiState, onStartCooking: (Long, Double?) -> Unit) {
    item(key = "today") { SectionTitle(stringResource(R.string.kitchen_today)) }
    if (state.todayMeals.isEmpty()) {
        item(key = "today-empty") {
            EmptyState(
                icon = TandemIcons.Cooking,
                title = stringResource(R.string.kitchen_nothing_today),
                body = stringResource(R.string.kitchen_nothing_today_body),
            )
        }
    }
    items(state.todayMeals, key = { "meal-${it.id}" }) { meal -> MealCard(meal = meal, onStartCooking = onStartCooking, large = true) }
}

private fun LazyListScope.tomorrow(state: KitchenUiState) {
    if (state.tomorrowMeals.isEmpty()) return
    item(key = "tomorrow") { SectionTitle(stringResource(R.string.kitchen_tomorrow)) }
    items(state.tomorrowMeals, key = { "tomorrow-${it.id}" }) { meal -> MealCard(meal = meal, onStartCooking = null, large = false) }
}

private fun LazyListScope.timers(state: KitchenUiState, actions: TimerActions, onOpenTimer: (CookTimer) -> Unit) {
    item(key = "timers") { SectionTitle(stringResource(R.string.kitchen_timers)) }
    if (state.timers.isEmpty()) {
        item(key = "timers-empty") {
            EmptyState(
                icon = TandemIcons.Timer,
                title = stringResource(R.string.kitchen_no_timers),
                body = stringResource(R.string.kitchen_no_timers_body),
            )
        }
        return
    }
    item(key = "timer-cards") { KitchenTimers(timers = state.timers, actions = actions, onOpenTimer = onOpenTimer) }
}

/** The timers as large cards, counting down once a second (only while a timer is shown). */
@Composable
private fun KitchenTimers(timers: List<CookTimer>, actions: TimerActions, onOpenTimer: (CookTimer) -> Unit) {
    val now by rememberTimerNow()
    val report = LocalKitchenRecompositions.current
    SideEffect { report(PART_TIMERS) }
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.lg)) {
        timers.forEach { timer ->
            TimerCard(
                timer = timer,
                now = now,
                actions = actions,
                onOpen = timer.recipeId?.let { { onOpenTimer(timer) } },
                showRecipe = true,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * A planned meal: picture, meal type, name and servings as one TalkBack item, and "Laga" (64 dp)
 * when it has a recipe. Tomorrow's meals ([onStartCooking] null) are smaller and have no button.
 */
@Composable
private fun MealCard(meal: KitchenMeal, onStartCooking: ((Long, Double?) -> Unit)?, large: Boolean) {
    val spacing = TandemTheme.spacing
    val report = LocalKitchenRecompositions.current
    SideEffect { report(PART_MEALS) }
    val servings = ServingsText.number(meal.servings, currentLocale().toNumberStyle())
    val servingsText = pluralStringResource(R.plurals.kitchen_servings, if (meal.servings == 1.0) 1 else PLURAL_OTHER, servings)
    val cookDescription = stringResource(R.string.kitchen_cook_description, meal.title)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            if (meal.recipeId != null) {
                RecipeImage(
                    imageUrl = meal.imageUrl,
                    modifier = Modifier
                        .size(if (large) LargeImage else SmallImage)
                        .clip(RoundedCornerShape(spacing.lg)),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    text = meal.mealType.uppercase(currentLocale()),
                    style = TandemTheme.extendedTypography.overline,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = meal.title,
                    style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meal.recipeId != null) {
                    Text(
                        text = servingsText,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onStartCooking != null && meal.recipeId != null) {
                TandemPrimaryButton(
                    text = stringResource(R.string.kitchen_cook),
                    onClick = { onStartCooking(meal.recipeId, meal.servings) },
                    leadingIcon = TandemIcons.Cooking,
                    size = TandemButtonSize.Kitchen,
                    modifier = Modifier
                        .testTag(KITCHEN_COOK_TAG)
                        .semantics { contentDescription = cookDescription },
                )
            }
        }
    }
}

private val MediumWidth = 600.dp
private val ExpandedWidth = 840.dp
private val LargeImage = 112.dp
private val SmallImage = 72.dp
private const val TIMERS_WEIGHT = 0.45f
private const val MEALS_WEIGHT = 0.55f
private const val MILLIS_PER_MINUTE = 60_000L
private const val TICK_SLACK_MILLIS = 50L
private const val DATE_PATTERN = "EEEE d MMMM"
private const val PLURAL_OTHER = 2

internal const val PART_CLOCK = "clock"
internal const val PART_TIMERS = "timers"
internal const val PART_MEALS = "meals"

internal const val KITCHEN_TAG = "kitchen"
internal const val KITCHEN_CLOSE_TAG = "kitchen:close"
internal const val KITCHEN_CLOCK_TAG = "kitchen:clock"
internal const val KITCHEN_COOK_TAG = "kitchen:cook"
internal const val KITCHEN_MEALS_TAG = "kitchen:meals"
internal const val KITCHEN_TIMERS_TAG = "kitchen:timers"
