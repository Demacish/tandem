package casa.moller.tandem.feature.pantry

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.feature.pantry.Devices.PHONE_TALL
import casa.moller.tandem.feature.pantry.entry.PANTRY_ENTRY_SAVE_TAG
import casa.moller.tandem.feature.pantry.entry.PantryEntryAction
import casa.moller.tandem.feature.pantry.entry.PantryEntryScreen
import casa.moller.tandem.feature.pantry.hemma.PantryAction
import casa.moller.tandem.feature.pantry.hemma.PantryScreen
import casa.moller.tandem.feature.pantry.hemma.pantryOnHandTag
import casa.moller.tandem.feature.pantry.hemma.pantryRowTag
import casa.moller.tandem.feature.pantry.makenow.MakeNowAction
import casa.moller.tandem.feature.pantry.makenow.MakeNowScreen
import casa.moller.tandem.feature.pantry.makenow.makeNowAddMissingTag
import casa.moller.tandem.feature.pantry.navigation.PantryScanner
import casa.moller.tandem.feature.pantry.unpack.UNPACK_SAVE_TAG
import casa.moller.tandem.feature.pantry.unpack.UnpackAction
import casa.moller.tandem.feature.pantry.unpack.UnpackScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI tests of the Hemma screens (Fas S, hemma-ui lane): a pantry row is ONE TalkBack item
 * with its actions, a guest gets none, the empty state leads to the scanners, "Packa upp" saves and
 * "Kan laga nu" asks for the missing foods and adds them to the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE_TALL")
class PantryScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hasNoCustomActions = SemanticsMatcher("has no TalkBack actions") { SemanticsActions.CustomActions !in it.config }

    @Test
    fun aPantryRowIsReadAsOneItemWithItsActions() {
        val actions = mutableListOf<PantryAction>()
        composeRule.setContent { PantryTestTheme { PantryScreen(state = PantryStates.hemma(), onAction = { actions += it }) } }
        val cream = composeRule.onNodeWithTag(pantryRowTag(PantrySamples.cream.id))
        cream.assert(hasContentDescription("Grädde, 3 dl, Kyl, går ut i morgon"))
        composeRule.onNodeWithTag(pantryRowTag(PantrySamples.leftovers.id))
            .assert(hasContentDescription("Köttfärssås, 1 låda, Kyl, gick ut i går, Från söndagen"))
        composeRule.onNodeWithTag(pantryRowTag(PantrySamples.yoghurt.id))
            .assert(hasContentDescription("Turkisk yoghurt, 5 dl, Kyl, går ut om 2 dagar, väntar på att skickas"))

        cream.performCustomAction("Slut")
        cream.performCustomAction("En mindre")
        cream.performCustomAction("Flytta")
        cream.performClick()
        assertEquals(
            listOf(
                PantryAction.MarkEmpty(PantrySamples.cream.id),
                PantryAction.Decrement(PantrySamples.cream.id),
                PantryAction.StartMove(PantrySamples.cream.id),
                PantryAction.OpenEntry(PantrySamples.cream.id),
            ),
            actions,
        )
    }

    @Test
    fun aGuestGetsNoActionsAndAnExplanation() {
        val actions = mutableListOf<PantryAction>()
        val state = PantryStates.hemma(readOnly = true)
        composeRule.setContent { PantryTestTheme { PantryScreen(state = state, onAction = { actions += it }) } }
        composeRule.onNodeWithText("Du är gäst").assertIsDisplayed()
        composeRule.onNodeWithTag(pantryRowTag(PantrySamples.cream.id)).assert(hasNoCustomActions)
        composeRule.onNodeWithTag(pantryRowTag(PantrySamples.cream.id)).performClick()
        assertTrue(composeRule.onAllNodesWithText("Lägg till").fetchSemanticsNodes().isEmpty())
        assertTrue(actions.none { it is PantryAction.OpenEntry })
    }

    @Test
    fun theEmptyPantrySaysHowToFillIt() {
        val actions = mutableListOf<PantryAction>()
        composeRule.setContent {
            PantryTestTheme { PantryScreen(state = PantryStates.hemma(entries = emptyList()), onAction = { actions += it }) }
        }
        composeRule.onNodeWithText("Skafferiet är tomt").assertIsDisplayed()
        composeRule.onAllNodesWithText("Skanna streckkod").onFirst().performClick()
        composeRule.onNodeWithText("Lägg till för hand").performClick()
        assertTrue(PantryAction.OpenScanner(PantryScanner.BARCODE, null) in actions)
        assertTrue(PantryAction.AddEntry(null) in actions)
    }

    @Test
    fun onHandFoodsCanBeTakenOffWithTalkBack() {
        val actions = mutableListOf<PantryAction>()
        composeRule.setContent { PantryTestTheme { PantryScreen(state = PantryStates.hemma(), onAction = { actions += it }) } }
        val honey = composeRule.onNodeWithTag(pantryOnHandTag("honung"))
        honey.assert(hasContentDescription("Honung, finns hemma"))
        honey.performCustomAction("Inte hemma längre")
        assertEquals("honung", (actions.single() as PantryAction.RemoveOnHand).food.name)
    }

    @Test
    fun theEntryPageSaves() {
        val actions = mutableListOf<PantryEntryAction>()
        composeRule.setContent { PantryTestTheme { PantryEntryScreen(state = PantryStates.entryNew, onAction = { actions += it }) } }
        composeRule.onNodeWithText("grädde").performClick()
        composeRule.onNodeWithText("+1 månad").performClick()
        composeRule.onNodeWithTag(PANTRY_ENTRY_SAVE_TAG).performClick()
        assertTrue(actions.any { it is PantryEntryAction.PickFood && it.food.name == "grädde" })
        assertTrue(PantryEntryAction.PickExpiry(ExpiryChoice.PLUS_1_MONTH) in actions)
        assertEquals(PantryEntryAction.Save, actions.last())
    }

    @Test
    fun unpackSavesWhatIsTicked() {
        val actions = mutableListOf<UnpackAction>()
        composeRule.setContent { PantryTestTheme { UnpackScreen(state = PantryStates.unpack, onAction = { actions += it }) } }
        composeRule.onNodeWithText("Lägg in 2 varor").assertIsDisplayed()
        composeRule.onNodeWithTag(UNPACK_SAVE_TAG).performClick()
        composeRule.onNodeWithText("Hoppa över").performClick()
        assertEquals(listOf(UnpackAction.Save, UnpackAction.Skip), actions)
    }

    @Test
    fun makeNowAddsTheMissingFoodsAndAsksForUnknownOnes() {
        val actions = mutableListOf<MakeNowAction>()
        val state = PantryStates.makeNow(selected = MakeNowGroup.MISSING_2)
        composeRule.setContent { PantryTestTheme { MakeNowScreen(state = state, onAction = { actions += it }) } }
        composeRule.onNodeWithText("Saknas: tahini, citron").assertIsDisplayed()
        composeRule.onNodeWithTag(makeNowAddMissingTag(PantrySamples.hummus.id)).performClick()
        assertTrue(MakeNowAction.AddMissingToList(PantrySamples.hummus.id) in actions)
        composeRule.onNodeWithText("Har allt (3)").performClick()
        assertTrue(MakeNowAction.SelectGroup(MakeNowGroup.HAS_ALL) in actions)
    }
}
