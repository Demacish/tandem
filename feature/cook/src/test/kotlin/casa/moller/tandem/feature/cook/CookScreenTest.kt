package casa.moller.tandem.feature.cook

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.ui.COOK_PAGER_TAG
import casa.moller.tandem.feature.cook.ui.COOK_SIDE_PANE_TAG
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Cook mode in Compose with a real ViewModel over fakes: pages, timers, ingredients and "Klart!". */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class CookScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pasta = SampleRecipes.MushroomPasta

    private fun show(fakes: CookFakes = CookFakes(), key: CookKey = CookKey(pasta.id), onClose: () -> Unit = {}): CookFakes {
        composeRule.setContent { TestFrame(clock = fakes.clock) { CookWithFakes(key = key, fakes = fakes, onClose = onClose) } }
        composeRule.waitForIdle()
        return fakes
    }

    @Test
    fun nextAndPreviousTurnThePages() {
        show()
        composeRule.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        composeRule.onNodeWithText("Previous").assertIsNotEnabled()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Step 2 of 3").assertIsDisplayed()

        composeRule.onNodeWithText("Previous").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Step 1 of 3").assertIsDisplayed()
    }

    @Test
    fun theLastStepLeadsToDone() {
        show(key = CookKey(pasta.id, startStep = 2))
        composeRule.onNodeWithText("Step 3 of 3").assertIsDisplayed()

        composeRule.onAllNodesWithText("Done!").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Enjoy your meal!").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun arrowKeysTurnThePages() {
        show()
        composeRule.onNodeWithTag(COOK_PAGER_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Step 2 of 3").assertIsDisplayed()

        composeRule.onNodeWithTag(COOK_PAGER_TAG).performKeyInput { pressKey(Key.PageUp) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Step 1 of 3").assertIsDisplayed()
    }

    @Test
    fun aTimerButtonStartsATimerShownInTheBar() {
        val fakes = show()

        composeRule.onNodeWithText("Start 5 min").performScrollTo().performClick()
        composeRule.waitForIdle()

        val timer = fakes.timers.timers.value.single()
        assertEquals(5.minutes, timer.duration)
        assertEquals(0, timer.stepIndex)
        composeRule.onNodeWithText(timer.label).assertIsDisplayed()
        composeRule.onNodeWithText("5:00").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Pause ${timer.label}").performClick()
        composeRule.waitForIdle()
        assertEquals(TimerState.PAUSED, fakes.timers.timers.value.single().state)
    }

    @Test
    fun aRingingTimerIsStoppedFromTheBar() {
        val fakes = CookFakes().apply {
            startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
            timers.advanceTimeBy(5.minutes)
        }
        show(fakes)
        composeRule.onNodeWithText("Time is up!").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Stop the alarm of Svampen · ca 5 minuter").performClick()
        composeRule.waitForIdle()
        assertEquals(TimerState.FINISHED, fakes.timers.timers.value.single().state)
    }

    @Test
    fun theIngredientSectionOpensAndCloses() {
        show()
        // An ingredient row is one TalkBack item labelled "400 g champinjoner".
        composeRule.onNodeWithContentDescription("champinjoner", substring = true).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Ingredients for this step").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("champinjoner", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun moreServingsScaleTheStep() {
        val fakes = show()
        composeRule.onNodeWithContentDescription("More servings").performScrollTo().performClick()
        composeRule.waitForIdle()

        // The recipe's own servings text (Tandoor `servings_text`).
        composeRule.onNodeWithText("5 portioner").assertIsDisplayed()
        assertEquals(5.0, fakes.recipes.servingsChoices.value[pasta.id])
    }

    @Test
    fun doneSavesTheRatingAndCommentAndCloses() {
        var closed = false
        val fakes = show(key = CookKey(pasta.id, startStep = 3), onClose = { closed = true })

        composeRule.onNodeWithContentDescription("4 stars").performClick()
        composeRule.onNodeWithText("Comment (optional)").performScrollTo().performTextInput("Gott")
        composeRule.onNodeWithText("Save in the cook log").performScrollTo().performClick()
        composeRule.waitForIdle()

        val draft = fakes.cookLog.logged.single()
        assertEquals(4, draft.rating)
        assertEquals("Gott", draft.comment)
        assertEquals(4, draft.servings)
        assertTrue(closed)
    }

    @Test
    @Config(qualifiers = TABLET)
    fun aTabletShowsTheIngredientsAndTimersBesideTheStep() {
        show(CookFakes().apply { startTimer(1, "Pastan · 10 min", 10.minutes) })

        composeRule.onNodeWithTag(COOK_SIDE_PANE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Ingredients for step 1").assertIsDisplayed()
        composeRule.onNodeWithText("Active timers").assertIsDisplayed()
        composeRule.onNodeWithText("Pastan · 10 min").assertIsDisplayed()

        // A timer of this recipe jumps to its step.
        composeRule.onNodeWithContentDescription("Pastan · 10 min, 10 min left").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        composeRule.onNodeWithText("Ingredients for step 2").assertIsDisplayed()
    }
}
