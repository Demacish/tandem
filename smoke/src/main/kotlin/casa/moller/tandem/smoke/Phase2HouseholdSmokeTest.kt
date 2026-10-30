package casa.moller.tandem.smoke

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test of the household on Handla (Fas S rester lane, Phase 2's open issue: no
 * scenario covered "Delad med: …" or the household warning in the R8-minified build; plan 8.6,
 * docs/BYGGE.md "Release-röktest"):
 *
 * - In a household ([FakeTandoor.household], the default): the first sync reads the role with the household
 *   (`user-space/all_personal/`) and, for the admin, the space's memberships (`user-space/`), so
 *   Handla says "Shared with contract-reader" and shows no warning.
 * - In no household (as recorded, the space's household setup not finished): Handla shows "You are
 *   not in a household" and no "Shared with", right after the first sync (before this test the
 *   warning waited for the periodic sync's version check, `ServerStatus.capabilitiesResolved`).
 */
@RunWith(AndroidJUnit4::class)
class Phase2HouseholdSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    @Test
    fun inAHousehold_handlaSaysWhoItIsSharedWith_andNoWarning() {
        logIn()
        app.tap("Shopping")

        app.waitForNode("\"$SHARED_WITH\"", HOUSEHOLD_TIMEOUT_MS) { it.says(SHARED_WITH) }
        app.capture("household-shared-with")
        assertTrue("No household warning in a household", app.findNode { it.says(NO_HOUSEHOLD) } == null)
        assertTrue("The memberships were read: ${tandoor.requests}", tandoor.requests.any { it == "GET /api/user-space/" })
    }

    @Test
    fun inNoHousehold_handlaShowsTheWarning_andNoSharedWith() {
        tandoor.household = false
        logIn()
        app.tap("Shopping")

        app.waitForNode("\"$NO_HOUSEHOLD\"", HOUSEHOLD_TIMEOUT_MS) { it.says(NO_HOUSEHOLD) }
        app.capture("household-missing")
        assertTrue("Nothing is shared without a household", app.findNode { it.says("Shared with") } == null)
    }

    /** The node's text or description contains [text] (a merged row reads as one node). */
    private fun AccessibilityNodeInfo.says(text: String): Boolean = listOfNotNull(this.text, contentDescription).any { text in it }

    private companion object {
        /** core:ui `ui_shared_with_one` with the other member of the recorded household. */
        const val SHARED_WITH = "Shared with contract-reader"

        /** core:ui `ui_household_missing_title`. */
        const val NO_HOUSEHOLD = "You are not in a household"
        const val HOUSEHOLD_TIMEOUT_MS = 30_000L
    }
}
