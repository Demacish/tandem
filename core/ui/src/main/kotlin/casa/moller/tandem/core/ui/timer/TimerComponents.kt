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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** What TalkBack says for a timer: "Pasta · 10 min, 7 min 39 s kvar", "…, tiden är ute". */
@Composable
fun timerDescription(timer: CookTimer, now: Instant): String = when (timer.state) {
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
 */
@Composable
fun TimerBar(
    timers: List<CookTimer>,
    now: Instant,
    actions: TimerActions,
    modifier: Modifier = Modifier,
    onOpen: ((CookTimer) -> Unit)? = null,
    maxHeight: Dp = TimerBarMaxHeight,
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
            TimerBarItem(timer = timer, now = now, actions = actions, onOpen = onOpen?.let { open -> { open(timer) } })
        }
    }
}

/** Ringing timers first (they need the user now), then the others oldest first. */
fun List<CookTimer>.sortedForDisplay(): List<CookTimer> =
    sortedWith(compareBy<CookTimer> { if (it.state == TimerState.RINGING) 0 else 1 }.thenBy { it.createdAt }.thenBy { it.id })

/** One row of [TimerBar]: name and time, then the timer's buttons. */
@Composable
fun TimerBarItem(timer: CookTimer, now: Instant, actions: TimerActions, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val colors = timerColors(timer.state)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(start = TandemTheme.spacing.lg, end = TandemTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimerInfo(
                timer = timer,
                now = now,
                colors = colors,
                timeStyle = MaterialTheme.typography.headlineSmall,
                onOpen = onOpen,
                modifier = Modifier.weight(1f),
            )
            TimerButtons(timer = timer, actions = actions, colors = colors)
        }
    }
}

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
                modifier = Modifier.fillMaxWidth(),
            )
            LinearProgressIndicator(
                progress = { timer.progress(now) },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.content.copy(alpha = TRACK_ALPHA),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TimerButtons(timer = timer, actions = actions, colors = colors)
            }
        }
    }
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
) {
    val description = timerDescription(timer, now)
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
        Text(
            text = when (timer.state) {
                TimerState.RINGING -> stringResource(R.string.ui_timer_ringing)
                TimerState.FINISHED -> stringResource(R.string.ui_timer_finished)
                else -> formatRemaining(timer.remaining(now))
            },
            style = timeStyle.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR_NUMBERS),
            color = if (timer.state == TimerState.RUNNING) colors.accent else colors.content,
        )
        if (timer.state == TimerState.PAUSED) {
            Text(text = stringResource(R.string.ui_timer_paused), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The buttons of one timer, by state: +1 min, pause/resume and remove; Stop while it rings. */
@Composable
private fun TimerButtons(timer: CookTimer, actions: TimerActions, colors: TimerColors) {
    val id = timer.id
    val label = timer.label
    if (timer.state != TimerState.FINISHED) {
        PlusOneMinuteButton(label = label, color = colors.content, onClick = { actions.onAddMinute(id) })
    }
    when (timer.state) {
        TimerState.RUNNING -> KitchenIconButton(
            icon = TandemIcons.Pause,
            description = stringResource(R.string.ui_timer_pause_for, label),
            color = colors.content,
            onClick = { actions.onPause(id) },
        )

        TimerState.PAUSED -> KitchenIconButton(
            icon = TandemIcons.PlayArrow,
            description = stringResource(R.string.ui_timer_resume_for, label),
            color = colors.content,
            onClick = { actions.onResume(id) },
        )

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
