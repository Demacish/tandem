package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.containerColor
import casa.moller.tandem.core.designsystem.component.contentColor
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.ui.timer.TimerActions
import casa.moller.tandem.core.ui.timer.TimerBarItem
import casa.moller.tandem.core.ui.timer.sortedForDisplay
import casa.moller.tandem.feature.cook.R
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Instant

// Device test 2026-11-14 (B6, B10): cook mode's timer area and page buttons on a small phone.
// A ringing timer and its Stop stand above everything else of the timer area, the warnings shrink
// to one line while something rings, the step keeps a minimum height, and a page button that
// does not fit its label drops the icon, then the label (never a word broken in two).

/**
 * While a timer rings, the alarm warnings ([AlarmWarnings]) are one line each that opens the whole
 * warning: the ringing timer's Stop must be in view, and the warning can wait (B6).
 */
internal val LocalWarningsCollapsed = compositionLocalOf { false }

/** True when [timers] has one that rings now. */
internal fun List<CookTimer>.anyRinging(): Boolean = any { it.state == TimerState.RINGING }

/**
 * The ringing timers, each with Stop and +1 min, outside any scrolling: nothing above them in the
 * timer area, so they are never scrolled or pushed out of view. Draws nothing when none rings.
 */
@Composable
internal fun RingingTimers(
    timers: List<CookTimer>,
    now: Instant,
    actions: TimerActions,
    onOpen: (CookTimer) -> Unit,
    showRecipe: Boolean,
    modifier: Modifier = Modifier,
) {
    val ringing = timers.filter { it.state == TimerState.RINGING }.sortedForDisplay()
    if (ringing.isEmpty()) return
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Several ringing at once on a short window: they scroll among themselves.
            .verticalScroll(rememberScrollState())
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm)
            .testTag(COOK_RINGING_TIMERS_TAG),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        ringing.forEach { timer ->
            TimerBarItem(timer = timer, now = now, actions = actions, onOpen = { onOpen(timer) }, showRecipe = showRecipe)
        }
    }
}

/**
 * A warning folded to one line while a timer rings: the warning icon, its title and a chevron.
 * The whole line is one 64 dp button that opens the warning ("Visa hela varningen").
 */
@Composable
internal fun CollapsedWarning(title: String, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val collapsed = stringResource(R.string.cook_collapsed)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = TandemTone.Warning.containerColor(),
        contentColor = TandemTone.Warning.contentColor(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                .clickable(onClickLabel = stringResource(R.string.cook_warning_show), role = Role.Button, onClick = onExpand)
                .semantics { stateDescription = collapsed }
                .padding(horizontal = spacing.lg)
                .testTag(COOK_WARNING_COLLAPSED_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(TandemIcons.Warning, contentDescription = null)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(TandemIcons.ExpandMore, contentDescription = null)
        }
    }
}

/**
 * The phone layout of cook mode (B6): [top] (header, tabs, handsfree), the step [pager], then at
 * the bottom the [ringing] timers, the [others] (warnings and the other timers, scrolling inside)
 * and the page [buttons].
 *
 * Who gets room first: the buttons and the top whole, then the ringing timers, then the step keeps
 * [minStepHeight] before the others get theirs. The ringing timers and the others together take at
 * most [timerShare] of the height, so the step above keeps room on every window (plan 6.5); the
 * [othersGap] above the others (their top padding) is not counted.
 */
@Composable
internal fun CookPhoneLayout(
    top: @Composable () -> Unit,
    pager: @Composable () -> Unit,
    ringing: @Composable () -> Unit,
    others: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
    timerShare: Float,
    modifier: Modifier = Modifier,
    othersGap: Dp = 0.dp,
    minStepHeight: Dp = MinStepHeight,
) {
    // One node per slot, whatever each slot emits.
    val topSlot: @Composable () -> Unit = { Column { top() } }
    val pagerSlot: @Composable () -> Unit = { Box(propagateMinConstraints = true) { pager() } }
    val ringingSlot: @Composable () -> Unit = { Column { ringing() } }
    val othersSlot: @Composable () -> Unit = { Column { others() } }
    val buttonsSlot: @Composable () -> Unit = { Column { buttons() } }
    Layout(
        contents = listOf(topSlot, pagerSlot, ringingSlot, othersSlot, buttonsSlot),
        modifier = modifier,
    ) { slots, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val minStep = minStepHeight.roundToPx()
        fun Measurable.upTo(maxHeight: Int) =
            measure(Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight.coerceAtLeast(0)))
        val topPlaceable = slots[TOP].single().upTo(height)
        var left = height - topPlaceable.height
        val buttonsPlaceable = slots[BUTTONS].single().upTo(left)
        left -= buttonsPlaceable.height
        val share = (height * timerShare).roundToInt()
        val ringingPlaceable = slots[RINGING].single().upTo(min(share, left))
        left -= ringingPlaceable.height
        // The others' gap above them is not part of the share (as the timer region before B6).
        val othersShare = share - ringingPlaceable.height + othersGap.roundToPx()
        val othersPlaceable = slots[OTHERS].single().upTo(min(othersShare, left - minStep))
        left -= othersPlaceable.height
        val pagerPlaceable = slots[PAGER].single().measure(Constraints.fixed(width, left.coerceAtLeast(0)))
        layout(width, height) {
            var y = 0
            listOf(topPlaceable, pagerPlaceable, ringingPlaceable, othersPlaceable, buttonsPlaceable).forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

/**
 * Draws the first of [variants] whose content fits the width it gets on one line, else the last
 * one (B10: "Föregående" must never be broken inside the word, in any language or font size).
 * The candidates are measured without semantics, so TalkBack and tests only see the one shown.
 */
@Composable
internal fun FirstThatFits(variants: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier) { constraints ->
        val chosen = if (constraints.hasBoundedWidth) {
            variants.indices.firstOrNull { index ->
                index == variants.lastIndex ||
                    subcompose("probe-$index") { Box(Modifier.clearAndSetSemantics { }) { variants[index]() } }
                        .all { it.maxIntrinsicWidth(Constraints.Infinity) <= constraints.maxWidth }
            } ?: variants.lastIndex
        } else {
            0
        }
        val placeables = subcompose("shown-$chosen", variants[chosen]).map { it.measure(constraints) }
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { placeables.forEach { it.placeRelative(0, 0) } }
    }
}

/**
 * A page button that shows only its arrow, for when the label does not fit (B10). The label is
 * still what TalkBack says; 64 dp like every button of cook mode (plan 6.6).
 */
@Composable
internal fun PageIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val height = TandemTheme.sizes.kitchenTouchTarget
    val buttonModifier = modifier.heightIn(min = height)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = description, modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)))
    }
    if (primary) {
        Button(onClick = onClick, shapes = ButtonDefaults.shapesFor(height), modifier = buttonModifier, enabled = enabled) { content() }
    } else {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapesFor(height),
            modifier = buttonModifier,
            enabled = enabled,
            // As TandemSecondaryButton: the neutral sand pill.
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { content() }
    }
}

/** The step keeps at least this much of a phone's height when the timer area is full (B6). */
internal val MinStepHeight = 96.dp

private const val TOP = 0
private const val PAGER = 1
private const val RINGING = 2
private const val OTHERS = 3
private const val BUTTONS = 4

/** Test tags. */
internal const val COOK_RINGING_TIMERS_TAG = "cook:ringing-timers"
internal const val COOK_WARNING_COLLAPSED_TAG = "cook:warning-collapsed"
