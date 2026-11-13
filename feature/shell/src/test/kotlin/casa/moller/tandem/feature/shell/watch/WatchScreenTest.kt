package casa.moller.tandem.feature.shell.watch

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.watch.WatchOutcome
import casa.moller.tandem.core.model.watch.WatchResult
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.settings.PHONE_TALL
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fas 5 (wear lane): what Mer → Klockan does with a tap, and its ViewModel. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class WatchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun pairingAndUnpairingAreOneTapEach() {
        val actions = mutableListOf<WatchAction>()
        composeRule.setContent { TandemTheme { WatchScreen(WATCH_PAIRED, { actions += it }, {}) } }

        composeRule.onNodeWithTag(WATCH_PAIR_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(WATCH_UNPAIR_TAG).performScrollTo().performClick()

        assertEquals(listOf(WatchAction.Pair, WatchAction.Unpair), actions)
    }

    @Test
    fun nothingToPairWithoutAWatchOrForAGuest() {
        composeRule.setContent { TandemTheme { WatchScreen(WATCH_NO_APP, {}, {}) } }
        composeRule.onNodeWithTag(WATCH_PAIR_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onAllNodesWithText("Unpair the watch").assertCountEquals(0)
    }

    @Test
    fun aReadyWatchCanBePaired() {
        composeRule.setContent { TandemTheme { WatchScreen(WATCH_READY, {}, {}) } }
        composeRule.onNodeWithTag(WATCH_PAIR_TAG).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Galaxy Watch6 found").assertExists()
    }

    @Test
    fun theMoreRowOnlyShowsWithAWatch() {
        composeRule.setContent { TandemTheme { WatchMoreRow(WATCH_NO_WATCH.watch, selected = false, onOpen = {}) } }
        composeRule.onAllNodesWithText("Watch").assertCountEquals(0)
    }

    @Test
    fun theViewModelReportsEveryOutcome() = runTest(main.dispatcher) {
        val repository = FakeWatchRepository()
        val model = WatchViewModel(repository)
        assertEquals("the page looks for watches when it opens", 1, repository.refreshes)
        backgroundScope.launch { model.uiState.collect { } }

        model.effect.test {
            model.onAction(WatchAction.Pair)
            assertEquals(WatchEffect.Message(R.string.watch_message_paired), awaitItem())

            repository.pairResult = WatchResult(WatchOutcome.SERVER, TandemError.Timeout)
            model.onAction(WatchAction.Pair)
            assertEquals(WatchEffect.Message(R.string.watch_message_server), awaitItem())
            assertEquals(TandemError.Timeout, model.uiState.value.error)

            repository.unpairResult = WatchResult(WatchOutcome.UNPAIRED_WATCH_AWAY)
            model.onAction(WatchAction.Unpair)
            assertEquals(WatchEffect.Message(R.string.watch_message_unpaired_away), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(model.uiState.value.error)
    }
}
