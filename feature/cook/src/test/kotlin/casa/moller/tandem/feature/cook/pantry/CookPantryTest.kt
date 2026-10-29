package casa.moller.tandem.feature.cook.pantry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryUnit
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.FakeCalendarDays
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.feature.cook.PHONE
import casa.moller.tandem.feature.cook.TestFrame
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Tog något slut?" after "Klart!" (Fas S, hemma-ui lane, plan §8.8): the recipe's foods that are
 * in the pantry, "Slut" and "−1" with "Ångra", nothing for a guest, the card on a phone (also dark
 * and at font scale 2) with its accessibility checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-$PHONE")
class CookPantryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pasta = SampleRecipes.MushroomPasta

    /** The pasta's butter (food 2) and salt (5) are in the pantry; the cream is not one of its foods. */
    private val butter = InventoryEntry(21, 1, PantryFood(2, "smör"), PantryUnit(4, "paket"), 2.0, LocalDate(2026, 11, 20))
    private val salt = InventoryEntry(22, 3, PantryFood(5, "salt"), PantryUnit(5, "st"), 1.0)
    private val pantry = FakePantryRepository(entries = listOf(butter, salt, SamplePantry.cream))
    private val permissions = FakeSpacePermissionsRepository()

    private val recipes = FakeRecipeRepository(details = listOf(pasta))

    private fun viewModel() = CookPantryViewModel(pasta.id, recipes, pantry, permissions, FakeCalendarDays(SamplePantry.TODAY))

    @Test
    fun theRecipesFoodsInThePantryAreOfferedAndNothingIsTakenByItself() = runTest {
        val vm = viewModel()
        val effects = mutableListOf<CookPantryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        assertEquals(listOf("smör", "salt"), vm.uiState.value.rows.map { it.entry.food.name })
        assertEquals(listOf("Kyl", "Skafferi"), vm.uiState.value.rows.map { it.locationName })
        assertEquals(2.0, pantry.entries.value.first { it.id == butter.id }.amount, 0.0)

        vm.onAction(CookPantryAction.Decrement(butter.id))
        advanceUntilIdle()
        assertEquals(1.0, pantry.entries.value.first { it.id == butter.id }.amount, 0.0)
        assertEquals(CookPantryEffect.Changed("smör", 1.0, "paket"), effects.last())
        vm.onAction(CookPantryAction.MarkEmpty(salt.id))
        advanceUntilIdle()
        assertEquals(listOf("smör"), vm.uiState.value.rows.map { it.entry.food.name })
        vm.onAction(CookPantryAction.Undo)
        advanceUntilIdle()
        assertEquals(1.0, pantry.entries.value.first { it.id == salt.id }.amount, 0.0)
    }

    @Test
    fun aGuestIsNotAsked() = runTest {
        permissions.state.value = SpacePermissions(SpaceRole.GUEST)
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.visible)
        vm.onAction(CookPantryAction.MarkEmpty(butter.id))
        advanceUntilIdle()
        assertEquals(2.0, pantry.entries.value.first { it.id == butter.id }.amount, 0.0)
    }

    @Test
    fun theCardIsFoldedAndEachRowIsOneItem() {
        val actions = mutableListOf<CookPantryAction>()
        composeRule.setContent { TestFrame { Card(state, onAction = { actions += it }) } }
        composeRule.onNodeWithText("Visa 2 varor i skafferiet").performClick()
        composeRule.onNodeWithTag(COOK_PANTRY_TAG).assertExists()
        composeRule.onNode(hasContentDescription("Smör, 2 paket, Kyl, bäst före", substring = true)).assertExists()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun card() = capture("cook_pantry_light_sv")

    @Test
    fun cardDark() = capture("cook_pantry_dark_sv", darkTheme = true)

    @Test
    fun cardFontScale2() = capture("cook_pantry_font2_light_sv", fontScale = 2f)

    @Test
    fun cardAccessible() {
        composeRule.setContent { TestFrame { Card(state, onAction = {}) } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun cardAccessibleDarkFontScale2() {
        composeRule.setContent { TestFrame(darkTheme = true, fontScale = 2f) { Card(state, onAction = {}) } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    private val state = CookPantryState(
        loading = false,
        today = SamplePantry.TODAY,
        rows = listOf(
            UsedUpRow(butter, "Kyl", ExpiryUrgency.LATER),
            UsedUpRow(salt, "Skafferi", ExpiryUrgency.NONE),
            UsedUpRow(SamplePantry.cream, "Kyl", ExpiryUrgency.SOON),
        ).take(2),
    )

    private fun capture(name: String, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale) { Card(state, onAction = {}, open = true) } }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/cook/phone_$name.png")
    }

    @Composable
    private fun Card(state: CookPantryState, onAction: (CookPantryAction) -> Unit, open: Boolean = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                CookPantryCard(state = state, onAction = onAction, initiallyOpen = open)
            }
        }
    }
}
