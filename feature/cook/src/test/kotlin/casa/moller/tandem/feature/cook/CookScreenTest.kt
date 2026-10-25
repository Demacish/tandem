package casa.moller.tandem.feature.cook

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.ui.COOK_PAGER_TAG
import casa.moller.tandem.feature.cook.ui.COOK_SIDE_PANE_TAG
import casa.moller.tandem.feature.cook.ui.COOK_SIDE_TIMERS_TAG
import casa.moller.tandem.feature.cook.ui.COOK_STEP_IMAGE_TAG
import casa.moller.tandem.feature.cook.ui.COOK_STEP_OF_TAG
import casa.moller.tandem.feature.cook.ui.COOK_TABLETOP_CONTROLS_TAG
import casa.moller.tandem.feature.cook.ui.COOK_TABLETOP_UPPER_TAG
import casa.moller.tandem.feature.cook.ui.CookLayout
import casa.moller.tandem.feature.cook.ui.CookScreen
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

        composeRule.onNodeWithContentDescription("Start a 5 min timer", substring = true).performScrollTo().performClick()
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

    // --- fix-ui (0.2.0 review) ---

    @Test
    @Config(qualifiers = TABLET)
    fun aRingingTimerStaysInViewBesideManyIngredients() {
        show(CookFakes().withManyIngredients().withRingingTimer())

        // Pinned under the ingredients, ringing first: Stop and +1 min without scrolling the pane.
        composeRule.onNodeWithTag(COOK_SIDE_TIMERS_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop the alarm of Svampen · ca 5 minuter").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add one minute to Svampen · ca 5 minuter").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun aRingingTimerStaysInViewOnALandscapePhone() {
        show(CookFakes().withManyIngredients().withRingingTimer())

        composeRule.onNodeWithContentDescription("Stop the alarm of Svampen · ca 5 minuter").assertIsDisplayed()
    }

    @Test
    fun theTabletopHalvesMeetAtTheFold() {
        val fakes = CookFakes().withRingingTimer()
        composeRule.setContent {
            TestFrame(clock = fakes.clock) { CookWithFakes(fakes = fakes, layout = CookLayout.TABLETOP, fold = FOLDABLE_FOLD) }
        }
        composeRule.waitForIdle()

        val upper = composeRule.onNodeWithTag(COOK_TABLETOP_UPPER_TAG).fetchSemanticsNode().boundsInWindow
        val controls = composeRule.onNodeWithTag(COOK_TABLETOP_CONTROLS_TAG).fetchSemanticsNode().boundsInWindow
        // Nothing of the step (header included) on the crease, the controls start below it.
        assertTrue("upper part ends at ${upper.bottom}", upper.bottom <= FOLDABLE_FOLD.top + 0.5f)
        assertTrue("controls start at ${controls.top}", controls.top >= FOLDABLE_FOLD.bottom - 0.5f)
    }

    @Test
    @Config(qualifiers = PHONE_SHORT)
    fun aShortWindowKeepsTheStepAndFullSizeButtons() {
        show(
            CookFakes().apply {
                timers.setCapabilities(TimerCapabilities.AllGranted.copy(exactAlarmsAllowed = false, exactAlarmsRequestable = true))
                startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
                startTimer(1, "Pastan · 8–10 minuter (10 min)", 10.minutes)
            },
        )

        composeRule.onNodeWithTag(COOK_PAGER_TAG).assertHeightIsAtLeast(MIN_STEP_HEIGHT)
        composeRule.onNodeWithText("Next").assertIsDisplayed().assertHeightIsAtLeast(KITCHEN_TARGET)
        composeRule.onNodeWithText("Previous").assertHeightIsAtLeast(KITCHEN_TARGET)
    }

    @Test
    fun warningAndQuestionButtonsAreKitchenSized() {
        // ATF only checks 48 dp; cook mode needs 64 dp (plan 6.2/6.6).
        show(
            CookFakes().apply {
                timers.setCapabilities(TimerCapabilities.AllGranted.copy(exactAlarmsAllowed = false, exactAlarmsRequestable = true))
                startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
            },
        )
        composeRule.onNodeWithText("Allow").assertHeightIsAtLeast(KITCHEN_TARGET)
        composeRule.onNodeWithText("Not now").assertHeightIsAtLeast(KITCHEN_TARGET)
    }

    @Test
    fun theNotificationQuestionHasKitchenSizedButtons() {
        show(
            CookFakes().apply {
                timers.setCapabilities(TimerCapabilities.FreshInstall)
                handle[CookViewModel.KEY_PENDING] = PendingTimer(0, "Svampen · ca 5 minuter", durationSeconds = 300).encode()
            },
        )
        composeRule.onNodeWithText("Continue").assertHeightIsAtLeast(KITCHEN_TARGET)
        composeRule.onNodeWithText("Not now").assertHeightIsAtLeast(KITCHEN_TARGET)
    }

    @Test
    fun theAlarmChannelWarningOpensTheChannelSettings() {
        var opened = ""
        val fakes = CookFakes().apply {
            timers.setCapabilities(TimerCapabilities.AllGranted.copy(alarmChannelEnabled = false))
            startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        }
        composeRule.setContent {
            TestFrame(clock = fakes.clock) {
                val viewModel = remember { fakes.viewModel(CookKey(pasta.id)) }
                val state by viewModel.uiState.collectAsState()
                CookScreen(
                    state = state,
                    onAction = viewModel::onAction,
                    onClose = {},
                    onOpenNotificationSettings = { opened = "notifications" },
                    onOpenAlarmChannelSettings = { opened = "channel" },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("The timer alarm is muted").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        assertEquals("channel", opened)
    }

    @Test
    fun theStepHeaderAnnouncesEveryPage() {
        show()
        val header = composeRule.onNodeWithTag(COOK_STEP_OF_TAG)
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        header.assert(hasContentDescription("Step 1 of 3: Svampen"))

        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assert(hasContentDescription("Step 2 of 3: Pastan"))
    }

    @Test
    fun pauseAndResumeAreOneButton() {
        // The same node toggles, so TalkBack focus stays on it.
        val fakes = show(CookFakes().apply { startTimer(0, "Svampen · ca 5 minuter", 5.minutes) })
        val pause = composeRule.onNodeWithContentDescription("Pause Svampen · ca 5 minuter")
        val id = pause.fetchSemanticsNode().id
        pause.performClick()
        composeRule.waitForIdle()

        assertEquals(TimerState.PAUSED, fakes.timers.timers.value.single().state)
        assertEquals(id, composeRule.onNodeWithContentDescription("Resume Svampen · ca 5 minuter").fetchSemanticsNode().id)
    }

    @Test
    fun aDeletedRecipeSaysSoWithoutARetry() {
        show(
            CookFakes().apply {
                recipes.details.value = recipes.details.value - pasta.id
                recipes.detailOverrides.value = mapOf(pasta.id to RecipeDetailState(recipeId = pasta.id, error = TandemError.NotFound))
            },
        )
        composeRule.onNodeWithText("The recipe is no longer in Tandoor").assertIsDisplayed()
        composeRule.onNodeWithText("Go back").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().size)
    }

    @Test
    fun anOfflineRecipeAsksForAConnection() {
        show(
            CookFakes().apply {
                recipes.details.value = recipes.details.value - pasta.id
                recipes.detailOverrides.value =
                    mapOf(pasta.id to RecipeDetailState(recipeId = pasta.id, error = TandemError.NetworkUnavailable))
            },
        )
        composeRule.onNodeWithText("The recipe is not downloaded").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun doneInSafeModeSaysTheEntryWaits() {
        show(
            CookFakes().apply { sync.setStatus(SyncStatus.Idle.copy(pause = SyncPause.SAFE_MODE)) },
            key = CookKey(pasta.id, startStep = 3),
        )
        // IconLabel speaks its text as the content description.
        composeRule.onNodeWithContentDescription("Safe mode", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("The entry is sent to your Tandoor.").fetchSemanticsNodes().size)
    }

    @Test
    fun theStepsPhotoIsShownUnderItsText() {
        show(CookFakes().withStepImage(), key = CookKey(pasta.id, startStep = 1))
        composeRule.onNodeWithTag(COOK_STEP_IMAGE_TAG).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        val KITCHEN_TARGET = 64.dp

        /** The step keeps at least this much on a 420 dp window with a warning and two timers. */
        val MIN_STEP_HEIGHT = 96.dp
    }
}
