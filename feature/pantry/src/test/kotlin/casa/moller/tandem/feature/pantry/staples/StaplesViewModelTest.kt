package casa.moller.tandem.feature.pantry.staples

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.pantry.Staple
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeStaplesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Basvaror" (Fas S, hemma-ui lane, plan §8.8): suggestions, ticks sent on "Spara", removing with undo, "Inte nu". */
@OptIn(ExperimentalCoroutinesApi::class)
class StaplesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val staples = FakeStaplesRepository(listOf(Staple("salt", 201, onHand = true), Staple("Olja", 203, onHand = false)))

    private fun TestScope.start(): Pair<StaplesViewModel, MutableList<StaplesEffect>> {
        val vm = StaplesViewModel(SavedStateHandle(), staples, FakeSpacePermissionsRepository())
        val effects = mutableListOf<StaplesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onAction(StaplesAction.Suggestions(listOf("salt", "svartpeppar", "olja", "vatten")))
        advanceUntilIdle()
        return vm to effects
    }

    @Test
    fun theHouseholdsStaplesComeFirstAndTheSuggestionsAreNotRepeated() = runTest {
        val (vm) = start()
        val rows = vm.uiState.value.rows
        assertEquals(listOf("Olja", "salt", "svartpeppar", "vatten"), rows.map { it.name })
        assertEquals(listOf(true, true, false, false), rows.map { it.known })
        assertEquals(listOf(false, true, false, false), rows.map { it.atHome })
    }

    @Test
    fun savingAddsTheNewOnesAndMarksTheChangedOnesInBatches() = runTest {
        val (vm, effects) = start()
        vm.onAction(StaplesAction.Toggle("svartpeppar"))
        vm.onAction(StaplesAction.Toggle("olja"))
        vm.onAction(StaplesAction.Toggle("salt"))
        vm.onAction(StaplesAction.TypedText("Ättika"))
        vm.onAction(StaplesAction.AddTyped)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rows.first { it.name == "Ättika" }.atHome)
        vm.onAction(StaplesAction.Save)
        advanceUntilIdle()

        val byName = staples.staples.value.associateBy { it.name.lowercase() }
        assertTrue(byName.getValue("svartpeppar").onHand)
        assertTrue(byName.getValue("ättika").onHand)
        assertTrue(byName.getValue("olja").onHand)
        assertFalse(byName.getValue("salt").onHand)
        assertFalse("vatten was not ticked", "vatten" in byName)
        assertTrue(staples.suggestionsShown.value)
        assertEquals(StaplesEffect.Done, effects.last())
    }

    @Test
    fun removingAStapleCanBeUndone() = runTest {
        val (vm, effects) = start()
        vm.onAction(StaplesAction.Remove("salt"))
        advanceUntilIdle()
        assertTrue(staples.staples.value.none { it.name == "salt" })
        assertEquals(StaplesEffect.Removed("salt"), effects.last())
        vm.onAction(StaplesAction.Undo)
        advanceUntilIdle()
        assertTrue(staples.staples.value.first { it.name == "salt" }.onHand)
    }

    @Test
    fun notNowCountsAsShown() = runTest {
        val (vm, effects) = start()
        vm.onAction(StaplesAction.Close)
        advanceUntilIdle()
        assertTrue(staples.suggestionsShown.value)
        assertEquals(StaplesEffect.Done, effects.last())
        assertTrue("nothing was saved", staples.staples.value.none { it.name == "svartpeppar" })
    }
}
