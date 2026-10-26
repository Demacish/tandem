package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.feature.mealplan.MealPlanFormat
import casa.moller.tandem.feature.mealplan.R
import casa.moller.tandem.feature.mealplan.WeekAction
import casa.moller.tandem.feature.mealplan.WeekUiState
import kotlinx.datetime.LocalDate

/** Test tags of the three layouts and the day pane. */
const val WEEK_AGENDA_TAG = "week:agenda"
const val WEEK_DAYS_TAG = "week:days"
const val WEEK_DAY_PANE_TAG = "week:day"
const val WEEK_GRID_TAG = "week:grid"

/** Test tag of a day's section or column: `day:<yyyy-MM-dd>`. */
fun dayTag(date: LocalDate): String = "day:$date"

private val GridTypeColumn = 112.dp
private val GridMinCell = 128.dp
private val TwoPaneDaysWidth = 300.dp

/**
 * Phones: the week header, then a section per day, scrolled so that today is first in this week
 * (plan 6.2 "Veckan": agenda, 7 days per page), and "Handla för veckan" at the end. The guest
 * note and the empty-week note stand fixed above the days (fix-ui: scrolling to today hid them).
 */
@Composable
internal fun WeekAgenda(state: WeekUiState, format: MealPlanFormat, onAction: (WeekAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val todayIndex = state.days.indexOfFirst { it.date == state.today }
    val listState = rememberSaveable(state.weekStart, saver = LazyListState.Saver) {
        LazyListState(firstVisibleItemIndex = todayIndex.coerceAtLeast(0))
    }
    Column(modifier = modifier.fillMaxSize()) {
        WeekHeader(state = state, format = format, onAction = onAction, modifier = Modifier.padding(horizontal = spacing.sm))
        val notePadding = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs)
        if (state.readOnly) ReadOnlyNote(modifier = notePadding)
        if (state.isEmpty) EmptyWeekNote(readOnly = state.readOnly, modifier = notePadding)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(WEEK_AGENDA_TAG),
            contentPadding = PaddingValues(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            items(state.days, key = { it.date.toString() }) { day ->
                DaySection(day = day, state = state, format = format, onAction = onAction)
            }
            if (showWeekShopping(state)) item(key = "week-shopping") { WeekShoppingCard(onClick = { onAction(WeekAction.WeekShopping) }) }
        }
    }
}

/**
 * Phones in landscape and other windows too narrow or too low for the grid (plan 6.5,
 * list-detail): the days of the week with their meals' names beside the chosen day (today by
 * default) with its cards.
 */
@Composable
internal fun WeekTwoPane(state: WeekUiState, format: MealPlanFormat, onAction: (WeekAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val shown = state.shownDay
    Row(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.width(TwoPaneDaysWidth).fillMaxSize()) {
            WeekHeader(state = state, format = format, onAction = onAction, modifier = Modifier.padding(horizontal = spacing.sm))
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(WEEK_DAYS_TAG),
                contentPadding = PaddingValues(start = spacing.lg, end = spacing.sm, top = spacing.sm, bottom = spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (state.readOnly) item(key = "read-only") { ReadOnlyNote() }
                items(state.days, key = { it.date.toString() }) { day ->
                    DayRow(day = day, today = day.date == state.today, selected = day.date == shown, format = format, onAction = onAction)
                }
                if (showWeekShopping(state)) {
                    item(key = "week-shopping") { WeekShoppingCard(onClick = { onAction(WeekAction.WeekShopping) }) }
                }
            }
        }
        val day = state.days.firstOrNull { it.date == shown }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize().testTag(WEEK_DAY_PANE_TAG),
            contentPadding = PaddingValues(start = spacing.lg, end = spacing.xl, top = spacing.lg, bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            if (state.isEmpty) item(key = "empty") { EmptyWeekNote(readOnly = state.readOnly) }
            if (day != null) item(key = day.date.toString()) { DaySection(day = day, state = state, format = format, onAction = onAction) }
        }
    }
}

/**
 * Tablets in both orientations (plan 6.5, §10 "veckorutnätet … i båda orienteringarna", Stitch
 * veckan_matsedel_surfplatta): days as columns, meal types as rows, today's column marked, "+" in
 * every empty cell. Every column stays readable: on a narrow window (a tablet in portrait) the grid
 * scrolls sideways instead of clipping Sunday (docs/design/README.md), and opens with today's
 * column in view. The day headings stay at the top while the meal types scroll under them, and
 * scroll sideways with the cells (fix-ui).
 */
@Composable
internal fun WeekGrid(state: WeekUiState, format: MealPlanFormat, onAction: (WeekAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = spacing.sm)) {
            WeekHeader(state = state, format = format, onAction = onAction, modifier = Modifier.weight(1f))
            if (showWeekShopping(state)) {
                WeekShoppingCard(
                    onClick = { onAction(WeekAction.WeekShopping) },
                    modifier = Modifier.width(360.dp).padding(end = spacing.lg),
                )
            }
        }
        if (state.readOnly) ReadOnlyNote(modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs))
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().testTag(WEEK_GRID_TAG)) {
            val shown = maxWidth - GridTypeColumn - spacing.lg * 2
            val cell = maxOf(shown / state.days.size.coerceAtLeast(1), GridMinCell)
            val rows = gridRows(state)
            // Today's column in view when the week is wider than the window (portrait).
            val todayIndex = state.days.indexOfFirst { it.date == state.today }
            val todayOffset = with(LocalDensity.current) { (cell * (todayIndex + 1) - shown).coerceAtLeast(0.dp).roundToPx() }
            val sideways = rememberSaveable(state.weekStart, saver = ScrollState.Saver) { ScrollState(todayOffset) }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .horizontalScroll(sideways)
                        .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.mealplan_meal_type_column),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(GridTypeColumn).padding(spacing.sm),
                    )
                    state.days.forEach { day -> GridDayHead(day.date, today = day.date == state.today, format = format, width = cell) }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(sideways)
                        .padding(horizontal = spacing.lg, vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    GridBody(state = state, rows = rows, format = format, onAction = onAction, cell = cell)
                }
            }
        }
    }
}

/** The grid's rows (one per meal type), under the fixed day headings. */
@Composable
private fun GridBody(state: WeekUiState, rows: List<MealType>, format: MealPlanFormat, onAction: (WeekAction) -> Unit, cell: Dp) {
    rows.forEach { type ->
        Row {
            GridTypeCell(type = type, width = GridTypeColumn)
            state.days.forEach { day ->
                GridCell(
                    day = day,
                    type = type,
                    isToday = day.date == state.today,
                    state = state,
                    format = format,
                    onAction = onAction,
                    width = cell,
                )
            }
        }
    }
}

/** The grid's rows: the space's meal types, plus any a planned meal has that is not stored (yet). */
private fun gridRows(state: WeekUiState): List<MealType> {
    val planned = state.days.flatMap { day -> day.meals.map { it.mealType } }
    return (state.mealTypes + planned.filter { meal -> state.mealTypes.none { it.id == meal.id } }.distinctBy { it.id })
        .sortedWith(compareBy({ it.order }, { it.id }))
}

private fun showWeekShopping(state: WeekUiState): Boolean = !state.readOnly && state.days.any { day -> day.meals.any { it.recipe != null } }

/** "Vecka 43 · 21–27 oktober" with previous/next, "Idag" outside this week, and the planned days. */
@Composable
internal fun WeekHeader(state: WeekUiState, format: MealPlanFormat, onAction: (WeekAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val resources = LocalResources.current
    Column(modifier = modifier.padding(vertical = spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAction(WeekAction.PreviousWeek) }) {
                Icon(
                    imageVector = TandemIcons.ChevronRight,
                    contentDescription = stringResource(R.string.mealplan_previous_week),
                    modifier = Modifier.rotate(180f),
                )
            }
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Text(
                    text = stringResource(R.string.mealplan_week_number, format.weekNumber(state.weekStart)),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = format.range(state.weekStart, state.weekEnd, resources),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.isThisWeek) {
                val description = stringResource(R.string.mealplan_this_week_description)
                TandemTextButton(
                    text = stringResource(R.string.mealplan_this_week),
                    onClick = { onAction(WeekAction.ThisWeek) },
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
            IconButton(onClick = { onAction(WeekAction.NextWeek) }) {
                Icon(imageVector = TandemIcons.ChevronRight, contentDescription = stringResource(R.string.mealplan_next_week))
            }
        }
        val planned = pluralStringResource(R.plurals.mealplan_planned_days, state.plannedDays, state.plannedDays)
        Column(
            modifier = Modifier
                .padding(horizontal = spacing.md)
                .clearAndSetSemantics { contentDescription = planned },
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(text = planned, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { state.plannedDays / state.days.size.coerceAtLeast(1).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A day of the agenda: its heading ("Torsdag 24 oktober", "Idag"), "+" and its meals (or "Inget planerat"). */
@Composable
internal fun DaySection(
    day: MealPlanDay,
    state: WeekUiState,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val isToday = day.date == state.today
    val dayName = format.fullDay(day.date)
    Column(modifier = modifier.fillMaxWidth().testTag(dayTag(day.date)), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            val headingText = if (isToday) stringResource(R.string.mealplan_today_heading, dayName) else dayName
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics {
                        heading()
                        contentDescription = headingText
                    },
                )
                if (isToday) {
                    StatusChip(
                        label = stringResource(R.string.mealplan_today),
                        tone = TandemTone.Accent,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
            if (!state.readOnly) {
                IconButton(onClick = { onAction(WeekAction.PlanMeal(day.date)) }) {
                    val label = stringResource(R.string.mealplan_plan_on_day, format.fullDayInText(day.date))
                    Icon(TandemIcons.Add, contentDescription = label)
                }
            }
        }
        if (day.meals.isEmpty()) {
            EmptyDay()
        } else {
            day.meals.forEach { meal ->
                MealCard(meal = meal, isToday = isToday, readOnly = state.readOnly, format = format, onAction = onAction)
            }
        }
    }
}

/** A day in the two-pane list: its name and its meals' names; selecting it shows it beside. */
@Composable
private fun DayRow(day: MealPlanDay, today: Boolean, selected: Boolean, format: MealPlanFormat, onAction: (WeekAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val dayName = format.fullDay(day.date)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(dayTag(day.date))
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.Tab, onClick = { onAction(WeekAction.SelectDay(day.date)) }),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (today) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget).padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                text = if (today) stringResource(R.string.mealplan_today_heading, dayName) else dayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val meals = day.meals.map { it.name() }
            Text(
                text = if (meals.isEmpty()) stringResource(R.string.mealplan_nothing_planned) else meals.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GridDayHead(date: LocalDate, today: Boolean, format: MealPlanFormat, width: Dp) {
    val spacing = TandemTheme.spacing
    val fullDay = format.fullDay(date)
    val description = if (today) stringResource(R.string.mealplan_today_heading, fullDay) else fullDay
    Surface(
        modifier = Modifier
            .width(width)
            .padding(horizontal = spacing.xs)
            .testTag(dayTag(date))
            .clearAndSetSemantics {
                heading()
                contentDescription = description
            },
        shape = MaterialTheme.shapes.large,
        color = if (today) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (today) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
            if (today) {
                Text(
                    text = stringResource(R.string.mealplan_today),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(text = format.shortWeekday(date), style = MaterialTheme.typography.labelLarge)
            Text(text = format.dayOfMonth(date), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GridTypeCell(type: MealType, width: Dp) {
    Text(
        text = type.name,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .width(width)
            .padding(TandemTheme.spacing.sm)
            .semantics { heading() },
    )
}

@Composable
private fun GridCell(
    day: MealPlanDay,
    type: MealType,
    isToday: Boolean,
    state: WeekUiState,
    format: MealPlanFormat,
    onAction: (WeekAction) -> Unit,
    width: Dp,
) {
    val spacing = TandemTheme.spacing
    val meals = day.meals.filter { it.mealType.id == type.id }
    Column(
        modifier = Modifier.width(width).padding(horizontal = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        meals.forEach { meal ->
            MealTile(
                meal = meal,
                date = day.date,
                isToday = isToday,
                readOnly = state.readOnly,
                format = format,
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (meals.isEmpty() && !state.readOnly) {
            IconButton(onClick = { onAction(WeekAction.PlanMeal(day.date, type.id)) }) {
                Icon(
                    imageVector = TandemIcons.Add,
                    contentDescription = stringResource(R.string.mealplan_plan_on_day_type, type.name, format.fullDayInText(day.date)),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyDay() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = stringResource(R.string.mealplan_nothing_planned),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(TandemTheme.spacing.lg),
        )
    }
}

@Composable
private fun EmptyWeekNote(readOnly: Boolean, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = stringResource(R.string.mealplan_empty_week_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(if (readOnly) R.string.mealplan_empty_week_guest else R.string.mealplan_empty_week_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReadOnlyNote(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = stringResource(R.string.mealplan_read_only),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(TandemTheme.spacing.md),
        )
    }
}

/** "Handla för veckan" (Stitch veckan_matsedel: the card at the end of the week). */
@Composable
private fun WeekShoppingCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget).padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(text = stringResource(R.string.mealplan_week_shopping_title), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.mealplan_week_shopping_card_body), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(TandemIcons.ChevronRight, contentDescription = null)
        }
    }
}
