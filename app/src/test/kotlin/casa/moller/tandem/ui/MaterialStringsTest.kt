package casa.moller.tandem.ui

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Device test 2026-11-14 (B13): TalkBack read the scrim behind a bottom sheet as "Stäng
 * kalkylarket" (Compose UI's Swedish "Close sheet") and every snackbar as "Varning". The app's
 * values-sv/strings_material.xml replaces them; these tests read the strings as the merged app
 * resolves them, through the libraries' own resource ids, and the scrim of a real
 * [ModalBottomSheet].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "sv-w412dp-h915dp-port-xhdpi")
class MaterialStringsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun theSheetsScrimIsASheetNotASpreadsheet() {
        assertEquals("Stäng arket", resources.getString(androidx.compose.ui.R.string.close_sheet))
    }

    @Test
    fun aSnackbarIsAMessageNotAWarning() {
        assertEquals("Meddelande", resources.getString(androidx.compose.material3.R.string.m3c_snackbar_pane_title))
    }

    @Test
    @Config(application = Application::class, qualifiers = "en-w412dp-h915dp-port-xhdpi")
    fun englishKeepsTheLibrarysText() {
        assertEquals("Close sheet", resources.getString(androidx.compose.ui.R.string.close_sheet))
        assertEquals("Alert", resources.getString(androidx.compose.material3.R.string.m3c_snackbar_pane_title))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun talkBackHearsTheNewNameOnARealSheet() {
        composeRule.setContent {
            TandemTheme {
                ModalBottomSheet(onDismissRequest = {}) { Text("Lägg på listan") }
            }
        }
        composeRule.onNodeWithText("Lägg på listan").assertExists()
        composeRule.onNodeWithContentDescription("Stäng arket").assertExists()
        composeRule.onNodeWithContentDescription("Stäng kalkylarket").assertDoesNotExist()
    }
}
