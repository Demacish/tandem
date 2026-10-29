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
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryUnit
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.feature.cook.PHONE
import casa.moller.tandem.feature.cook.TestFrame
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Tog något slut?" after "Klart!" (Fas S, hemma-ui lane, plan §8.8): the card on a phone (also dark
 * and at font scale 2), one TalkBack item per row, and its accessibility checks.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-$PHONE")
class CookPantryTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The pasta's butter and salt in the pantry. */
    private val butter = InventoryEntry(21, 1, PantryFood(2, "smör"), PantryUnit(4, "paket"), 2.0, LocalDate(2026, 11, 20))
    private val salt = InventoryEntry(22, 3, PantryFood(5, "salt"), PantryUnit(5, "st"), 1.0)

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
        ),
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
