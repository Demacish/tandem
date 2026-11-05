package casa.moller.tandem.feature.recipes.editor

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.feature.recipes.PHONE
import casa.moller.tandem.feature.recipes.PHONE_TALL
import casa.moller.tandem.feature.recipes.TABLET
import casa.moller.tandem.feature.recipes.TestFrame
import casa.moller.tandem.feature.recipes.books.PHONE_LANDSCAPE
import casa.moller.tandem.feature.recipes.books.TABLET_PORTRAIT
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference images of the recipe editor (#22, plan §8.9; `src/test/screenshots/editor/`): a new
 * recipe empty and without a name, a stored recipe being edited (phone, tall phone, landscape,
 * tablet in both orientations, dark, font scale 2, Swedish and English), the kept edit after a
 * conflict, a guest, and the loading and unknown states.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class RecipeEditorScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun editingPicture() = capture("phone_editor_picture_light_sv", RecipeEditorShot.EDITING_PICTURE)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun editingPictureRemoved() = capture("phone_editor_picture_removed_light_sv", RecipeEditorShot.EDITING_PICTURE_REMOVED)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun newEmpty() = capture("phone_editor_new_light_sv", RecipeEditorShot.NEW_EMPTY)

    @Test
    fun newEmptyDark() = capture("phone_editor_new_dark", RecipeEditorShot.NEW_EMPTY, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun newNameMissing() = capture("phone_editor_new_name_missing_light_sv", RecipeEditorShot.NEW_NAME_MISSING)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun editing() = capture("phone_tall_editor_editing_light_sv", RecipeEditorShot.EDITING)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun editingDark() = capture("phone_tall_editor_editing_dark", RecipeEditorShot.EDITING, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun editingFontScale2() = capture("phone_tall_editor_editing_font2_light_sv", RecipeEditorShot.EDITING, fontScale = 2f)

    @Test
    @Config(qualifiers = PHONE_LANDSCAPE)
    fun editingLandscape() = capture("phone_landscape_editor_editing_light", RecipeEditorShot.EDITING)

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun editingTablet() = capture("tablet_editor_editing_light_sv", RecipeEditorShot.EDITING)

    @Test
    @Config(qualifiers = "sv-$TABLET_PORTRAIT")
    fun editingTabletPortrait() = capture("tablet_portrait_editor_editing_light_sv", RecipeEditorShot.EDITING)

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun editingKeptEdit() = capture("phone_tall_editor_kept_edit_light_sv", RecipeEditorShot.EDITING_KEPT_EDIT)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun editingGuest() = capture("phone_editor_guest_light_sv", RecipeEditorShot.EDITING_GUEST)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun loading() = capture("phone_editor_loading_light_sv", RecipeEditorShot.LOADING)

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun unknown() = capture("phone_editor_unknown_light_sv", RecipeEditorShot.UNKNOWN)

    private fun capture(name: String, shot: RecipeEditorShot, darkTheme: Boolean = false, fontScale: Float? = null) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, chrome = AppChrome(), fontScale = fontScale) { shot.content() } }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/editor/$name.png")
    }
}
