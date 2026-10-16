package casa.moller.tandem.core.ui.timer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.delay

/**
 * The wall clock the timer UI counts down with (the same clock as the timers' `endsAt`, plan
 * "Fas 1b – kontrakt"). Screenshot and UI tests provide a fixed one.
 */
val LocalTimerClock = staticCompositionLocalOf<Clock> { Clock.System }

/**
 * False stops the once-per-second tick of [rememberTimerNow] (tests: a fixed picture, and no
 * endless coroutine that keeps the test from becoming idle).
 */
val LocalTimerTicks = staticCompositionLocalOf { true }

/**
 * "Now" for the countdowns on screen, updated just after every full second while the caller is
 * composed (nothing ticks when no timer is shown).
 */
@Composable
fun rememberTimerNow(): State<Instant> {
    val clock = LocalTimerClock.current
    val ticks = LocalTimerTicks.current
    val now = remember(clock) { mutableStateOf(clock.now()) }
    if (ticks) {
        LaunchedEffect(clock) {
            while (true) {
                val current = clock.now()
                now.value = current
                delay(MILLIS_PER_SECOND - current.toEpochMilliseconds().mod(MILLIS_PER_SECOND) + TICK_SLACK_MILLIS)
            }
        }
    }
    return now
}

/**
 * A countdown as the clock shows it: "7:39", "0:05", "1:02:03". Rounded UP to whole seconds, so a
 * running timer never shows 0:00 before it rings.
 */
fun formatRemaining(remaining: Duration): String {
    val totalSeconds = ceil(remaining.coerceAtLeast(Duration.ZERO).inWholeMilliseconds / MILLIS_PER_SECOND.toDouble()).toLong()
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
    } else {
        "$minutes:${seconds.twoDigits()}"
    }
}

/** Hours, minutes and seconds of [duration] rounded up to whole seconds (for spoken texts). */
internal data class DurationParts(val hours: Long, val minutes: Long, val seconds: Long)

internal fun durationParts(duration: Duration): DurationParts {
    val totalSeconds = ceil(duration.coerceAtLeast(Duration.ZERO).inWholeMilliseconds / MILLIS_PER_SECOND.toDouble()).toLong()
    return DurationParts(
        hours = totalSeconds / SECONDS_PER_HOUR,
        minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
        seconds = totalSeconds % SECONDS_PER_MINUTE,
    )
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Wake up a little after the second turns, so the display never lags a whole second. */
private const val TICK_SLACK_MILLIS = 10L
