package casa.moller.tandem.core.ui.timer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.icon.Alarm
import casa.moller.tandem.core.designsystem.icon.Pause
import casa.moller.tandem.core.designsystem.icon.PlayArrow
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Timer
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.ui.R
import kotlin.time.Duration
import kotlin.time.Instant

// Timer UI shared by cook mode and the timer list (plan 6.4 signature components `TimerChip` and
// `TimerBar`, plan 8.1 "Timer-HUD"). Stateless: the timers come from core:data's TimerController,
// the actions go back to it. Every target is at least 64 dp (plan 6.6: cook mode, wet hands).

/** What the timer buttons do, by timer id. */
@Stable
class TimerActions(
    val onPause: (Long) -> Unit = {},
    val onResume: (Long) -> Unit = {},
    val onAddMinute: (Long) -> Unit = {},
    val onCancel: (Long) -> Unit = {},
    val onStopRinging: (Long) -> Unit = {},
)

/**
 * A detected timer of a step ("Starta 5 min", Stitch kokl_ge_*): one big button that starts it.
 *
 * @param durationText what the timer runs for ("10 min", [timerDurationText]).
 * @param detail the words the step uses, when they say more than the duration ("ca 20–25 minuter").
 * @param description what TalkBack says ([timerStartDescription]).
 */
@Composable
fun TimerStartButton(
    durationText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    description: String = stringResource(R.string.ui_timer_start, durationText),
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Icon(TandemIcons.Timer, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.icon))
        Spacer(Modifier.width(TandemTheme.spacing.sm))
        // TalkBack reads the button's description, not the texts again.
        Column(Modifier.clearAndSetSemantics { }) {
            Text(text = stringResource(R.string.ui_timer_start, durationText), style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** TalkBack text of a [TimerStartButton]: "Starta en timer på 25 min, ca 20–25 minuter". */
@Composable
fun timerStartDescription(durationText: String, detail: String?): String = if (detail == null) {
    stringResource(R.string.ui_timer_start, durationText)
} else {
    stringResource(R.string.ui_timer_start_description, durationText, detail)
}

/** "1 h 15 min", "10 min", "45 s", "1 min 30 s": a timer's length, rounded up to whole seconds. */
@Composable
fun timerDurationText(duration: Duration): String {
    val parts = durationParts(duration)
    return listOfNotNull(
        parts.hours.takeIf { it > 0 }?.let { stringResource(R.string.ui_timer_hours, it.toInt()) },
        parts.minutes.takeIf { it > 0 }?.let { stringResource(R.string.ui_timer_minutes, it.toInt()) },
        parts.seconds.takeIf { it > 0 || (parts.hours == 0L && parts.minutes == 0L) }
            ?.let { stringResource(R.string.ui_timer_seconds, it.toInt()) },
    ).joinToString(" ")
}

/**
 * What TalkBack says for a timer: "Pasta · 10 min, 7 min 39 s kvar", "…, tiden är ute"; with
 * [withRecipe] (Fas 4, two recipes at once) its recipe first: "Krämig svamppasta: Pasta · 10 min, …".
 */
@Composable
fun timerDescription(timer: CookTimer, now: Instant, withRecipe: Boolean = false): String {
    val description = timerStateDescription(timer, now)
    val recipe = timer.recipeName?.takeIf { withRecipe } ?: return description
    return stringResource(R.string.ui_timer_with_recipe_description, recipe, description)
}

@Composable
private fun timerStateDescription(timer: CookTimer, now: Instant): String = when (timer.state) {
    TimerState.RUNNING -> stringResource(R.string.ui_timer_running_description, timer.label, timerDurationText(timer.remaining(now)))
    TimerState.PAUSED -> stringResource(R.string.ui_timer_paused_description, timer.label, timerDurationText(timer.remaining(now)))
    TimerState.RINGING -> stringResource(R.string.ui_timer_ringing_description, timer.label)
    TimerState.FINISHED -> stringResource(R.string.ui_timer_finished_description, timer.label)
}

/**
 * The active timers at the bottom of cook mode (plan 6.3 "Kokläge" wireframe, Stitch
 * kokl_ge_kr_mig_svamppasta_steg_3 "Aktiv timer"): one row per timer, ringing ones first and
 * prominent, then the others oldest first. More timers than fit scroll inside the bar.
 *
 * @param onOpen tapping a timer's name and time (e.g. jump to its step); null: not tappable.
 * @param maxHeight the bar never takes more than this from the step above it.
 * @param showRecipe Fas 4 (two recipes at once): each timer says its recipe ([CookTimer.recipeName]).
 */
@Composable
fun TimerBar(
    timers: List<CookTimer>,
    now: Instant,
    actions: TimerActions,
    modifier: Modifier = Modifier,
    onOpen: ((CookTimer) -> Unit)? = null,
    maxHeight: Dp = TimerBarMaxHeight,
    showRecipe: Boolean = false,
) {
    if (timers.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
    ) {
        timers.sortedForDisplay().forEach { timer ->
            TimerBarItem(
                timer = timer,
                now = now,
                actions = actions,
                onOpen = onOpen?.let { open -> { open(timer) } },
                showRecipe = showRecipe,
            )
        }
    }
}

/**
 * Ringing timers first (they need the user now), then the running and paused ones, then the
 * finished ones, each oldest first: a finished timer never pushes a running one out of a short
 * timer bar (device test 2026-11-14, B6).
 */
fun List<CookTimer>.sortedForDisplay(): List<CookTimer> =
    sortedWith(compareBy<CookTimer> { it.state.displayRank() }.thenBy { it.createdAt }.thenBy { it.id })

private fun TimerState.displayRank(): Int = when (this) {
    TimerState.RINGING -> 0
    TimerState.RUNNING, TimerState.PAUSED -> 1
    TimerState.FINISHED -> 2
}

/**
 * One row of [TimerBar]: name and time, then the timer's buttons beside them, or below them when
 * the texts would get too little of the width.
 *
 * Device test 2026-11-14 (round 3, K2): in the narrow side pane of a phone in landscape the buttons
 * left the name a strip, so it broke inside a word ("Svamp en · c…") and a ringing card stood one
 * word per line. The row now measures first: the texts stay beside the buttons only when their
 * longest word fits there and they get at least [TimerInfoMinWidth] (scaled with the font);
 * otherwise they take the card's whole width and the buttons wrap below them, aligned to the end
 * (above them while the timer rings, so Stop is the first thing in the card).
 * The measuring passes have no semantics, so TalkBack and tests only see the row that is shown.
 */
@Composable
fun TimerBarItem(
    timer: CookTimer,
    now: Instant,
    actions: TimerActions,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    showRecipe: Boolean = false,
) {
    val colors = timerColors(timer.state)
    val spacing = TandemTheme.spacing
    val fontScale = LocalDensity.current.fontScale
    // Fas 4 (two recipes at once): at a large font the buttons beside the texts leave them a strip,
    // so the recipe breaks mid-word and the time goes out of view. The texts get the card's width.
    val alwaysStacked = showRecipe && timer.recipeName != null && fontScale >= LARGE_FONT_SCALE
    val info: @Composable (Modifier) -> Unit = { infoModifier ->
        TimerInfo(
            timer = timer,
            now = now,
            colors = colors,
            timeStyle = MaterialTheme.typography.headlineSmall,
            onOpen = onOpen,
            showRecipe = showRecipe,
            modifier = infoModifier,
        )
    }
    val buttons: @Composable () -> Unit = { TimerButtons(timer = timer, actions = actions, colors = colors, showRecipe = showRecipe) }
    val infoWidths = timerInfoWidths(timer, now, showRecipe)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.container,
        contentColor = colors.content,
    ) {
        SubcomposeLayout(Modifier.padding(start = spacing.lg, end = spacing.xs)) { constraints ->
            val stacked = alwaysStacked ||
                (constraints.hasBoundedWidth && textsTooNarrow(constraints.maxWidth, TimerInfoMinWidth * fontScale, infoWidths, buttons))
            val content: @Composable () -> Unit = if (stacked) {
                {
                    val buttonRows: @Composable () -> Unit = {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            buttons()
                        }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        // A ringing timer's Stop first: in a short, narrow place at a large font the
                        // name and "Tiden är ute!" could push it out of view (K2, kokvy's B6 rule).
                        if (timer.state == TimerState.RINGING) buttonRows()
                        info(Modifier.fillMaxWidth())
                        if (timer.state != TimerState.RINGING) buttonRows()
                    }
                }
            } else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        info(Modifier.weight(1f))
                        buttons()
                    }
                }
            }
            val placeables = subcompose(if (stacked) TimerItemSlot.STACKED else TimerItemSlot.ROW, content)
                .map { it.measure(constraints.copy(minHeight = 0)) }
            val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(width, height) { placeables.forEach { it.placeRelative(0, 0) } }
        }
    }
}

/**
 * True when the texts ([info]) beside [buttons] in [maxWidth] would break a word or get less than
 * [minWidth] (or their whole width, when that is less). The buttons are measured in a probe without
 * semantics.
 */
private fun SubcomposeMeasureScope.textsTooNarrow(
    maxWidth: Int,
    minWidth: Dp,
    info: TimerInfoWidths,
    buttons: @Composable () -> Unit,
): Boolean {
    val buttonsWidth = subcompose(TimerItemSlot.PROBE_BUTTONS) {
        Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) { buttons() }
    }.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
    return maxWidth - buttonsWidth < maxOf(info.longestWord, minOf(info.whole, minWidth.roundToPx()))
}

/** The slots of [TimerBarItem]'s measuring: a probe without semantics, then the row shown. */
private enum class TimerItemSlot { PROBE_BUTTONS, ROW, STACKED }

/**
 * A timer as a card (tablet cook mode's left pane, Stitch kokl_ge_surfplatta_*, and the timer
 * list): optional [overline] ("STEG 1"), name, a big countdown, progress and the buttons.
 */
@Composable
fun TimerCard(
    timer: CookTimer,
    now: Instant,
    actions: TimerActions,
    modifier: Modifier = Modifier,
    overline: String? = null,
    onOpen: (() -> Unit)? = null,
    showRecipe: Boolean = false,
) {
    val colors = timerColors(timer.state)
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (overline != null) {
                Text(text = overline.uppercase(), style = TandemTheme.extendedTypography.overline, color = colors.accent)
            }
            TimerInfo(
                timer = timer,
                now = now,
                colors = colors,
                timeStyle = MaterialTheme.typography.displaySmall,
                onOpen = onOpen,
                showRecipe = showRecipe,
                modifier = Modifier.fillMaxWidth(),
            )
            LinearProgressIndicator(
                progress = { timer.progress(now) },
                modifier = Modifier
                    .fillMaxWidth()
                    // TimerInfo already says the time left; the bar is decoration for TalkBack
                    // (otherwise a bare percentage without the timer's name, changing every second).
                    .clearAndSetSemantics { },
                color = colors.accent,
                trackColor = colors.content.copy(alpha = TRACK_ALPHA),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TimerButtons(timer = timer, actions = actions, colors = colors, showRecipe = showRecipe)
            }
        }
    }
}

/**
 * The timer's name for a button's description: with [withRecipe] and a known recipe "Koka · 10 min
 * (Krämig svamppasta)", as the lock-screen alarm says it. Two recipes' steps are often named alike,
 * and a button must say which timer it stops.
 */
@Composable
fun timerLabel(timer: CookTimer, withRecipe: Boolean): String {
    val recipe = timer.recipeName?.takeIf { withRecipe } ?: return timer.label
    return stringResource(R.string.ui_timer_label_with_recipe, timer.label, recipe)
}

/** How far the timer has run, 0..1 (1 once it rang). */
fun CookTimer.progress(now: Instant): Float = when (state) {
    TimerState.RINGING, TimerState.FINISHED -> 1f
    else -> (1.0 - remaining(now) / duration).toFloat().coerceIn(0f, 1f)
}

/** Name and time as one TalkBack item; a ringing timer is announced. */
@Composable
private fun TimerInfo(
    timer: CookTimer,
    now: Instant,
    colors: TimerColors,
    timeStyle: TextStyle,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showRecipe: Boolean = false,
) {
    val recipe = timer.recipeName?.takeIf { showRecipe }
    val description = timerDescription(timer, now, withRecipe = recipe != null)
    val openLabel = stringResource(R.string.ui_timer_open_step)
    Column(
        modifier = modifier
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (timer.state == TimerState.RINGING) liveRegion = LiveRegionMode.Assertive
            }
            .padding(vertical = TandemTheme.spacing.sm),
        verticalArrangement = Arrangement.Center,
    ) {
        if (recipe != null) {
            // Fas 4 (two recipes at once): which recipe the timer belongs to, above its name.
            Text(
                text = recipe,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
                // Two lines at a large font: names that start alike must stay apart. The bar's card
                // gives the name its full width there (TimerBarItem).
                maxLines = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs)) {
            Icon(
                imageVector = timer.state.icon(),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(TandemTheme.sizes.iconSmall),
            )
            Text(
                text = timer.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A paused timer says so on the same line ("3:12 · Pausad"), so the row keeps its height.
        Text(
            text = timerTimeText(timer, now),
            style = timeStyle.timeText(),
            color = if (timer.state == TimerState.RUNNING) colors.accent else colors.content,
        )
    }
}

/** The big line of a timer: the time left ("3:12", "3:12 · Pausad"), or that it rang or finished. */
@Composable
private fun timerTimeText(timer: CookTimer, now: Instant): String = when (timer.state) {
    TimerState.RINGING -> stringResource(R.string.ui_timer_ringing)
    TimerState.FINISHED -> stringResource(R.string.ui_timer_finished)
    TimerState.PAUSED -> stringResource(R.string.ui_timer_paused_time, formatRemaining(timer.remaining(now)))
    TimerState.RUNNING -> formatRemaining(timer.remaining(now))
}

/** [timerTimeText]'s style: bold, digits of equal width. */
private fun TextStyle.timeText(): TextStyle = copy(fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR_NUMBERS)

/**
 * The widths [TimerInfo] needs on one line, in pixels: [longestWord] its widest word (with the
 * state icon in front of the name's), so nothing breaks inside a word, and [whole] every text whole.
 * Measured with the texts' own styles (an intrinsic measurement would count a word's trailing space).
 */
private data class TimerInfoWidths(val longestWord: Int, val whole: Int)

@Composable
private fun timerInfoWidths(timer: CookTimer, now: Instant, showRecipe: Boolean): TimerInfoWidths {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelLarge
    val recipeStyle = MaterialTheme.typography.labelMedium
    val timeStyle = MaterialTheme.typography.headlineSmall.timeText()
    val recipe = timer.recipeName?.takeIf { showRecipe }
    val time = timerTimeText(timer, now)
    val iconAndGap = with(density) { (TandemTheme.sizes.iconSmall + TandemTheme.spacing.xs).roundToPx() }
    return remember(timer.label, recipe, time, labelStyle, recipeStyle, timeStyle, density, measurer, iconAndGap) {
        fun width(text: String, style: TextStyle) = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width
        fun longest(text: String, style: TextStyle) = text.split(WORD_GAP).filter { it.isNotEmpty() }.maxOfOrNull { width(it, style) } ?: 0
        TimerInfoWidths(
            longestWord = maxOf(
                iconAndGap + longest(timer.label, labelStyle),
                recipe?.let { longest(it, recipeStyle) } ?: 0,
                longest(time, timeStyle),
            ),
            whole = maxOf(iconAndGap + width(timer.label, labelStyle), recipe?.let { width(it, recipeStyle) } ?: 0, width(time, timeStyle)),
        )
    }
}

private val WORD_GAP = Regex("\\s+")

/** The buttons of one timer, by state: +1 min, pause/resume and remove; Stop while it rings. */
@Composable
private fun TimerButtons(timer: CookTimer, actions: TimerActions, colors: TimerColors, showRecipe: Boolean = false) {
    val id = timer.id
    val label = timerLabel(timer, withRecipe = showRecipe)
    if (timer.state != TimerState.FINISHED) {
        PlusOneMinuteButton(label = label, color = colors.content, onClick = { actions.onAddMinute(id) })
    }
    when (timer.state) {
        // One call site for pause and resume: the button node survives the toggle, so TalkBack
        // focus stays on it and reads the new description.
        TimerState.RUNNING, TimerState.PAUSED -> {
            val running = timer.state == TimerState.RUNNING
            KitchenIconButton(
                icon = if (running) TandemIcons.Pause else TandemIcons.PlayArrow,
                description = stringResource(if (running) R.string.ui_timer_pause_for else R.string.ui_timer_resume_for, label),
                color = colors.content,
                onClick = { if (running) actions.onPause(id) else actions.onResume(id) },
            )
        }

        TimerState.RINGING -> {
            val stopFor = stringResource(R.string.ui_timer_stop_for, label)
            Button(
                onClick = { actions.onStopRinging(id) },
                modifier = Modifier
                    .padding(horizontal = TandemTheme.spacing.xs)
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                    .semantics { contentDescription = stopFor },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                // The button's description already says it ("Stoppa larmet för Ugnen").
                Text(
                    text = stringResource(R.string.ui_timer_stop),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }

        TimerState.FINISHED -> Unit
    }
    if (timer.state != TimerState.RINGING) {
        KitchenIconButton(
            icon = TandemIcons.Close,
            description = stringResource(R.string.ui_timer_cancel_for, label),
            color = colors.content,
            onClick = { actions.onCancel(id) },
        )
    }
}

@Composable
private fun PlusOneMinuteButton(label: String, color: Color, onClick: () -> Unit) {
    val description = stringResource(R.string.ui_timer_plus_one_minute_for, label)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .widthIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(
            text = stringResource(R.string.ui_timer_plus_one_minute),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** An icon button with a 64 dp target (cook mode, plan 6.6). */
@Composable
fun KitchenIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(TandemTheme.sizes.kitchenTouchTarget),
        colors = IconButtonDefaults.iconButtonColors(contentColor = color),
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(TandemTheme.sizes.iconLarge))
    }
}

@Composable
private fun TimerState.icon(): ImageVector = when (this) {
    TimerState.RINGING -> TandemIcons.Alarm
    TimerState.PAUSED -> TandemIcons.Pause
    TimerState.RUNNING, TimerState.FINISHED -> TandemIcons.Timer
}

/** Colour roles of a timer by state: ringing is the error container, running the inverse surface. */
@Immutable
private data class TimerColors(val container: Color, val content: Color, val accent: Color)

@Composable
private fun timerColors(state: TimerState): TimerColors {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        TimerState.RINGING -> TimerColors(scheme.errorContainer, scheme.onErrorContainer, scheme.onErrorContainer)
        TimerState.RUNNING -> TimerColors(scheme.inverseSurface, scheme.inverseOnSurface, scheme.inversePrimary)
        TimerState.PAUSED, TimerState.FINISHED -> TimerColors(scheme.surfaceContainerHighest, scheme.onSurface, scheme.primary)
    }
}

/** The bar takes at most about two and a half rows from the step above it. */
private val TimerBarMaxHeight = 216.dp

private const val TRACK_ALPHA = 0.24f

/** Digits of equal width, so a countdown does not jitter. */
private const val TABULAR_NUMBERS = "tnum"

/**
 * A timer row keeps its name and time beside the buttons only when they get at least this much of
 * the width (times the font scale), else they stand above the buttons (K2, device test 2026-11-14).
 */
internal val TimerInfoMinWidth = 96.dp

/** From this font scale a recipe's name may take two lines on a timer. */
private const val LARGE_FONT_SCALE = 1.5f
