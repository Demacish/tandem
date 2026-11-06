package casa.moller.tandem.feature.cook

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.session.COOK_TABS_TAG
import casa.moller.tandem.feature.cook.session.CookRecipeTabs
import casa.moller.tandem.feature.cook.session.CookSessionAction
import casa.moller.tandem.feature.cook.session.CookSessionUi
import casa.moller.tandem.feature.cook.session.CookTab
import casa.moller.tandem.feature.cook.session.cookPickerRecipeTag
import casa.moller.tandem.feature.cook.session.cookTabTag
import casa.moller.tandem.feature.cook.ui.COOK_ADD_RECIPE_TAG
import casa.moller.tandem.feature.cook.ui.COOK_CLOSE_TAG
import casa.moller.tandem.feature.cook.ui.COOK_STEP_OF_TAG
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas 4 (two recipes at once, plan 8.1, Kitshn #433), "Klart när": you can switch between two
 * cook-mode tabs, and timers from both are visible and ring. Driven like CookRoute
 * ([CookSessionWithFakes]: a real session and a real cook ViewModel per recipe).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class CookTwoRecipesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pasta = SampleRecipes.MushroomPasta
    private val buns = SampleRecipes.CinnamonBuns

    private lateinit var inputMode: InputModeManager

    private fun show(
        fakes: CookFakes = CookFakes(),
        sessionKey: CookKey = CookKey(buns.id, alongsideRecipeId = pasta.id),
        onClose: () -> Unit = {},
    ): CookFakes {
        composeRule.setContent {
            inputMode = LocalInputModeManager.current
            TestFrame(clock = fakes.clock) { CookSessionWithFakes(fakes = fakes, sessionKey = sessionKey, onClose = onClose) }
        }
        composeRule.waitForIdle()
        return fakes
    }

    @Test
    fun theTabsSwitchAndEachRecipeKeepsItsStep() {
        show()
        composeRule.onNodeWithTag(cookTabTag(buns.id)).assertIsSelected().assertHeightIsAtLeast(64.dp)
        composeRule.onAllNodesWithText("Kanelbullar").onFirst().assertExists()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 2 of 3")

        composeRule.onNodeWithTag(cookTabTag(pasta.id)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(cookTabTag(pasta.id)).assertIsSelected()
        composeRule.onAllNodesWithText("Krämig svamppasta").onFirst().assertExists()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 1 of 3")

        composeRule.onNodeWithTag(cookTabTag(buns.id)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 2 of 3")
        composeRule.onNodeWithTag(cookTabTag(pasta.id)).assert(hasContentDescription("Krämig svamppasta, Step 1 of 3"))
    }

    /** Keyboard mode, where a tab can take the focus (a test's key events do not switch to it). */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun focusTab(recipeId: Long) {
        composeRule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
        composeRule.onNodeWithTag(cookTabTag(recipeId)).requestFocus()
        composeRule.waitForIdle()
    }

    @Test
    fun aTabSwitchKeepsTheTabRowSoFocusStaysOnTheTab() {
        show()
        focusTab(pasta.id)
        composeRule.onNodeWithTag(cookTabTag(pasta.id)).performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(cookTabTag(pasta.id)).assertIsSelected().assertIsFocused()
    }

    @Test
    fun leftAndRightOnATabMoveTheFocusAndDoNotTurnThePage() {
        show()
        focusTab(pasta.id)
        composeRule.onNodeWithTag(cookTabTag(pasta.id)).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 1 of 3")
        composeRule.onNodeWithTag(cookTabTag(buns.id)).assertIsFocused()
    }

    @Test
    fun theButtonsOfATimerSayWhichRecipeTheyActOn() {
        val fakes = CookFakes().apply {
            startTimerOf(pasta.id, pasta.name, 1, "Koka · 10 min", 10.minutes)
            startTimerOf(buns.id, buns.name, 1, "Koka · 10 min", 10.minutes)
        }
        show(fakes)
        composeRule.onNodeWithContentDescription("Pause Koka · 10 min (Krämig svamppasta)").assertExists()
        composeRule.onNodeWithContentDescription("Pause Koka · 10 min (Kanelbullar)").assertExists()
    }

    @Test
    fun aRecipeClosedWithXComesBackAsItWas() {
        val fakes = CookFakes()
        lateinit var restore: () -> Unit
        composeRule.setContent {
            TestFrame(clock = fakes.clock) {
                val session = remember { fakes.sessionViewModel(CookKey(buns.id, alongsideRecipeId = pasta.id)) }
                restore = { session.onAction(CookSessionAction.RestoreClosed) }
                CookSessionWithFakes(fakes = fakes, sessionViewModel = session)
            }
        }
        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 2 of 3")

        composeRule.onNodeWithTag(COOK_CLOSE_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 1 of 3")

        restore()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(cookTabTag(buns.id)).assertIsSelected()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 2 of 3")
    }

    @Test
    fun aTabThatCannotBeShownSaysSo() {
        val tab = CookTab(recipeId = 9, name = "Kanelbullar", page = 0, stepCount = 0, selected = true, failed = true)
        val tabs = listOf(tab, tab.copy(recipeId = 8, selected = false))
        composeRule.setContent { TestFrame { CookRecipeTabs(CookSessionUi(tabs = tabs), onAction = {}) } }
        composeRule.onNodeWithTag(cookTabTag(9)).assert(hasContentDescription("Kanelbullar, Cannot be shown"))
    }

    @Test
    fun timersOfBothRecipesAreShownWithTheirRecipeAndRing() {
        val fakes = CookFakes().apply {
            startTimerOf(pasta.id, pasta.name, 1, "Pastan · 10 min", 10.minutes)
            startTimerOf(buns.id, buns.name, 2, "Gräddning · 7 min", 7.minutes)
        }
        show(fakes)

        composeRule.onNodeWithContentDescription("Krämig svamppasta: Pastan · 10 min, 10 min left").assertExists()
        composeRule.onNodeWithContentDescription("Kanelbullar: Gräddning · 7 min, 7 min left").assertExists()

        fakes.timers.advanceTimeBy(10.minutes)
        composeRule.waitForIdle()
        assertEquals(listOf(TimerState.RINGING, TimerState.RINGING), fakes.timers.timers.value.map { it.state })
        composeRule.onNodeWithContentDescription("Krämig svamppasta: Pastan · 10 min, time is up").assertExists()
        composeRule.onNodeWithTag(cookTabTag(pasta.id)).assert(hasContentDescription("Krämig svamppasta, Step 1 of 3, a timer is ringing"))

        // The same timers in the other tab.
        composeRule.onNodeWithTag(cookTabTag(pasta.id)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Kanelbullar: Gräddning · 7 min, time is up").assertExists()
    }

    @Test
    fun aTimerOfTheOtherRecipeOpensItsTabAtItsStep() {
        val fakes = CookFakes().apply { startTimerOf(pasta.id, pasta.name, 1, "Pastan · 10 min", 10.minutes) }
        show(fakes)

        composeRule.onNodeWithContentDescription("Krämig svamppasta: Pastan · 10 min, 10 min left").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(cookTabTag(pasta.id)).assertIsSelected()
        composeRule.onNodeWithTag(COOK_STEP_OF_TAG).assertTextEquals("Step 2 of 3")
    }

    @Test
    fun closingTheShownRecipeReturnsToTheOtherAndTheLastClosesCookMode() {
        var closed = 0
        show(onClose = { closed++ })
        composeRule.onNodeWithContentDescription("Stop cooking Kanelbullar").assertExists()

        composeRule.onNodeWithTag(COOK_CLOSE_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COOK_TABS_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithText("Krämig svamppasta").onFirst().assertExists()
        assertEquals(0, closed)

        composeRule.onNodeWithContentDescription("Close cook mode").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun cookAnotherRecipeAddsItAsASecondTab() {
        show(sessionKey = CookKey(pasta.id))
        composeRule.onNodeWithTag(COOK_TABS_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(COOK_ADD_RECIPE_TAG).assert(hasContentDescription("Cook another recipe")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(cookPickerRecipeTag(buns.id)).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(COOK_TABS_TAG).assertExists()
        composeRule.onNodeWithTag(cookTabTag(buns.id)).assertIsSelected()
        composeRule.onNodeWithTag(COOK_ADD_RECIPE_TAG).assertDoesNotExist()
    }

    @Test
    fun savingDoneClosesOnlyThatRecipe() {
        var closed = false
        val fakes = show(sessionKey = CookKey(buns.id, startStep = 3, alongsideRecipeId = pasta.id), onClose = { closed = true })

        composeRule.onNodeWithText("Save in the cook log").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(buns.id), fakes.cookLog.logged.map { it.recipeId })
        composeRule.onNodeWithTag(COOK_TABS_TAG).assertDoesNotExist()
        composeRule.onAllNodesWithText("Krämig svamppasta").onFirst().assertExists()
        assertFalse("cook mode stays open with the other recipe", closed)
    }
}
