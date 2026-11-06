package casa.moller.tandem.feature.scan.recipetext

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.domain.scan.TextFragment
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Fota recept" (Fas 4): pages read into text, a photo without text, a failed photo, "Använd texten", a guest. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeTextScanViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val permissions = FakeSpacePermissionsRepository()

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun <T> TestScope.effectsOf(flow: Flow<T>): List<T> {
        val effects = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(effects) }
        return effects
    }

    private fun line(text: String, top: Int) = TextFragment(text, 0, top, 100, top + 20)

    @Test
    fun pagesAreReadIntoTextAndHandedToTheImport() = runTest {
        val vm = RecipeTextScanViewModel(SavedStateHandle(), permissions)
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        vm.onAction(RecipeTextScanAction.ReadPage { listOf(line("Pannkakor", 0), line("2,5 dl vetemjöl", 60)) })
        assertEquals(listOf("Pannkakor\n\n2,5 dl vetemjöl"), vm.uiState.value.pages)
        assertFalse(vm.uiState.value.reading)
        vm.onAction(RecipeTextScanAction.ReadPage { listOf(line("Vispa ihop allt.", 0)) })
        assertEquals("Pannkakor\n\n2,5 dl vetemjöl\n\nVispa ihop allt.", vm.uiState.value.text)
        assertEquals("Pannkakor\n2,5 dl vetemjöl\nVispa ihop allt.", vm.uiState.value.preview)
        vm.onAction(RecipeTextScanAction.RemoveLastPage)
        vm.onAction(RecipeTextScanAction.Done)
        assertEquals(listOf(RecipeTextScanEffect.TextRead("Pannkakor\n\n2,5 dl vetemjöl")), effects)
    }

    @Test
    fun aPhotoWithoutTextOrAFailedOneIsReported() = runTest {
        val vm = RecipeTextScanViewModel(SavedStateHandle(), permissions)
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        vm.onAction(RecipeTextScanAction.ReadPage { emptyList() })
        assertEquals(RecipeTextProblem.NO_TEXT, vm.uiState.value.problem)
        assertTrue(vm.uiState.value.pages.isEmpty())
        vm.onAction(RecipeTextScanAction.ReadPage { error("broken image") })
        assertEquals(RecipeTextProblem.UNREADABLE, vm.uiState.value.problem)
        vm.onAction(RecipeTextScanAction.DismissProblem)
        assertNull(vm.uiState.value.problem)
        // Nothing read: "Använd texten" does nothing.
        vm.onAction(RecipeTextScanAction.Done)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun aGuestSeesTheReadOnlyState() = runTest {
        permissions.setGuest(true)
        val vm = RecipeTextScanViewModel(SavedStateHandle(), permissions)
        collect(vm.uiState)
        assertTrue(vm.uiState.value.readOnly)
    }

    @Test
    fun thePagesReadSurviveProcessDeath() = runTest {
        val handle = SavedStateHandle()
        val vm = RecipeTextScanViewModel(handle, permissions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onAction(RecipeTextScanAction.PageRead(listOf(TextFragment("Pannkakor", 0, 0, 100, 20))))
        assertEquals(1, vm.uiState.value.pages.size)

        val again = RecipeTextScanViewModel(handle, permissions)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { again.uiState.collect {} }
        assertEquals(listOf("Pannkakor"), again.uiState.value.pages)
    }
}
