package casa.moller.tandem.feature.shopping

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * fix-ui (Phase 2 review): a change by another household member never takes the user's "Ångra"
 * away (the 20 s poll while two people shop), and the user's own message always wins.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = Devices.PHONE)
class ShoppingSnackbarsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var snackbars: ShoppingSnackbars
    private lateinit var host: SnackbarHostState

    private fun setUp() {
        composeRule.setContent {
            ShoppingTestTheme {
                host = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                snackbars = remember { ShoppingSnackbars(host, scope) }
                SnackbarHost(host)
            }
        }
    }

    @Test
    fun anotherMembersChangeLeavesTheUndoSnackbarAlone() {
        setUp()
        var undone = 0
        composeRule.runOnIdle { snackbars.showOwn("mjölk bockades av", actionLabel = "Ångra") { undone++ } }
        composeRule.onNodeWithText("Ångra").assertIsDisplayed()

        composeRule.runOnIdle {
            snackbars.showOther("Anna lade till kaffe")
            snackbars.showOther("3 ändringar från andra i hushållet")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("mjölk bockades av").assertIsDisplayed()
        composeRule.onAllNodesWithText("Anna lade till kaffe").assertCountEquals(0)
        composeRule.onNodeWithText("Ångra").performClick()
        composeRule.waitForIdle()
        assertEquals(1, undone)
    }

    @Test
    fun withoutAnUndoTheChangeIsShownAndTheUsersOwnMessageReplacesIt() {
        setUp()
        composeRule.runOnIdle { snackbars.showOther("Anna lade till kaffe") }
        composeRule.onNodeWithText("Anna lade till kaffe").assertIsDisplayed()

        composeRule.runOnIdle { snackbars.showOwn("bröd lades till", actionLabel = "Ångra") {} }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("bröd lades till").assertIsDisplayed()
        composeRule.onAllNodesWithText("Anna lade till kaffe").assertCountEquals(0)
    }
}
