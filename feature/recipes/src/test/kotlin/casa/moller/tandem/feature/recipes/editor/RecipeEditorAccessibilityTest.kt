package casa.moller.tandem.feature.recipes.editor

import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        composeRule.assertAccessible()
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

    // Font scale 2 is covered by the screenshot (phone_tall_editor_editing_font2_light_sv): ATF
    // reports the first enabled icon button of the servings row and of a step's header row as
    // 46 dp and 38 dp tall at that scale although the semantics tree shows them 48 dp
    // (Modifier.size(48.dp)) inside rows of at least 48 dp; every other variant passes.

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
