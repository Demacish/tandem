package casa.moller.tandem.feature.cook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import casa.moller.tandem.core.model.timer.TimerSuggestion
import casa.moller.tandem.core.model.timer.TimerSuggestionSource
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.handsfree.HandsfreeUiState
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.ui.COOK_ADD_RECIPE_TAG
import casa.moller.tandem.feature.cook.ui.COOK_MORE_ACTIONS_TAG
import casa.moller.tandem.feature.cook.ui.COOK_PAGER_TAG
import casa.moller.tandem.feature.cook.ui.COOK_RINGING_TIMERS_TAG
import casa.moller.tandem.feature.cook.ui.COOK_SIDE_PANE_TAG
import casa.moller.tandem.feature.cook.ui.COOK_SIDE_TIMERS_TAG
import casa.moller.tandem.feature.cook.ui.COOK_WARNING_COLLAPSED_TAG
import casa.moller.tandem.feature.cook.ui.CookLayout
import casa.moller.tandem.feature.cook.ui.MAX_TIMER
import casa.moller.tandem.feature.cook.ui.MIN_TIMER
import casa.moller.tandem.feature.cook.ui.TIMER_ADJUST_LONGER_TAG
import casa.moller.tandem.feature.cook.ui.TIMER_ADJUST_SHORTER_TAG
import casa.moller.tandem.feature.cook.ui.TimerAdjustDialog
import casa.moller.tandem.feature.cook.ui.cookLayoutFor
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Device test 2026-11-14, round 3, on the Galaxy S21 FE (360 × 780 dp; 320 × 693 dp at the largest
 * display size), in Compose with a real ViewModel over fakes:
 *
 * - **B20:** a phone in landscape gets the short wide layout: at 780 × 360 dp and font scale 1 at
 *   least four whole lines of the step show, at 693 × 320 dp and font scale 2 at least two; a
 *   ringing timer's Stop is whole above the folded warning; turning the phone keeps the step and an
 *   open "Justera tiden".
 * - **K1:** the header's name breaks only between words (at most two lines); without room the
 *   actions move into the ⋮ menu.
 * - **K2:** a timer's name in the narrow side column is never broken inside a word.
 * - **K3:** "Föregående" whole (or only its arrow) in landscape; "Justera tiden" never says "0 s".
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_SMALL_LANDSCAPE)
class CookLandscapeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pasta = SampleRecipes.MushroomPasta

    private fun show(
        fakes: CookFakes = CookFakes(),
        key: CookKey = CookKey(pasta.id, startStep = 1),
        handsfree: HandsfreeUiState? = KEYS_ONLY,
        fontScale: Float? = null,
    ): CookFakes {
        composeRule.setContent {
            TestFrame(fontScale = fontScale, clock = fakes.clock) { CookWithFakes(key = key, fakes = fakes, handsfree = handsfree) }
        }
        composeRule.waitForIdle()
        return fakes
    }

    // --- B20 ---

    @Test
    fun aShortWideWindowGetsItsOwnLayout() {
        assertEquals(CookLayout.SHORT_WIDE, cookLayoutFor(780.dp, tabletop = false, height = 360.dp))
        assertEquals(CookLayout.SHORT_WIDE, cookLayoutFor(693.dp, tabletop = false, height = 320.dp))
        assertEquals(CookLayout.SHORT_WIDE, cookLayoutFor(915.dp, tabletop = false, height = 412.dp))
        // Tablets and foldables keep two panes; a phone in portrait keeps the phone layout.
        assertEquals(CookLayout.TWO_PANE, cookLayoutFor(1280.dp, tabletop = false, height = 800.dp))
        assertEquals(CookLayout.TWO_PANE, cookLayoutFor(800.dp, tabletop = false, height = 1280.dp))
        assertEquals(CookLayout.TWO_PANE, cookLayoutFor(600.dp, tabletop = false, height = 480.dp))
        assertEquals(CookLayout.PHONE, cookLayoutFor(360.dp, tabletop = false, height = 780.dp))
        assertEquals(CookLayout.TABLETOP, cookLayoutFor(780.dp, tabletop = true, height = 360.dp))
    }

    @Test
    fun atFontScale1FourLinesOfTheStepShowWithHandsfree() {
        show(CookFakes().withLongStep())

        composeRule.onNodeWithTag(COOK_SIDE_PANE_TAG).assertIsDisplayed()
        // The two panes' ingredient and timer pane is not used here.
        assertEquals(0, composeRule.onAllNodesWithTag(COOK_SIDE_TIMERS_TAG).fetchSemanticsNodes().size)
        assertTrue("whole lines of the step: ${visibleStepLines()}", visibleStepLines() >= 4)
    }

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun atFontScale2TwoLinesOfTheStepShowWithHandsfree() {
        show(CookFakes().withLongStep(), fontScale = 2f)
        assertTrue("whole lines of the step: ${visibleStepLines()}", visibleStepLines() >= 2)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED_LANDSCAPE")
    fun atFontScale2InSwedishTwoLinesOfTheStepShow() {
        show(CookFakes().withLongStep(), fontScale = 2f)
        assertTrue("whole lines of the step: ${visibleStepLines()}", visibleStepLines() >= 2)
    }

    @Test
    fun aRingingTimersStopIsWholeAboveTheFoldedWarning() {
        show(DeviceTest.ringingWithWarning(), key = CookKey(pasta.id))

        val stop = composeRule.onNodeWithContentDescription(STOP_MUSHROOMS).assertWhollyVisible()
        composeRule.onNodeWithTag(COOK_RINGING_TIMERS_TAG).assertIsDisplayed()
        val warning = composeRule.onNodeWithTag(COOK_WARNING_COLLAPSED_TAG).performScrollTo().assertIsDisplayed()
        assertTrue("Stop above the warning", stop.fetchSemanticsNode().boundsInRoot.top < warning.fetchSemanticsNode().boundsInRoot.top)
        composeRule.onNodeWithText("Next").assertWhollyVisible()
    }

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun atFontScale2TheRingingTimersStopIsWhole() {
        show(DeviceTest.ringingWithWarning(), key = CookKey(pasta.id), fontScale = 2f)

        composeRule.onNodeWithContentDescription(STOP_MUSHROOMS).assertWhollyVisible()
        // The page buttons stay in one row: "Next" whole or only its arrow, and in the window.
        composeRule.onAllNodesWithContentDescription("Next").onFirst().assertWhollyVisible()
    }

    @Test
    fun twoRecipesWithHandsfreeKeepStopAndTheStep() {
        val fakes = DeviceTest.twoRingingWithWarning()
        composeRule.setContent {
            TestFrame(clock = fakes.clock) { CookSessionWithFakes(fakes = fakes, sessionKey = TwoRecipes.key, handsfree = KEYS_ONLY) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(STOP_MUSHROOMS_IN_PASTA).assertWhollyVisible()
        composeRule.onNodeWithTag(COOK_PAGER_TAG).assertHeightIsAtLeast(LANDSCAPE_MIN_STEP)
    }

    @Test
    fun turningThePhoneKeepsTheStepAndTheOpenDialog() {
        var layout by mutableStateOf(CookLayout.PHONE)
        val fakes = CookFakes()
        composeRule.setContent {
            TestFrame(clock = fakes.clock) { CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = fakes, layout = layout) }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Start", substring = true).onFirst().performScrollTo().performClick()
        composeRule.onNodeWithText("Adjust the time").assertIsDisplayed()

        layout = CookLayout.SHORT_WIDE
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Adjust the time").assertIsDisplayed()
        composeRule.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        composeRule.onNodeWithTag(COOK_SIDE_PANE_TAG).assertExists()
    }

    // --- K2 ---

    @Test
    fun timerNamesInTheSideColumnAreNotBrokenInsideAWord() {
        show(DeviceTest.ringingWithWarning(), key = CookKey(pasta.id))
        assertWordsWhole("Svampen · ca 5 minuter", "Pastan · 8–10 minuter (10 min)", "Time is up!")
    }

    @Test
    @Config(qualifiers = PHONE_ZOOMED_LANDSCAPE)
    fun timerNamesAtFontScale2AreNotBrokenInsideAWord() {
        show(DeviceTest.ringingWithWarning(), key = CookKey(pasta.id), fontScale = 2f)
        assertWordsWhole("Svampen · ca 5 minuter", "Time is up!")
    }

    // --- K3 ---

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun swedishPageButtonsAreWholeInLandscape() {
        show()
        assertLabelsWhole("Föregående", "Nästa")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_ZOOMED_LANDSCAPE")
    fun swedishPageButtonsAreWholeInLandscapeAtFontScale2() {
        show(fontScale = 2f)
        assertLabelsWhole("Föregående", "Nästa")
    }

    @Test
    fun theShortestTimeHasNoZeroStep() {
        composeRule.setContent {
            TestFrame {
                TimerAdjustDialog(
                    suggestion = TimerSuggestion(label = "10 s", duration = MIN_TIMER, source = TimerSuggestionSource.STEP_TIME),
                    duration = MIN_TIMER,
                    onChange = {},
                    onStart = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(TIMER_ADJUST_SHORTER_TAG).assertIsNotEnabled().assert(hasContentDescription("Shorter"))
        composeRule.onNodeWithTag(TIMER_ADJUST_LONGER_TAG).assertIsEnabled().assert(hasContentDescription("10 s longer"))
        assertEquals(0, composeRule.onAllNodesWithContentDescription("0 s shorter").fetchSemanticsNodes().size)
    }

    @Test
    fun theLongestTimeHasNoZeroStep() {
        composeRule.setContent {
            TestFrame {
                TimerAdjustDialog(
                    suggestion = TimerSuggestion(label = "23 h", duration = MAX_TIMER, source = TimerSuggestionSource.STEP_TIME),
                    duration = MAX_TIMER,
                    onChange = {},
                    onStart = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(TIMER_ADJUST_LONGER_TAG).assertIsNotEnabled().assert(hasContentDescription("Longer"))
        composeRule.onNodeWithTag(TIMER_ADJUST_SHORTER_TAG).assertIsEnabled()
    }

    // --- K1 ---

    @Test
    @Config(qualifiers = PHONE_SMALL)
    fun atFontScale2TheNameBreaksBetweenWordsAndTheActionsMoveIntoTheMenu() {
        show(key = CookKey(pasta.id), handsfree = null, fontScale = 2f)

        val title = composeRule.onAllNodesWithText(pasta.name, useUnmergedTree = true).fetchSemanticsNodes()
            .single { it.layoutInfo.isPlaced }
        val layout = title.textLayout()
        assertTrue("at most two lines: ${layout.lineCount}", layout.lineCount <= 2)
        assertNoWordBroken(layout)

        composeRule.onNodeWithContentDescription("Larger text").assertDoesNotExist()
        composeRule.onNodeWithTag(COOK_MORE_ACTIONS_TAG).assert(hasContentDescription("More options")).performClick()
        composeRule.onNodeWithTag(COOK_ADD_RECIPE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Smaller text").assertIsDisplayed()
        composeRule.onNodeWithText("Larger text").assertIsDisplayed().performClick()
        // The text size items keep the menu open.
        composeRule.onNodeWithText("Larger text").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = PHONE)
    fun withRoomTheActionsStayInTheHeader() {
        show(key = CookKey(pasta.id), handsfree = null)

        composeRule.onNodeWithTag(COOK_MORE_ACTIONS_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Larger text").assertIsDisplayed()
        composeRule.onNodeWithTag(COOK_ADD_RECIPE_TAG).assertIsDisplayed()
    }

    // --- helpers ---

    /** The whole lines of the step's text inside the pager's bounds. */
    private fun visibleStepLines(): Int {
        val pager = composeRule.onNodeWithTag(COOK_PAGER_TAG).fetchSemanticsNode().boundsInRoot
        val node = composeRule.onAllNodesWithText(LONG_STEP_START, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.layoutInfo.isPlaced && it.boundsInRoot.left >= pager.left && it.boundsInRoot.right <= pager.right + 1f }
        val layout = node.textLayout()
        val top = node.positionInRoot.y
        return (0 until layout.lineCount).count { line ->
            top + layout.getLineTop(line) >= pager.top - 1f && top + layout.getLineBottom(line) <= pager.bottom + 1f
        }
    }

    private fun assertWordsWhole(vararg texts: String) {
        texts.forEach { text ->
            val nodes = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
            assertTrue("“$text” shown", nodes.isNotEmpty())
            nodes.forEach { assertNoWordBroken(it.textLayout()) }
        }
    }

    /** Each line but the last ends at a space or a dash: no word is split over two lines. */
    private fun assertNoWordBroken(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        (0 until layout.lineCount - 1).forEach { line ->
            val end = layout.getLineEnd(line)
            val before = text.getOrNull(end - 1)
            val after = text.getOrNull(end)
            assertTrue(
                "“$text” broken inside a word after line $line",
                before == null || before.isWhitespace() || before in BREAKS || after?.isWhitespace() == true,
            )
        }
    }

    /** As CookSmallPhoneTest: each label on one line without an ellipsis, or only its arrow (64 dp, the label for TalkBack). */
    private fun assertLabelsWhole(vararg labels: String) {
        labels.forEach { label ->
            val texts = composeRule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
            if (texts.isEmpty()) {
                composeRule.onNodeWithContentDescription(label).assertIsDisplayed().assertHeightIsAtLeast(KITCHEN_TARGET)
            } else {
                texts.forEach { node ->
                    val layout = node.textLayout()
                    assertEquals("“$label” on one line", 1, layout.lineCount)
                    assertTrue("“$label” not ellipsized", !layout.isLineEllipsized(0))
                }
            }
        }
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** Not clipped by a scrolling parent or the window: the whole node is on screen. */
    private fun SemanticsNodeInteraction.assertWhollyVisible(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val node = fetchSemanticsNode()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val shown = node.boundsInRoot
        assertTrue("whole height shown (${shown.height} of ${node.size.height})", shown.height >= node.size.height - 1f)
        assertTrue("inside the window", shown.bottom <= root.bottom + 1f)
        return this
    }

    private companion object {
        val KITCHEN_TARGET = 64.dp
        val LANDSCAPE_MIN_STEP = 96.dp
        val KEYS_ONLY = HandsfreeUiState(settings = HandsfreeSettings(enabled = true))
        const val STOP_MUSHROOMS = "Stop the alarm of Svampen · ca 5 minuter"
        const val STOP_MUSHROOMS_IN_PASTA = "Stop the alarm of Svampen · ca 5 minuter (Krämig svamppasta)"
        const val BREAKS = "-–/"
    }
}

/** The tested phone at its largest display size, in landscape (693 × 320 dp). */
internal const val PHONE_ZOOMED_LANDSCAPE = "w693dp-h320dp-land-xhdpi"

/** The beginning of [withLongStep]'s text. */
internal const val LONG_STEP_START = "Hetta upp en stor stekpanna"

/** The pasta's second step with a long text, more lines than any short window shows at once. */
internal fun CookFakes.withLongStep(): CookFakes = apply {
    val pasta = SampleRecipes.MushroomPasta
    val text = "$LONG_STEP_START på medelhög värme och smält smöret tills det slutar fräsa. Lägg i den skivade " +
        "svampen i ett jämnt lager och låt den ligga orörd tills undersidan fått fin färg, vänd sedan och stek " +
        "den andra sidan. Salta först när svampen fått färg, pressa ner vitlöken och låt den mjukna utan att " +
        "brännas. Häll på grädden, låt det sjuda ihop under omrörning och smaka av med peppar och citron."
    val long = pasta.copy(steps = pasta.steps.mapIndexed { index, step -> if (index == 1) step.copy(instruction = text) else step })
    recipes.details.value = recipes.details.value + (pasta.id to long)
}
