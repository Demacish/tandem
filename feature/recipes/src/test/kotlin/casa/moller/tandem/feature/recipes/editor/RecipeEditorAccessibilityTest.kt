package casa.moller.tandem.feature.recipes.editor

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import casa.moller.tandem.feature.recipes.books.PHONE_LANDSCAPE
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
    fun editingPicture() = check(RecipeEditorShot.EDITING_PICTURE)

    @Test
    fun editingPictureRemovedDark() = check(RecipeEditorShot.EDITING_PICTURE_REMOVED, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editingOptions() = check(RecipeEditorShot.EDITING_OPTIONS)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editingOptionsDark() = check(RecipeEditorShot.EDITING_OPTIONS, darkTheme = true)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editingOptionsFontScale2() = check(RecipeEditorShot.EDITING_OPTIONS, fontScale = 2f)

    @Test
    fun linkPicker() = check(RecipeEditorShot.LINK_PICKER)

    @Test
    fun linkPickerDark() = check(RecipeEditorShot.LINK_PICKER, darkTheme = true)

    @Test
    fun linkPickerEmpty() = check(RecipeEditorShot.LINK_PICKER_EMPTY)

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
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun editingKeyboard() = check(RecipeEditorShot.EDITING_KEYBOARD)

    @Test
    fun editingOriginal() = check(RecipeEditorShot.EDITING_ORIGINAL)

    @Test
    @Config(qualifiers = TABLET)
    fun editingOriginalTablet() = check(RecipeEditorShot.EDITING_ORIGINAL)

    @Test
    fun leaveDialog() = check(RecipeEditorShot.LEAVE_DIALOG)

    @Test
    fun discardDialog() = check(RecipeEditorShot.DISCARD_DIALOG)

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
