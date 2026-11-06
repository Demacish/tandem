package casa.moller.tandem.feature.recipes

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.recipes.books.BookShot
import casa.moller.tandem.feature.recipes.drinks.DrinkShot
import casa.moller.tandem.feature.recipes.list.RecipeListScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas 4's completeness check: "Nytt recept", "Ny dryck" and "Ny bok" have a name in the MERGED tree,
 * the one TalkBack reads. material3 clears the semantics of the button's text and its icon has no
 * description, so without its own description the button is an unnamed clickable; a test on the
 * unmerged tree finds the text and sees nothing wrong.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewItemButtonsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun button(textId: Int): SemanticsNodeInteraction {
        val label = ApplicationProvider.getApplicationContext<android.content.Context>().getString(textId)
        return composeRule.onNode(hasContentDescription(label) and hasClickAction())
    }

    @Test
    fun newRecipeHasItsName() {
        var opened = 0
        composeRule.setContent {
            TestFrame { RecipeListScreen(state = listState(), onAction = {}, onOpenRecipe = {}, onCreateRecipe = { opened++ }) }
        }
        button(R.string.recipes_new).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun newDrinkHasItsName() {
        composeRule.setContent { TestFrame { DrinkShot.LIST.content() } }
        button(R.string.drinks_new).assertIsDisplayed()
    }

    @Test
    fun newBookHasItsName() {
        composeRule.setContent { TestFrame { BookShot.BOOKS.content() } }
        button(R.string.books_new).assertIsDisplayed()
    }
}
