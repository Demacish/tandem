package casa.moller.tandem.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertNoWordBreaks
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val HEADING = "Ingredienser"
private const val HINT = "Tryck för att bocka av"
private const val COUNT = "3 recept"
private const val OFFLINE_HINT = "Offline: söker bland recepten på enheten"
private const val NAME = "Banan taco med huvudsallad"

/**
 * The label-first rows of the device test 2026-11-14 (B18, B22): at font scale 2.0 on a 320 dp
 * phone the trailing element moves under the label and no word breaks; at font scale 1.0 on a
 * normal phone nothing moves. Real text measuring (NATIVE graphics).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h693dp-port-xhdpi")
class LabelRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aShortHintStaysBesideTheHeading() {
        show(fontScale = 1f, width = 380.dp) { Heading() }
        val heading = bounds(HEADING)
        val hint = bounds(HINT)
        assertTrue("hint beside the heading: $heading / $hint", hint.top < heading.bottom && hint.left >= heading.right)
        composeRule.assertNoWordBreaks(HEADING, HINT)
    }

    @Test
    fun atFontScale2TheHintMovesUnderTheHeading() {
        show(fontScale = 2f, width = 288.dp) { Heading() }
        val heading = bounds(HEADING)
        val hint = bounds(HINT)
        assertTrue("hint under the heading: $heading / $hint", hint.top >= heading.bottom)
        composeRule.assertNoWordBreaks(HEADING, HINT)
    }

    @Test
    fun theCountKeepsItsWidthBesideTheOfflineHint() {
        // B18 on the S21 FE: 360 dp minus the screen margins, font scale 1.0.
        show(fontScale = 1f, width = 328.dp) {
            LabelRow(
                modifier = Modifier.fillMaxWidth(),
                label = { Text(COUNT, style = MaterialTheme.typography.labelLarge) },
                trailing = { Text(OFFLINE_HINT, style = MaterialTheme.typography.labelLarge) },
            )
        }
        val count = bounds(COUNT)
        val hint = bounds(OFFLINE_HINT)
        // Before the fix the count got about 16 dp, one or two letters per line.
        assertTrue("the count keeps its width: $count", count.right - count.left > 40.dp)
        assertTrue("the hint beside or under the count: $count / $hint", hint.left >= count.right || hint.top >= count.bottom)
        composeRule.assertNoWordBreaks(COUNT, OFFLINE_HINT)
    }

    @Test
    fun anEmptyTrailingSlotLeavesTheLabelTheWholeRow() {
        show(fontScale = 2f, width = 288.dp) {
            LabelRow(
                modifier = Modifier.fillMaxWidth().testTag("row"),
                label = { Text(COUNT) },
                trailing = {},
            )
        }
        val row = composeRule.onNodeWithTag("row").getUnclippedBoundsInRoot()
        assertTrue("one line, no gap under it", row.bottom - bounds(COUNT).bottom < 1.dp)
    }

    @Test
    fun aMediaRowPutsTheTextUnderThePhotoAtFontScale2() {
        show(fontScale = 2f, width = 288.dp) { Media() }
        assertTrue("text under the photo", bounds(NAME).top >= composeRule.onNodeWithTag("photo").getUnclippedBoundsInRoot().bottom)
        val icon = composeRule.onNodeWithTag("icon").getUnclippedBoundsInRoot()
        assertTrue("the icon stays on the photo's line, at the end", icon.right > 280.dp && icon.top < 72.dp)
        composeRule.assertNoWordBreaks(NAME)
    }

    @Test
    fun aMediaRowKeepsOneRowAtFontScale1() {
        show(fontScale = 1f, width = 380.dp) { Media() }
        val photo = composeRule.onNodeWithTag("photo").getUnclippedBoundsInRoot()
        assertTrue("text beside the photo", bounds(NAME).left >= photo.right && bounds(NAME).top < photo.bottom)
    }

    @Composable
    private fun Heading() {
        LabelRow(
            modifier = Modifier.fillMaxWidth(),
            gap = 0.dp,
            label = { Text(HEADING, style = MaterialTheme.typography.titleLarge) },
            trailing = { Text(HINT, style = MaterialTheme.typography.labelMedium) },
        )
    }

    @Composable
    private fun Media() {
        MediaRow(
            modifier = Modifier.fillMaxWidth(),
            leading = { Box(Modifier.size(72.dp).testTag("photo")) },
            content = { Text(NAME, style = MaterialTheme.typography.titleMedium) },
            trailing = { Box(Modifier.size(24.dp).testTag("icon")) },
        )
    }

    private fun show(fontScale: Float, width: Dp, content: @Composable () -> Unit) {
        composeRule.setContent {
            TandemTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.width(width)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(text: String): DpRect = composeRule.onNodeWithText(text).getUnclippedBoundsInRoot()
}
