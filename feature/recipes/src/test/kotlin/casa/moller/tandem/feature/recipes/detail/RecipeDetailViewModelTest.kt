package casa.moller.tandem.feature.recipes.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.recipes.FakeSessionRepository
import casa.moller.tandem.feature.recipes.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeDetailViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeRecipeRepository()
    private val preferences = FakePreferencesRepository()
    private val sync = FakeSyncRepository()
    private val sessions = FakeSessionRepository()

    private val pasta = SampleRecipes.MushroomPasta.id

    private fun TestScope.viewModel(
        id: Long = pasta,
        handle: SavedStateHandle = SavedStateHandle(),
        style: NumberStyle = NumberStyle.English,
    ): RecipeDetailViewModel {
        val viewModel = RecipeDetailViewModel(id, handle, repository, preferences, sync, sessions)
        viewModel.onAction(RecipeDetailAction.SetNumberStyle(style))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun RecipeDetailUiState.item(id: Long): IngredientRow.Item =
        (ingredients + steps.flatMap { it.ingredients }).filterIsInstance<IngredientRow.Item>().first { it.id == id }

    /** The amount and unit the screen shows for [id] in the chosen unit ("2.5 dl"). */
    private fun RecipeDetailUiState.shownAmount(id: Long): String =
        item(id).unitAt(unitChoices[id] ?: 0)?.let { listOf(it.amount, it.unit).filter(String::isNotEmpty).joinToString(" ") }.orEmpty()

    @Test
    fun showsTheStoredRecipeAndCountsTheView() = runTest(mainDispatcher.dispatcher) {
        val state = viewModel().uiState.value

        assertFalse(state.loading)
        assertTrue(state.hasDetail)
        assertEquals("Krämig svamppasta", state.header?.name)
        assertEquals(12, state.header?.timesCooked)
        assertEquals(4.0, state.servings?.current)
        assertEquals(SampleRecipes.MushroomPasta.ingredients.size, state.ingredients.size)
        assertEquals(listOf(1, 2, 3), state.steps.map { it.number })
        assertEquals("https://tandoor.example.com/recipe/101/", state.webUrl)
        assertTrue(state.canPin)
        assertEquals(listOf(pasta), repository.viewedIds)
    }

    @Test
    fun servingsScaleTheAmountsAndAreRememberedPerRecipe() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(RecipeDetailAction.IncreaseServings)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(5.0, repository.servingsChoices.value[pasta])
        assertEquals(5.0, state.servings?.current)
        assertTrue(state.servings!!.isChanged)
        assertEquals("500 g", state.shownAmount(10111))

        viewModel.onAction(RecipeDetailAction.ResetServings)
        runCurrent()
        assertNull(repository.servingsChoices.value[pasta])
        assertEquals(4.0, viewModel.uiState.value.servings?.current)
    }

    @Test
    fun goingBackToTheRecipesOwnServingsForgetsTheChoice() = runTest(mainDispatcher.dispatcher) {
        repository.servingsChoices.value = mapOf(pasta to 5.0)
        val viewModel = viewModel()
        viewModel.onAction(RecipeDetailAction.DecreaseServings)
        runCurrent()
        assertNull(repository.servingsChoices.value[pasta])
    }

    @Test
    fun servingsNeverGoBelowOne() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(id = SampleRecipes.BananaBread.id)
        assertFalse(viewModel.uiState.value.servings!!.canDecrease)
        viewModel.onAction(RecipeDetailAction.DecreaseServings)
        runCurrent()
        assertEquals(1.0, viewModel.uiState.value.servings?.current)
    }

    @Test
    fun ingredientsAreStruckThroughForTheSessionAndSurviveRecreation() = runTest(mainDispatcher.dispatcher) {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle = handle)
        viewModel.onAction(RecipeDetailAction.ToggleIngredient(10111))
        runCurrent()
        assertEquals(setOf(10111L), viewModel.uiState.value.struckIngredientIds)

        assertEquals(setOf(10111L), viewModel(handle = handle).uiState.value.struckIngredientIds)

        viewModel.onAction(RecipeDetailAction.ToggleIngredient(10111))
        runCurrent()
        assertTrue(viewModel.uiState.value.struckIngredientIds.isEmpty())
    }

    @Test
    fun tappingAnAmountCyclesThroughTheServersConversions() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        val cream = 10131L
        fun shown() = viewModel.uiState.value.shownAmount(cream)

        assertEquals("2.5 dl", shown())
        viewModel.onAction(RecipeDetailAction.SwitchUnit(cream))
        runCurrent()
        assertEquals("250 ml", shown())
        viewModel.onAction(RecipeDetailAction.SwitchUnit(cream))
        runCurrent()
        assertEquals("1.06 cups", shown())
        viewModel.onAction(RecipeDetailAction.SwitchUnit(cream))
        runCurrent()
        assertEquals("2.5 dl", shown())
    }

    @Test
    fun rowsWithoutConversionsDoNotSwitch() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(RecipeDetailAction.SwitchUnit(10111))
        runCurrent()
        assertTrue(viewModel.uiState.value.unitChoices.isEmpty())
    }

    @Test
    fun aStoredRecipeIsShownAsSavedVersionWhenOffline() = runTest(mainDispatcher.dispatcher) {
        sync.setStatus(SyncStatus(pause = SyncPause.OFFLINE))
        repository.detailOverrides.value = mapOf(
            pasta to RecipeDetailState(
                recipeId = pasta,
                overview = SampleRecipes.overview(pasta),
                recipe = SampleRecipes.MushroomPasta,
                error = TandemError.NetworkUnavailable,
            ),
        )
        val state = viewModel().uiState.value
        assertTrue(state.offline)
        assertTrue(state.showsSavedVersion)
        assertFalse(state.isDeleted)
    }

    @Test
    fun aDeletedRecipeKeepsItsSavedCopy() = runTest(mainDispatcher.dispatcher) {
        repository.detailOverrides.value = mapOf(
            pasta to RecipeDetailState(recipeId = pasta, recipe = SampleRecipes.MushroomPasta, error = TandemError.NotFound),
        )
        val state = viewModel().uiState.value
        assertTrue(state.isDeleted)
        assertTrue(state.hasDetail)
        assertFalse(state.showsSavedVersion)
    }

    @Test
    fun nothingStoredAndAnErrorCanBeRetried() = runTest(mainDispatcher.dispatcher) {
        repository.detailOverrides.value = mapOf(999L to RecipeDetailState(recipeId = 999, error = TandemError.Timeout))
        val viewModel = viewModel(id = 999)
        val state = viewModel.uiState.value
        assertTrue(state.isEmpty)
        assertEquals(TandemError.Timeout, state.error)

        viewModel.onAction(RecipeDetailAction.Refresh)
        runCurrent()
        assertEquals(listOf(999L), repository.refreshedIds)
    }

    @Test
    fun onlyTheListEntryShowsTheHeaderWithoutDetail() = runTest(mainDispatcher.dispatcher) {
        repository.details.value = emptyMap()
        val state = viewModel().uiState.value
        assertFalse(state.hasDetail)
        assertEquals("Krämig svamppasta", state.header?.name)
        assertNull(state.servings)
    }

    @Test
    fun aFailedRefreshOfAStoredRecipeIsAMessage() = runTest(mainDispatcher.dispatcher) {
        repository.refreshResult = Outcome.Failure(TandemError.UnknownHost)
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onAction(RecipeDetailAction.Refresh)
            assertEquals(RecipeDetailEffect.Message(R.string.recipe_detail_refresh_failed_offline), awaitItem())
        }
        assertFalse(viewModel.uiState.value.refreshing)
    }

    @Test
    fun downloadingForOfflinePinsTheRecipe() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(id = SampleRecipes.PeaSoup.id)
        assertFalse(viewModel.uiState.value.pinned)
        viewModel.effects.test {
            viewModel.onAction(RecipeDetailAction.ToggleOffline)
            assertEquals(RecipeDetailEffect.Message(R.string.recipe_detail_offline_saved), awaitItem())
        }
        assertTrue(viewModel.uiState.value.pinned)
    }

    @Test
    fun demoModeHasNoWebLinkAndNoOfflineToggle() = runTest(mainDispatcher.dispatcher) {
        sessions.state.value = Session.Demo
        val state = viewModel().uiState.value
        assertNull(state.webUrl)
        assertFalse(state.canPin)
    }

    @Test
    fun stepIngredientsFollowTheUserPreference() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.steps.first().showIngredients)

        preferences.preferences.value = UserPreferences(showStepIngredients = false)
        runCurrent()
        assertTrue(viewModel.uiState.value.steps.none { it.showIngredients })
    }

    @Test
    fun stepsWithoutATableHideTheirIngredients() = runTest(mainDispatcher.dispatcher) {
        val steps = viewModel(id = SampleRecipes.CinnamonBuns.id).uiState.value.steps
        assertEquals(listOf(true, true, false), steps.map { it.showIngredients })
    }

    @Test
    fun numbersFollowTheScreensLanguage() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(style = NumberStyle.Swedish)
        assertEquals("2,5 dl", viewModel.uiState.value.shownAmount(10131))

        viewModel.onAction(RecipeDetailAction.SetNumberStyle(NumberStyle.English))
        runCurrent()
        assertEquals("2.5 dl", viewModel.uiState.value.shownAmount(10131))
    }

    @Test
    fun templatesAreRenderedForTheChosenServings() = runTest(mainDispatcher.dispatcher) {
        repository.details.value = mapOf(templated.id to templated)
        val viewModel = viewModel(id = templated.id)
        val step = viewModel.uiState.value.steps.single()
        assertEquals("Stek 1 lök i 30 g smör.", step.instruction)
        assertFalse(step.hasBrokenTemplate)

        viewModel.onAction(RecipeDetailAction.IncreaseServings)
        runCurrent()
        // Like the web: the scaled amount, the food in plural, the unit-less number scaled too.
        assertEquals("Stek 1.5 lökar i 45 g smör.", viewModel.uiState.value.steps.single().instruction)
    }

    @Test
    fun aBrokenTemplateShowsTheInstructionAsWritten() = runTest(mainDispatcher.dispatcher) {
        val broken = templated.copy(steps = listOf(templated.steps.single().copy(instruction = "Stek {{ scale( }} g smör.")))
        repository.details.value = mapOf(broken.id to broken)
        val step = viewModel(id = broken.id).uiState.value.steps.single()
        assertTrue(step.hasBrokenTemplate)
        assertEquals("Stek {{ scale( }} g smör.", step.instruction)
    }

    @Test
    fun halfAnOnionIsPluralLikeOnTheWeb() = runTest(mainDispatcher.dispatcher) {
        repository.details.value = mapOf(templated.id to templated)
        repository.servingsChoices.value = mapOf(templated.id to 1.0)
        preferences.preferences.value = UserPreferences(useFractions = true)
        val onion = viewModel(id = templated.id, style = NumberStyle.Web).uiState.value.item(ONION)
        // FACTS.md "Fas 1 l" 4: singular only for exactly one, so ½ is plural ("1⁄2 lökar").
        assertEquals("1\u20442", onion.amount)
        assertEquals("lökar", onion.food)
    }

    private companion object {
        const val ONION = 9001L

        /** Two servings, a unit-less food with a plural name, and templates in the step. */
        val templated = Recipe(
            id = 900,
            name = "Lök i smör",
            servings = 2,
            steps = listOf(
                Step(
                    id = 9000,
                    instruction = "Stek {{ ingredients[0] }} i {{ scale(30) }} g smör.",
                    ingredients = listOf(
                        Ingredient(id = ONION, food = Food(1, "lök", "lökar"), amount = 1.0, order = 0),
                        Ingredient(id = 9002, food = Food(2, "smör"), unit = MeasurementUnit(1, "g"), amount = 30.0, order = 1),
                    ),
                ),
            ),
        )
    }
}
