package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.unit.Constraints

// Device test 2026-11-14, round 3 (B20): cook mode on a phone in landscape. At 780 × 360 dp the two
// panes left the step about 62 dp under the header, the handsfree row and the buttons, so not even
// one line of it could be read. A short wide window (CookLayout.SHORT_WIDE) now keeps the step on
// the left under a slim header, and everything else goes into this side column.

/**
 * The side column of [CookLayout.SHORT_WIDE]: the [ringing] timers at the top (their Stop above the
 * warnings, kokvy's B6 rule), the [handsfree] row, the [others] (warnings and the other timers,
 * scrolling inside), and the page [buttons] at the bottom.
 *
 * Who gets room first: the buttons, then the ringing timers (the whole rest if they need it), then
 * the handsfree row, then the others. Nothing here takes height from the step, which has the
 * other side of the window to itself.
 */
@Composable
internal fun CookShortSideColumn(
    ringing: @Composable () -> Unit,
    handsfree: @Composable () -> Unit,
    others: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One node per slot, whatever each slot emits.
    val ringingSlot: @Composable () -> Unit = { Column { ringing() } }
    val handsfreeSlot: @Composable () -> Unit = { Column { handsfree() } }
    val othersSlot: @Composable () -> Unit = { Column { others() } }
    val buttonsSlot: @Composable () -> Unit = { Column { buttons() } }
    Layout(
        contents = listOf(ringingSlot, handsfreeSlot, othersSlot, buttonsSlot),
        modifier = modifier,
    ) { slots, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        fun Measurable.upTo(maxHeight: Int) =
            measure(Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight.coerceAtLeast(0)))
        val buttonsPlaceable = slots[SIDE_BUTTONS].single().upTo(height)
        var left = height - buttonsPlaceable.height
        val ringingPlaceable = slots[SIDE_RINGING].single().upTo(left)
        left -= ringingPlaceable.height
        val handsfreePlaceable = slots[SIDE_HANDSFREE].single().upTo(left)
        left -= handsfreePlaceable.height
        val othersPlaceable = slots[SIDE_OTHERS].single().upTo(left)
        layout(width, height) {
            var y = 0
            listOf(ringingPlaceable, handsfreePlaceable, othersPlaceable).forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height
            }
            // The buttons stay at the bottom edge, where the thumb is.
            buttonsPlaceable.placeRelative(0, height - buttonsPlaceable.height)
        }
    }
}

private const val SIDE_RINGING = 0
private const val SIDE_HANDSFREE = 1
private const val SIDE_OTHERS = 2
private const val SIDE_BUTTONS = 3
