package casa.moller.tandem.feature.cook

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.handsfree.HandsfreeUiState
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.ui.COOK_PAGER_TAG
import casa.moller.tandem.feature.cook.ui.COOK_RINGING_TIMERS_TAG
import casa.moller.tandem.feature.cook.ui.COOK_WARNING_COLLAPSED_TAG
import casa.moller.tandem.feature.cook.ui.MinStepHeight
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The device test of 2026-11-14 on a 360 × 780 dp phone (Galaxy S21 FE class), in Compose with a
 * real ViewModel over fakes:
 *
 * - **B4:** two quick "Next" (taps or volume keys, the second while the pager still animates) end
 *   two steps on; a swipe still reports its page.
 * - **B6:** a ringing timer and its Stop stand above the warnings, which shrink to one line while it
 *   rings; a finished timer comes after the running ones; with two recipes and the handsfree row
 *   the step keeps room and its timer button stays reachable.
 * - **B10:** no page button label is broken inside a word, in either language and at font scale 2.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_SMALL)
class CookSmallPhoneTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pasta = SampleRecipes.MushroomPasta

    private fun show(
        fakes: CookFakes = CookFakes(),
        key: CookKey = CookKey(pasta.id),
        handsfree: HandsfreeUiState? = null,
        fontScale: Float? = null,
    ): CookFakes {
        composeRule.setContent {
            TestFrame(fontScale = fontScale, clock = fakes.clock) { CookWithFakes(key = key, fakes = fakes, handsfree = handsfree) }
        }
        composeRule.waitForIdle()
        return fakes
    }

    private fun showTwo(fakes: CookFakes, fontScale: Float? = null) {
        composeRule.setContent {
            TestFrame(fontScale = fontScale, clock = fakes.clock) {
                CookSessionWithFakes(fakes = fakes, sessionKey = TwoRecipes.key, handsfree = KEYS_ONLY)
            }
        }
        composeRule.waitForIdle()
    }

    // --- B4: quick page turns ---

    @Test
    fun twoQuickTapsOnNextEndOnTheThirdStep() {
        show()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Next").performClick()
        // The first page animation is running when the second tap comes.
        composeRule.mainClock.advanceTimeBy(QUICK_MILLIS)
        composeRule.onNodeWithText("Next").performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertOnThirdStep()
    }

    @Test
    fun twoQuickVolumeKeyPressesEndOnTheThirdStep() {
        show(handsfree = KEYS_ONLY)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(COOK_PAGER_TAG).performKeyInput { pressKey(Key.VolumeDown) }
        composeRule.mainClock.advanceTimeBy(QUICK_MILLIS)
        composeRule.onNodeWithTag(COOK_PAGER_TAG).performKeyInput { pressKey(Key.VolumeDown) }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertOnThirdStep()
    }

    @Test
    fun twoVolumeKeyPressesInOneBurstEndOnTheThirdStep() {
        // Both presses before any frame: the ViewModel's page must count both.
        show(handsfree = KEYS_ONLY)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(COOK_PAGER_TAG).performKeyInput {
            pressKey(Key.VolumeDown)
            pressKey(Key.VolumeDown)
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertOnThirdStep()
    }

    @Test
    fun aSwipeStillTurnsThePageAndTheButtonsFollow() {
        show()
        // A slow drag over most of the page: it settles on the next page without a fling.
        composeRule.onNodeWithTag(COOK_PAGER_TAG).performTouchInput {
            down(Offset(width * DRAG_START, centerY))
            repeat(DRAG_STEPS) { moveBy(Offset(-width * DRAG_STEP, 0f), delayMillis = FRAME_MILLIS) }
            up()
        }
        composeRule.waitForIdle()
        // The pager moved, and the header (the ViewModel's page) followed it.
        composeRule.onNodeWithText("STEP 2").assertIsDisplayed()
        composeRule.onNodeWithText("Step 2 of 3").assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
        assertOnThirdStep()
    }

    private fun assertOnThirdStep() {
        composeRule.onNodeWithText("Step 3 of 3").assertIsDisplayed()
        // The pager is at rest on step 3 too, not between two pages or back on step 2.
        val heading = composeRule.onNodeWithText("STEP 3").assertIsDisplayed().bounds()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("step 3 wholly in view: $heading", heading.left >= root.left - 1f && heading.right <= root.right + 1f)
    }

    // --- B6: the timer area ---

    @Test
    fun aRingingTimerStandsAboveTheWarningWhichIsOneLine() {
        show(ringingWithMutedChannel())

        val stop = composeRule.onNodeWithContentDescription(STOP_MUSHROOMS)
        stop.assertWhollyVisible()
        val warning = composeRule.onNodeWithTag(COOK_WARNING_COLLAPSED_TAG)
        warning.assertIsDisplayed()
        assertTrue("Stop above the warning", stop.bounds().bottom <= warning.bounds().top)
        // Folded: only the title, not the body or its button.
        assertEquals(0, composeRule.onAllNodesWithText("Settings").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Next").assertWhollyVisible()

        // A tap opens the whole warning; Stop stays where it is.
        warning.performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag(COOK_WARNING_COLLAPSED_TAG).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(STOP_MUSHROOMS).assertWhollyVisible()
    }

    @Test
    fun aRingingTimerStaysInViewAtFontScale2() {
        show(ringingWithMutedChannel(), fontScale = 2f)

        composeRule.onNodeWithContentDescription(STOP_MUSHROOMS).assertWhollyVisible()
        composeRule.onNodeWithTag(COOK_RINGING_TIMERS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertWhollyVisible()
    }

    @Test
    fun withoutARingingTimerTheWarningIsWhole() {
        show(
            CookFakes().apply {
                timers.setCapabilities(TimerCapabilities.AllGranted.copy(alarmChannelEnabled = false))
                startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
            },
        )
        composeRule.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag(COOK_WARNING_COLLAPSED_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun aFinishedTimerComesAfterTheRunningOnes() {
        val fakes = CookFakes().apply {
            startTimer(0, "Timer F", 1.minutes)
            timers.advanceTimeBy(1.minutes)
            val finished = timers.timers.value.single().id
            runBlocking { timers.stopRinging(finished) }
            startTimer(1, "Timer A", 10.minutes)
            clock.advanceBy(1.seconds)
            startTimer(1, "Timer B", 8.minutes)
        }
        show(fakes, key = CookKey(pasta.id, startStep = 1))

        val finished = composeRule.onNode(hasContentDescription("Timer F, ", substring = true)).bounds()
        val first = composeRule.onNode(hasContentDescription("Timer A, ", substring = true))
        val second = composeRule.onNode(hasContentDescription("Timer B, ", substring = true))
        first.assertIsDisplayed()
        second.assertIsDisplayed()
        assertTrue("A before the finished one", first.bounds().top < finished.top)
        assertTrue("B before the finished one", second.bounds().top < finished.top)
    }

    @Test
    fun twoRecipesWithHandsfreeKeepTheStepAndItsTimerButton() {
        showTwo(DeviceTest.twoRingingWithWarning())

        composeRule.onNodeWithContentDescription(STOP_MUSHROOMS_IN_PASTA).assertWhollyVisible()
        composeRule.onNodeWithTag(COOK_PAGER_TAG).assertHeightIsAtLeast(MinStepHeight)
        composeRule.onAllNodesWithContentDescription("Start", substring = true).onFirst().performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertWhollyVisible()
    }

    @Test
    fun twoRecipesWithHandsfreeAtFontScale2KeepStopAndTheTimerButtonReachable() {
        showTwo(DeviceTest.twoRingingWithWarning(), fontScale = 2f)

        composeRule.onNodeWithContentDescription(STOP_MUSHROOMS_IN_PASTA).assertWhollyVisible()
        composeRule.onAllNodesWithContentDescription("Start", substring = true).onFirst().performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertWhollyVisible()
    }

    // --- B10: whole labels ---

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun swedishLabelsAreNeverBrokenWithHandsfree() {
        show(key = CookKey(pasta.id, startStep = 1), handsfree = KEYS_ONLY)
        assertLabelsWhole("Föregående", "Nästa")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun swedishLabelsAreNeverBrokenWithoutHandsfree() {
        show(key = CookKey(pasta.id, startStep = 1))
        assertLabelsWhole("Föregående", "Nästa")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun swedishLabelsAreNeverBrokenAtFontScale2() {
        show(key = CookKey(pasta.id, startStep = 1), handsfree = KEYS_ONLY, fontScale = 2f)
        assertLabelsWhole("Föregående", "Nästa")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun swedishDoneIsWholeOnTheLastStep() {
        show(key = CookKey(pasta.id, startStep = 2), handsfree = KEYS_ONLY)
        assertLabelsWhole("Föregående", "Klart!")
    }

    @Test
    fun englishLabelsAreNeverBrokenWithHandsfree() {
        show(key = CookKey(pasta.id, startStep = 1), handsfree = KEYS_ONLY)
        assertLabelsWhole("Previous", "Next")
    }

    @Test
    fun englishLabelsAreNeverBrokenAtFontScale2() {
        show(key = CookKey(pasta.id, startStep = 1), handsfree = KEYS_ONLY, fontScale = 2f)
        assertLabelsWhole("Previous", "Next")
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun swedishLabelsAreNeverBrokenInLandscapeAtFontScale2() {
        show(key = CookKey(pasta.id, startStep = 1), handsfree = KEYS_ONLY, fontScale = 2f)
        assertLabelsWhole("Föregående", "Nästa")
    }

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun swedishLabelsAreNeverBrokenOnATabletAtFontScale2() {
        show(key = CookKey(pasta.id, startStep = 1), handsfree = KEYS_ONLY, fontScale = 2f)
        assertLabelsWhole("Föregående", "Nästa")
    }

    /**
     * Each label is either shown on one line without being cut, or (no room) only as the arrow
     * whose button TalkBack reads with the label, 64 dp high.
     */
    private fun assertLabelsWhole(vararg labels: String) {
        labels.forEach { label ->
            // The labels measured but not shown (FirstThatFits) are in the unmerged tree, unplaced.
            val texts = composeRule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
                .filter { it.layoutInfo.isPlaced }
            if (texts.isEmpty()) {
                composeRule.onNodeWithContentDescription(label).assertIsDisplayed().assertHeightIsAtLeast(KITCHEN_TARGET)
            } else {
                texts.forEach { node ->
                    val layout = node.textLayout()
                    assertEquals("“$label” on one line", 1, layout.lineCount)
                    // What the device showed: "Före gåe…", two lines and an ellipsis.
                    assertFalse("“$label” not ellipsized", layout.isLineEllipsized(0))
                }
            }
        }
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    /** Not clipped by a scrolling parent or the window: the whole node is on screen. */
    private fun SemanticsNodeInteraction.assertWhollyVisible() {
        assertIsDisplayed()
        val node = fetchSemanticsNode()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val shown = node.boundsInRoot
        assertTrue("whole height shown (${shown.height} of ${node.size.height})", shown.height >= node.size.height - 1f)
        assertTrue("inside the window", shown.bottom <= root.bottom + 1f)
    }

    private fun ringingWithMutedChannel(): CookFakes = DeviceTest.ringingWithWarning()

    private companion object {
        /** Well inside the pager's page animation. */
        const val QUICK_MILLIS = 50L

        const val DRAG_START = 0.9f
        const val DRAG_STEPS = 10
        const val DRAG_STEP = 0.07f
        const val FRAME_MILLIS = 16L

        val KITCHEN_TARGET = 64.dp
        val KEYS_ONLY = HandsfreeUiState(settings = HandsfreeSettings(enabled = true))
        const val STOP_MUSHROOMS = "Stop the alarm of Svampen · ca 5 minuter"
        const val STOP_MUSHROOMS_IN_PASTA = "Stop the alarm of Svampen · ca 5 minuter (Krämig svamppasta)"
    }
}
