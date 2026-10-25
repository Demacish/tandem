package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Remove
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Timer
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.TimerSuggestion
import casa.moller.tandem.core.ui.timer.KitchenIconButton
import casa.moller.tandem.core.ui.timer.timerDurationText
import casa.moller.tandem.feature.cook.R
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * "Justera tiden": a tapped timer button or duration in the text asks for the time before the
 * timer starts, since recipes often give a range ("20–25 min") or a time that depends on the
 * stove. Opens with the suggested time (the longer end of a range); − and + change it by
 * [adjustStep], and a range offers both of its ends. "Starta" starts the timer with the chosen
 * time; [onStart] gets the chosen time as text for the timer's label.
 */
@Composable
internal fun TimerAdjustDialog(
    suggestion: TimerSuggestion,
    duration: Duration,
    onChange: (Duration) -> Unit,
    onStart: (durationText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = TandemTheme.spacing
    val durationText = timerDurationText(duration)
    val suggestedText = timerDurationText(suggestion.duration)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TIMER_ADJUST_TAG),
        icon = { Icon(TandemIcons.Timer, contentDescription = null) },
        title = { Text(stringResource(R.string.cook_timer_adjust_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                // The recipe's own words when they say more than the time ("ca 20–25 minuter").
                if (suggestion.label != suggestedText) {
                    Text(
                        text = stringResource(R.string.cook_timer_adjust_recipe, suggestion.label),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    val shorter = adjusted(duration, longer = false)
                    val longer = adjusted(duration, longer = true)
                    KitchenIconButton(
                        icon = TandemIcons.Remove,
                        description = stringResource(R.string.cook_timer_adjust_shorter, timerDurationText(duration - shorter)),
                        onClick = { onChange(shorter) },
                        enabled = shorter < duration,
                    )
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(min = DURATION_MIN_WIDTH)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    KitchenIconButton(
                        icon = TandemIcons.Add,
                        description = stringResource(R.string.cook_timer_adjust_longer, timerDurationText(longer - duration)),
                        onClick = { onChange(longer) },
                        enabled = longer > duration,
                    )
                }
                if (suggestion.isRange) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf(suggestion.minDuration, suggestion.duration).forEach { end ->
                            FilterChip(
                                selected = duration == end,
                                onClick = { onChange(end) },
                                label = { Text(timerDurationText(end)) },
                                modifier = Modifier.heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TandemPrimaryButton(
                text = stringResource(R.string.cook_timer_adjust_start, durationText),
                onClick = { onStart(durationText) },
                size = TandemButtonSize.Kitchen,
            )
        },
        dismissButton = {
            TandemSecondaryButton(
                text = stringResource(R.string.cook_timer_adjust_cancel),
                onClick = onDismiss,
                size = TandemButtonSize.Kitchen,
            )
        },
    )
}

/** How much − and + change [duration]: 10 s under 2 min, 1 min under 30 min, otherwise 5 min. */
internal fun adjustStep(duration: Duration): Duration = when {
    duration < 2.minutes -> 10.seconds
    duration < 30.minutes -> 1.minutes
    else -> 5.minutes
}

/**
 * [duration] one step shorter or longer, between [MIN_TIMER] and [MAX_TIMER]. Going down uses the
 * step just below the current time, so 30 min − 1 step is 29 min, not 25 min.
 */
internal fun adjusted(duration: Duration, longer: Boolean): Duration {
    val next = if (longer) duration + adjustStep(duration) else duration - adjustStep(duration - 1.seconds)
    return next.coerceIn(MIN_TIMER, MAX_TIMER)
}

internal val MIN_TIMER: Duration = 10.seconds
internal val MAX_TIMER: Duration = 24.hours - 1.minutes
internal const val TIMER_ADJUST_TAG = "cook:timer-adjust"
private val DURATION_MIN_WIDTH = 120.dp
