package casa.moller.tandem.core.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.recipe.IngredientRow
import casa.moller.tandem.core.ui.recipe.RecipeCard
import casa.moller.tandem.core.ui.recipe.RecipeListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What TalkBack gets from the recipe components (plan 6.6): one complete item per row and card. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp-port-xhdpi")
class RecipeSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clickable = hasClickAction()

    @Test
    fun anIngredientWithConversionsIsOneItemWithTheSpokenAmountAndTheUnitSwitch() {
        var switched = 0
        composeRule.setContent {
            TandemTheme {
                IngredientRow(
                    amountText = "³⁄₁₆ tsk",
                    amountDescription = "3/16 tsk",
                    name = "kardemumma",
                    note = "nymald",
                    struck = false,
                    onToggle = {},
                    onAmountClick = { switched++ },
                )
            }
        }
        // Exactly one TalkBack stop: the row. The amount pill is still tappable, but no item of its own.
        composeRule.onAllNodes(clickable).assertCountEquals(1)
        val row = composeRule.onNodeWithContentDescription("3/16 tsk kardemumma, nymald").fetchSemanticsNode()
        assertEquals(ToggleableState.Off, row.config[SemanticsProperties.ToggleableState])
        val description = row.config[SemanticsProperties.ContentDescription].joinToString()
        assertFalse("no superscript digits for TalkBack: $description", description.any { it in "⁰¹²³⁴⁵⁶⁷⁸⁹₀₁₂₃₄₅₆₇₈₉" })
        // The unit switch is the row's custom action.
        val action = row.config.getOrNull(SemanticsActions.CustomActions)?.singleOrNull()
        assertNotNull(action)
        assertEquals("Show in another unit", action!!.label)
        action.action()
        assertEquals(1, switched)
        // The visible texts are not separate items either.
        composeRule.onAllNodesWithText("kardemumma").assertCountEquals(0)
    }

    @Test
    fun anIngredientWithoutConversionsSaysItsAmountInTheSamePlace() {
        composeRule.setContent {
            TandemTheme {
                IngredientRow(amountText = "2 dl", name = "grädde", struck = true, onToggle = {})
            }
        }
        composeRule.onAllNodes(clickable).assertCountEquals(1)
        val row = composeRule.onNodeWithContentDescription("2 dl grädde").fetchSemanticsNode()
        assertEquals(ToggleableState.On, row.config[SemanticsProperties.ToggleableState])
        assertTrue(row.config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty())
    }

    @Test
    fun aDimmedCardSaysItsNameFirstAndNotDownloadedOnce() {
        val recipe = RecipeOverview(id = 1, name = "Ärtsoppa", workingMinutes = 90, rating = 4.5)
        composeRule.setContent {
            TandemTheme {
                RecipeCard(recipe = recipe, onClick = {}, dimmed = true)
            }
        }
        val card = composeRule.onNode(clickable).fetchSemanticsNode()
        val description = card.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("name first: $description", description.startsWith("Ärtsoppa"))
        assertTrue(description.contains("Total time 1 h 30 min"))
        val state = card.config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Not downloaded, needs a connection", state)
        // The badge on the photo does not repeat it.
        val everything = (description + " " + state).split("Not downloaded").size - 1
        assertEquals(1, everything)
        composeRule.onAllNodes(hasContentDescription("Not downloaded", substring = true)).assertCountEquals(0)
    }

    @Test
    fun aListRowIsOneClickableItemWithItsName() {
        val recipe = RecipeOverview(id = 2, name = "Pannkakor", workingMinutes = 30)
        var opened = 0
        composeRule.setContent {
            TandemTheme {
                RecipeListItem(recipe = recipe, onClick = { opened++ }, selected = true)
            }
        }
        val node = composeRule.onNode(clickable).fetchSemanticsNode()
        assertEquals("Pannkakor, Total time 30 min", node.config[SemanticsProperties.ContentDescription].joinToString())
        assertEquals(true, node.config.getOrNull(SemanticsProperties.Selected))
        composeRule.onNode(clickable).performClick()
        assertEquals(1, opened)
    }
}
