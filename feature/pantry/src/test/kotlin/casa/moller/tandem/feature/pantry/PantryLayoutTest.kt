package casa.moller.tandem.feature.pantry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.pantry.Devices.PHONE
import casa.moller.tandem.feature.pantry.Devices.PHONE_LANDSCAPE
import casa.moller.tandem.feature.pantry.Devices.PHONE_LANDSCAPE_KEYBOARD
import casa.moller.tandem.feature.pantry.Devices.TABLET
import casa.moller.tandem.feature.pantry.entry.PANTRY_ENTRY_DELETE_TAG
import casa.moller.tandem.feature.pantry.entry.PANTRY_ENTRY_FOOD_TAG
import casa.moller.tandem.feature.pantry.entry.PANTRY_ENTRY_SAVE_TAG
import casa.moller.tandem.feature.pantry.hemma.PantryScreen
import casa.moller.tandem.feature.pantry.hemma.pantryRowTag
import casa.moller.tandem.feature.pantry.makenow.makeNowRowTag
import casa.moller.tandem.feature.pantry.staples.STAPLES_ADD_TAG
import casa.moller.tandem.feature.pantry.staples.STAPLES_SAVE_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas S review (fix-ui): layouts at their limits. Kan laga nu on a tablet at font scale 2 with
 * every notice still reaches its recipes; the entry editor and Basvaror keep the field being typed
 * in visible in landscape with the keyboard open; Hemma's rows are lazy items on a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE")
class PantryLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(shot: PantryShot, fontScale: Float = 1f) {
        composeRule.setContent { PantryTestTheme(fontScale = fontScale) { shot.content() } }
    }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun theTabletColumnsReachTheirRecipesBelowEveryNotice() {
        show(PantryShot.MAKE_NOW_CROWDED, fontScale = 2f)
        composeRule.onNodeWithTag(makeNowRowTag(PantrySamples.creamSauce.id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE_KEYBOARD")
    fun theEntryEditorKeepsItsFieldsAboveTheKeyboard() {
        show(PantryShot.ENTRY_EDIT_KEYBOARD)
        composeRule.onNodeWithTag(PANTRY_ENTRY_SAVE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(PANTRY_ENTRY_FOOD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PANTRY_ENTRY_DELETE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE")
    fun inLandscapeOnlySaveIsPinned() {
        show(PantryShot.ENTRY_EDIT)
        composeRule.onNodeWithTag(PANTRY_ENTRY_SAVE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PANTRY_ENTRY_FOOD_TAG).assertIsDisplayed()
        // "Ta bort varan" is at the end of the form, not in the bar.
        composeRule.onNodeWithTag(PANTRY_ENTRY_DELETE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_LANDSCAPE_KEYBOARD")
    fun basvarorKeepsItsFieldAboveTheKeyboard() {
        show(PantryShot.STAPLES_KEYBOARD)
        composeRule.onNodeWithTag(STAPLES_SAVE_TAG).assertDoesNotExist()
        // The field is the list's last (lazy) item: scroll the list to it.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(STAPLES_ADD_TAG))
        composeRule.onNodeWithTag(STAPLES_ADD_TAG).assertIsDisplayed()
    }

    @Test
    fun aPhoneComposesHemmaRowByRow() {
        composeRule.setContent { PantryTestTheme { PantryScreen(state = PantryStates.hemma(), onAction = {}) } }
        composeRule.onNodeWithTag(pantryRowTag(PantrySamples.cream.id)).assertIsDisplayed()
    }
}
