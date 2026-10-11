package casa.moller.tandem.feature.recipes.list

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.FakeRecipeListPreferences
import casa.moller.tandem.feature.recipes.FakeSessionRepository
import casa.moller.tandem.feature.recipes.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeListViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeRecipeRepository()
    private val sync = FakeSyncRepository()
    private val sessions = FakeSessionRepository()
    private val listPreferences = FakeRecipeListPreferences()

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()) =
        RecipeListViewModel(handle, repository, sync, listPreferences, sessions)

    /** Keeps uiState subscribed (it is WhileSubscribed) and returns the ViewModel. */
    private fun TestScope.subscribed(viewModel: RecipeListViewModel = newViewModel()): RecipeListViewModel {
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun showsTheStoredRecipesAndKeywords() = runTest(mainDispatcher.dispatcher) {
        val viewModel = newViewModel()
        viewModel.uiState.test {
            runCurrent()
            val state = expectMostRecentItem()
            assertTrue(state.loaded)
            assertEquals(SampleRecipes.overviews.size, state.recipes.size)
            assertEquals("Banana bread", state.recipes.first().name)
            assertEquals(SampleRecipes.keywords.size, state.keywords.size)
            assertFalse(state.isFiltered)
            assertEquals(ServerSearch.IDLE, state.serverSearch)
        }
    }

    @Test
    fun typingFiltersLocallyAtOnceAndAsksTheServerAfterAPause() = runTest(mainDispatcher.dispatcher) {
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.Search("pasta"))
        runCurrent()
        assertEquals(listOf(SampleRecipes.MushroomPasta.id), viewModel.uiState.value.recipes.map { it.id })
        assertTrue("no request per keystroke", repository.searchedQueries.isEmpty())

        advanceTimeBy(RecipeListViewModel.SEARCH_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(RecipeQuery(text = "pasta")), repository.searchedQueries)
        assertEquals(ServerSearch.DONE, viewModel.uiState.value.serverSearch)
    }

    @Test
    fun serverResultsAreAddedAfterTheStoredOnesWithoutDuplicates() = runTest(mainDispatcher.dispatcher) {
        val fromServer = RecipeOverview(id = 900, name = "Svampsoppa")
        val stored = SampleRecipes.overview(SampleRecipes.MushroomPasta.id)
        repository.searchServerResult = Outcome.Success(listOf(stored, fromServer, fromServer))
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.Search("svamp"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(SampleRecipes.MushroomPasta.id, 900L), state.recipes.map { it.id })
        assertEquals(1, state.serverOnlyCount)
    }

    @Test
    fun offlineSearchesOnlyTheDeviceAndDimsRecipesWithoutDetail() = runTest(mainDispatcher.dispatcher) {
        sync.setStatus(SyncStatus(pause = SyncPause.OFFLINE))
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.Search("a"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ServerSearch.OFFLINE, state.serverSearch)
        assertTrue(repository.searchedQueries.isEmpty())
        assertTrue(state.isDimmed(SampleRecipes.overview(SampleRecipes.PeaSoup.id)))
        assertFalse(state.isDimmed(SampleRecipes.overview(SampleRecipes.MushroomPasta.id)))
    }

    @Test
    fun aFailedServerSearchKeepsTheStoredResults() = runTest(mainDispatcher.dispatcher) {
        repository.searchServerResult = Outcome.Failure(TandemError.ServerError(502))
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.Search("pasta"))
        advanceUntilIdle()

        assertEquals(ServerSearch.FAILED, viewModel.uiState.value.serverSearch)
        assertEquals(1, viewModel.uiState.value.recipes.size)
    }

    @Test
    fun demoModeNeverAsksTheServer() = runTest(mainDispatcher.dispatcher) {
        sessions.state.value = Session.Demo
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.Search("pasta"))
        advanceUntilIdle()

        assertEquals(ServerSearch.IDLE, viewModel.uiState.value.serverSearch)
        assertTrue(repository.searchedQueries.isEmpty())
    }

    @Test
    fun onlyOfflineIsLocalOnly() = runTest(mainDispatcher.dispatcher) {
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.ToggleOnlyOffline)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.recipes.all { it.availableOffline })
        assertTrue(repository.searchedQueries.isEmpty())
    }

    @Test
    fun filterChipsNarrowTheQuery() = runTest(mainDispatcher.dispatcher) {
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.ToggleQuick)
        viewModel.onAction(RecipeListAction.ToggleGoodRating)
        viewModel.onAction(RecipeListAction.ToggleKeyword(SampleRecipes.Vegetarian.id))
        viewModel.onAction(RecipeListAction.Sort(RecipeSort.RATING))
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(
            RecipeQuery(keywordIds = setOf(SampleRecipes.Vegetarian.id), maxTotalMinutes = 30, minRating = 4.0, sort = RecipeSort.RATING),
            state.query,
        )
        assertEquals(listOf(SampleRecipes.MushroomPasta.id), state.recipes.map { it.id })
        assertEquals(listOf(SampleRecipes.Vegetarian), state.selectedKeywords)

        viewModel.onAction(RecipeListAction.ClearFilters)
        runCurrent()
        assertEquals(RecipeQuery(sort = RecipeSort.RATING), viewModel.uiState.value.query)
    }

    @Test
    fun theQuerySurvivesProcessDeath() = runTest(mainDispatcher.dispatcher) {
        val handle = SavedStateHandle()
        val first = subscribed(newViewModel(handle))
        first.onAction(RecipeListAction.Search("bullar"))
        first.onAction(RecipeListAction.ToggleKeyword(SampleRecipes.Baking.id))
        first.onAction(RecipeListAction.Sort(RecipeSort.NEWEST))

        val restored = subscribed(newViewModel(handle))
        assertEquals(
            RecipeQuery(text = "bullar", keywordIds = setOf(SampleRecipes.Baking.id), sort = RecipeSort.NEWEST),
            restored.uiState.value.query,
        )
    }

    @Test
    fun theLayoutChoiceIsRemembered() = runTest(mainDispatcher.dispatcher) {
        val viewModel = subscribed()
        assertEquals(RecipeListLayout.GRID, viewModel.uiState.value.layout)
        viewModel.onAction(RecipeListAction.ToggleLayout)
        runCurrent()
        assertEquals(RecipeListLayout.LIST, listPreferences.stored.value)
        assertEquals(RecipeListLayout.LIST, viewModel.uiState.value.layout)
    }

    @Test
    fun pullToRefreshRequestsAUserSyncAndEndsWithIt() = runTest(mainDispatcher.dispatcher) {
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.Refresh)
        runCurrent()
        assertEquals(listOf(SyncReason.USER_REFRESH), sync.requests)
        assertTrue(viewModel.uiState.value.refreshing)

        sync.setStatus(SyncStatus(running = true))
        runCurrent()
        assertTrue(viewModel.uiState.value.refreshing)
        sync.setStatus(SyncStatus.Idle)
        runCurrent()
        assertFalse(viewModel.uiState.value.refreshing)
    }

    @Test
    fun pullToRefreshStopsWhenTheSyncNeverStarts() = runTest(mainDispatcher.dispatcher) {
        val viewModel = subscribed()
        viewModel.onAction(RecipeListAction.Refresh)
        advanceTimeBy(RecipeListViewModel.SYNC_START_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertFalse(viewModel.uiState.value.refreshing)
    }

    @Test
    fun pullToRefreshOfflineSaysSo() = runTest(mainDispatcher.dispatcher) {
        sync.setStatus(SyncStatus(pause = SyncPause.OFFLINE))
        val viewModel = subscribed()
        viewModel.effects.test {
            viewModel.onAction(RecipeListAction.Refresh)
            assertEquals(RecipeListEffect.Message(R.string.recipes_refresh_offline), awaitItem())
        }
    }

    @Test
    fun anEmptyLibraryIsNotAFilteredResult() = runTest(mainDispatcher.dispatcher) {
        repository.overviews.value = emptyList()
        val viewModel = subscribed()
        val state = viewModel.uiState.value
        assertTrue(state.loaded)
        assertTrue(state.recipes.isEmpty())
        assertFalse(state.isFiltered)
    }
}
