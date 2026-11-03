package casa.moller.tandem.feature.recipes.editor

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6/6.6) of every editor state
 * ([RecipeEditorShot]): 48 dp targets, a label on every icon button (the step and line it moves
 * or removes), contrast in light and dark, font scale 2 and the tablet's width.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class RecipeEditorAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(shot: RecipeEditorShot, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        try {
            composeRule.assertAccessible()
        } catch (e: Throwable) {
            // TEMPORARY (debug): the semantics tree, to find the clipped touch targets at font scale 2.
            throw AssertionError(composeRule.onRoot().printToString(maxDepth = 30) + "\n" + e.message, e)
        }
    }

    @Test
    fun newEmpty() = check(RecipeEditorShot.NEW_EMPTY)

    @Test
    fun newNameMissing() = check(RecipeEditorShot.NEW_NAME_MISSING)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editing() = check(RecipeEditorShot.EDITING)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editingDark() = check(RecipeEditorShot.EDITING, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editingFontScale2() = check(RecipeEditorShot.EDITING, fontScale = 2f)

    @Test
    @Config(qualifiers = TABLET)
    fun editingTablet() = check(RecipeEditorShot.EDITING)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editingKeptEdit() = check(RecipeEditorShot.EDITING_KEPT_EDIT)

    @Test
    fun editingGuest() = check(RecipeEditorShot.EDITING_GUEST)

    @Test
    fun unknown() = check(RecipeEditorShot.UNKNOWN)
}
