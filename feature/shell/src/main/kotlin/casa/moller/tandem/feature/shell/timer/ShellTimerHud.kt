package casa.moller.tandem.feature.shell.timer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import casa.moller.tandem.core.designsystem.icon.Alarm
import casa.moller.tandem.core.designsystem.icon.Pause
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Timer
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.ui.timer.formatRemaining
import casa.moller.tandem.core.ui.timer.rememberTimerNow
import casa.moller.tandem.core.ui.timer.sortedForDisplay
import casa.moller.tandem.core.ui.timer.timerDescription
import casa.moller.tandem.feature.shell.R
import kotlin.time.Instant

// Phase 1b (inställningar lane): the compact timer bar the shell shows on every tab while timers
// run (plan 8.1 "Timer-HUD"), built on core:ui's timer helpers from the kokläge lane.

/** Test tag of the timer bar. */
internal const val TIMER_HUD_TAG = "shell_timer_hud"

/** The timers the bar is about: every one that is not finished, ringing ones first, then oldest. */
internal fun List<CookTimer>.forHud(): List<CookTimer> = filter { it.state != TimerState.FINISHED }.sortedForDisplay()

/**
 * [content] (the tabs) with the timer bar below it while [timers] has a running, paused or
 * ringing timer and [visible] (not in cook mode or the timer list, which show the timers
 * themselves). The bar keeps clear of the navigation bar or keyboard when the shell's own bottom
 * bar is hidden, and the content does not pad for them twice.
 */
@Composable
internal fun TimerHudFrame(
    timers: List<CookTimer>,
    visible: Boolean,
    onOpenTimer: (CookTimer) -> Unit,
    onOpenAll: () -> Unit,
    onStop: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    val shown = remember(timers) { timers.forHud() }
    val showHud = visible && shown.isNotEmpty()
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .then(if (showHud) Modifier.consumeWindowInsets(bottom) else Modifier),
        ) {
            content()
        }
        if (showHud) {
            ShellTimerHud(
                timers = shown,
                onOpenTimer = onOpenTimer,
                onOpenAll = onOpenAll,
                onStop = onStop,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
        }
    }
}

/**
 * One slim row: the most urgent timer (a ringing one, else the oldest) with its name and
 * countdown, "+2" for the other timers (opens the timer list) and "Stop" while it rings. Tapping
 * the timer opens cook mode at its recipe and step. TalkBack reads the timer as one button
 * ("Pasta · 10 min, 7 min 39 s left"); the countdown itself is not announced every second.
 *
 * @param timers what [forHud] returns, never empty.
 */
@Composable
internal fun ShellTimerHud(
    timers: List<CookTimer>,
    onOpenTimer: (CookTimer) -> Unit,
    onOpenAll: () -> Unit,
    onStop: (Long) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0),
) {
    val primary = timers.first()
    val now by rememberTimerNow()
    val ringing = primary.state == TimerState.RINGING
    val scheme = MaterialTheme.colorScheme
    // Like core:ui's timer bar: ringing is the error container, otherwise the inverse surface.
    val container = if (ringing) scheme.errorContainer else scheme.inverseSurface
    val onContainer = if (ringing) scheme.onErrorContainer else scheme.inverseOnSurface
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TIMER_HUD_TAG),
        color = container,
        contentColor = onContainer,
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(windowInsets)
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            val description = timerDescription(primary, now)
            val openLabel = stringResource(R.string.timer_hud_open)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TandemTheme.sizes.minTouchTarget)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClickLabel = openLabel, role = Role.Button) { onOpenTimer(primary) }
                    .semantics { contentDescription = description }
                    .padding(horizontal = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    imageVector = when (primary.state) {
                        TimerState.RINGING -> TandemIcons.Alarm
                        TimerState.PAUSED -> TandemIcons.Pause
                        TimerState.RUNNING, TimerState.FINISHED -> TandemIcons.Timer
                    },
                    contentDescription = null,
                    modifier = Modifier.size(TandemTheme.sizes.icon),
                )
                Text(
                    text = hudText(primary, now),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The button's description says it (and does not change every second).
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            if (timers.size > 1) {
                val others = timers.size - 1
                val allDescription = stringResource(R.string.timer_hud_show_all, timers.size)
                TextButton(
                    onClick = onOpenAll,
                    colors = ButtonDefaults.textButtonColors(contentColor = onContainer),
                    modifier = Modifier
                        .heightIn(min = TandemTheme.sizes.minTouchTarget)
                        .semantics { contentDescription = allDescription },
                ) {
                    Text(text = stringResource(R.string.timer_hud_more, others), style = MaterialTheme.typography.titleSmall)
                }
            }
            if (ringing) {
                val stopDescription = stringResource(R.string.timer_hud_stop_description, primary.label)
                // Filled in the bar's own colours, reversed: clearly the one thing to press.
                Button(
                    onClick = { onStop(primary.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = onContainer, contentColor = container),
                    modifier = Modifier
                        .heightIn(min = TandemTheme.sizes.minTouchTarget)
                        .semantics { contentDescription = stopDescription },
                ) {
                    Text(text = stringResource(R.string.timer_hud_stop))
                }
            }
        }
    }
}

/** "Pasta · 7:39", "Pasta · paused at 7:39", "Pasta · time is up!". */
@Composable
private fun hudText(timer: CookTimer, now: Instant): String = when (timer.state) {
    TimerState.RUNNING -> stringResource(R.string.timer_hud_running, timer.label, formatRemaining(timer.remaining(now)))
    TimerState.PAUSED -> stringResource(R.string.timer_hud_paused, timer.label, formatRemaining(timer.remaining(now)))
    TimerState.RINGING, TimerState.FINISHED -> stringResource(R.string.timer_hud_ringing, timer.label)
}
