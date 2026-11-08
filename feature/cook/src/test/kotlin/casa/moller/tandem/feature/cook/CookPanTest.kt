package casa.moller.tandem.feature.cook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The sample pasta baked in a 24 cm tin (Fas 5, plan §8.1 "Pannstorlek"): 400 g champignons. */
internal fun CookFakes.withTin(diameter: Int = 24, unit: String = "cm"): CookFakes = apply {
    val pasta = SampleRecipes.MushroomPasta
    recipes.details.value = recipes.details.value + (pasta.id to pasta.copy(diameter = diameter, diameterText = unit))
}

/** Fas 5 (plan §8.1 "Pannstorlek"): cook mode scales by the tin's area, on top of the servings. */
@OptIn(ExperimentalCoroutinesApi::class)
class CookPanViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val pasta = SampleRecipes.MushroomPasta

    private fun CookUiState.amount(stepIndex: Int, id: Long): String =
        steps[stepIndex].ingredients.filterIsInstance<IngredientRow.Item>().first { it.id == id }.let { "${it.amount} ${it.unit}".trim() }

    @Test
    fun aRecipeWithoutTinHasNoControl() = runTest(mainDispatcher.dispatcher) {
        val fakes = CookFakes()
        val viewModel = fakes.viewModel(CookKey(pasta.id))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertNull(viewModel.uiState.value.pan)
        viewModel.onAction(CookAction.SmallerPan)
        runCurrent()
        assertEquals("400 g", viewModel.uiState.value.amount(0, 10111))
    }

    @Test
    fun aSmallerTinScalesByAreaAndIsShared() = runTest(mainDispatcher.dispatcher) {
        val fakes = CookFakes().withTin()
        val viewModel = fakes.viewModel(CookKey(pasta.id))
        viewModel.onAction(CookAction.SetLocale(NumberStyle.Swedish, "sv"))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val start = viewModel.uiState.value
        assertEquals(24, start.pan?.original)
        assertEquals(24, start.pan?.current)
        assertEquals("cm", start.pan?.unit)
        assertEquals(1.0, start.pan?.factor)
        assertEquals("400 g", start.amount(0, 10111))

        repeat(4) { viewModel.onAction(CookAction.SmallerPan) }
        runCurrent()
        val smaller = viewModel.uiState.value
        assertEquals(20, smaller.pan?.current)
        assertEquals("0,69", smaller.pan?.factorText)
        assertTrue(smaller.pan?.scaled == true)
        assertEquals("276 g", smaller.amount(0, 10111))
        // The recipe view sees the same tin.
        assertEquals(mapOf(pasta.id to 20), fakes.pans.chosen.value)
        // The servings stay; both multiply (8 servings in a 20 cm tin: 2 × 0.69).
        assertEquals(4.0, smaller.servings?.current)
        repeat(4) { viewModel.onAction(CookAction.IncreaseServings) }
        runCurrent()
        assertEquals("552 g", viewModel.uiState.value.amount(0, 10111))

        viewModel.onAction(CookAction.ResetPan)
        runCurrent()
        assertEquals(24, viewModel.uiState.value.pan?.current)
        assertEquals(emptyMap<Long, Int>(), fakes.pans.chosen.value)
        assertEquals("800 g", viewModel.uiState.value.amount(0, 10111))
    }

    @Test
    fun aTinChosenInTheRecipeViewIsUsedAndSurvivesProcessDeath() = runTest(mainDispatcher.dispatcher) {
        val fakes = CookFakes().withTin()
        fakes.pans.setPanSize(pasta.id, 26)
        val handle = SavedStateHandle()
        val viewModel = fakes.viewModel(CookKey(pasta.id), handle)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(26, viewModel.uiState.value.pan?.current)
        assertEquals("468 g", viewModel.uiState.value.amount(0, 10111))
        viewModel.onAction(CookAction.LargerPan)
        runCurrent()

        // A new process: the shared choice is gone, cook mode's own is kept.
        val restored = CookFakes().withTin().viewModel(CookKey(pasta.id), handle)
        backgroundScope.launch { restored.uiState.collect {} }
        runCurrent()
        assertEquals(27, restored.uiState.value.pan?.current)
    }
}

/** Fas 5: the tin control in cook mode, in Compose with a real ViewModel. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class CookPanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theTinControlScalesTheAmounts() {
        val fakes = CookFakes().withTin()
        composeRule.setContent { TestFrame(clock = fakes.clock) { CookWithFakes(fakes = fakes) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("24 cm").performScrollTo().assertIsDisplayed()
        repeat(4) {
            composeRule.onNodeWithContentDescription("Smaller tin").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Scaled for a 20 cm tin (× 0.69)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("276 g", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("As in the recipe (24 cm)").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("400 g", substring = true).assertIsDisplayed()
    }
}
