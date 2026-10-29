package casa.moller.tandem.core.testing

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.pantry.ExpiryChoice
import casa.moller.tandem.core.model.pantry.InventoryEntryDraft
import casa.moller.tandem.core.model.pantry.UnpackChoice
import casa.moller.tandem.core.model.scan.BarcodeLookupResult
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.MatchedFood
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.testing.data.FakeAiSettingsRepository
import casa.moller.tandem.core.testing.data.FakeBarcodeLookup
import casa.moller.tandem.core.testing.data.FakeDrinkRepository
import casa.moller.tandem.core.testing.data.FakeFoodAliasRepository
import casa.moller.tandem.core.testing.data.FakeFridgeVision
import casa.moller.tandem.core.testing.data.FakeMakeNowRepository
import casa.moller.tandem.core.testing.data.FakePantryRepository
import casa.moller.tandem.core.testing.data.FakeStaplesRepository
import casa.moller.tandem.core.testing.data.SamplePantry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fas S fakes and pure types behave like the contracts in core:data say (docs/BYGGE.md "Fas S – kontrakt"). */
class FasSFakesTest {
    private val today = SamplePantry.TODAY

    @Test
    fun thePantryListsStockByLocationSoonestFirstAndSlutLeavesIt() = runTest {
        val pantry = FakePantryRepository()
        val fridge = pantry.observePantry().first().first { it.location.id == SamplePantry.fridge.id }
        assertEquals(listOf("grädde", "mjölk"), fridge.entries.map { it.food.name })

        pantry.markEmpty(SamplePantry.cream.id)
        val after = pantry.observePantry().first().first { it.location.id == SamplePantry.fridge.id }
        assertEquals(listOf("mjölk"), after.entries.map { it.food.name })
        assertEquals(0.0, pantry.observeEntry(SamplePantry.cream.id).first()?.amount ?: -1.0, 0.0)
    }

    @Test
    fun minusOneStopsAtZeroAndNewEntriesGetTheDefaultUnit() = runTest {
        val pantry = FakePantryRepository()
        pantry.decrement(SamplePantry.milk.id)
        pantry.decrement(SamplePantry.milk.id)
        assertEquals(0.0, pantry.observeEntry(SamplePantry.milk.id).first()?.amount ?: -1.0, 0.0)

        val added = pantry.addEntry(InventoryEntryDraft(locationId = 1, foodName = "yoghurt")).getOrNull()
        assertEquals(-1L, added?.id)
        assertEquals(FakePantryRepository.DEFAULT_UNIT, added?.unit?.name)
    }

    @Test
    fun expiringEntriesAreTheInputOfUseUp() = runTest {
        val pantry = FakePantryRepository()
        assertEquals(listOf("grädde"), pantry.observeExpiring(today).first().map { it.food.name })
        assertTrue(SamplePantry.cream.expiresSoon(today))
        assertEquals(1, SamplePantry.cream.daysLeft(today))
        assertTrue(103L in pantry.observeAvailableFoodIds().first())
    }

    @Test
    fun unpackingPutsTheChosenItemsInThePantryAndSkipsTheRest() = runTest {
        val pantry = FakePantryRepository()
        val (eggs, butter) = SamplePantry.unpack
        val count = pantry.unpack(
            listOf(
                UnpackChoice(eggs, locationId = SamplePantry.fridge.id, expires = ExpiryChoice.PLUS_1_WEEK.dateFrom(today)),
                UnpackChoice(butter, locationId = SamplePantry.fridge.id, include = false),
            ),
        ).getOrNull()

        assertEquals(1, count)
        assertEquals(emptyList<Any>(), pantry.observeUnpackable(today).first())
        assertEquals(LocalDate(2026, 11, 5), pantry.entries.value.first { it.food.name == "ägg" }.expires)
    }

    @Test
    fun expiryChoicesDependOnTheLocation() {
        assertEquals(LocalDate(2026, 11, 1), ExpiryChoice.PLUS_3_DAYS.dateFrom(today))
        assertEquals(LocalDate(2026, 11, 29), ExpiryChoice.PLUS_1_MONTH.dateFrom(today))
        assertNull(ExpiryChoice.NONE.dateFrom(today))
        assertTrue(ExpiryChoice.PLUS_3_MONTHS in ExpiryChoice.forLocation(SamplePantry.freezer))
        assertFalse(ExpiryChoice.PLUS_3_MONTHS in ExpiryChoice.forLocation(SamplePantry.fridge))
    }

    @Test
    fun aGuestCannotWriteThePantryStaplesAliasesOrPairings() = runTest {
        val readOnly = DataProblem.READ_ONLY
        assertEquals(readOnly, FakePantryRepository(readOnly = true).markEmpty(11).errorOrNull()?.dataProblem())
        assertEquals(readOnly, FakeStaplesRepository(readOnly = true).addStaples(listOf("salt")).errorOrNull()?.dataProblem())
        val aliases = FakeFoodAliasRepository(readOnly = true)
        assertEquals(readOnly, aliases.learn("123", "mjölk", FoodAliasKind.BARCODE).errorOrNull()?.dataProblem())
        assertEquals(readOnly, FakeDrinkRepository(readOnly = true).addPairing(1, 51).errorOrNull()?.dataProblem())
    }

    @Test
    fun staplesAreMarkedOnHandInOneGo() = runTest {
        val staples = FakeStaplesRepository()
        staples.addStaples(listOf("Olja", "vatten"))
        staples.setOnHand(listOf("salt", "vatten"), onHand = false)

        val byName = staples.observeStaples().first().associateBy { it.name }
        assertEquals(listOf("olja", "salt", "svartpeppar", "vatten"), byName.keys.toList())
        assertFalse(byName.getValue("salt").onHand)
        assertTrue(byName.getValue("svartpeppar").onHand)
    }

    @Test
    fun makeNowHasThreeGroupsAndTheFilterApplies() = runTest {
        val makeNow = FakeMakeNowRepository()
        val state = makeNow.observe(MakeNowFilter(), today).first()
        assertEquals(listOf("Pannkakor"), state.recipes(MakeNowGroup.HAS_ALL).map { it.recipe.name })
        assertEquals(listOf("potatis"), makeNow.missingFoods(1).getOrNull()?.map { it.name })
        assertEquals(MakeNowGroup.MISSING_2, MakeNowGroup.of(2))
        assertNull(MakeNowGroup.of(3))

        val quick = makeNow.observe(MakeNowFilter(maxMinutes = 30), today).first()
        assertEquals(emptyList<Any>(), quick.recipes(MakeNowGroup.MISSING_1))
        assertEquals("Hummus", makeNow.surprise(MakeNowGroup.MISSING_2, MakeNowFilter()).getOrNull()?.recipe?.name)
    }

    @Test
    fun aliasesMatchWithoutCaseAndAreCutAt128() = runTest {
        val aliases = FakeFoodAliasRepository()
        assertEquals("grädde", aliases.find("grädde 36% 5dl")?.foodName)
        val long = aliases.learn("X".repeat(200), "mjölk", FoodAliasKind.RECEIPT).getOrNull()
        assertEquals(128, long?.alias?.length)
    }

    @Test
    fun barcodesAreLookedUpByTheirCodeOnly() = runTest {
        val lookup = FakeBarcodeLookup()
        assertTrue(lookup.lookup("2000000000022").getOrNull() is BarcodeLookupResult.Found)
        assertEquals(BarcodeLookupResult.NotFound, lookup.lookup("2000000000039").getOrNull())
        assertTrue(lookup.lookup("abc").errorOrNull() != null)
        assertEquals(listOf("2000000000022", "2000000000039", "abc"), lookup.lookups)
    }

    @Test
    fun pairingsWorkFromBothSides() = runTest {
        val drinks = FakeDrinkRepository()
        drinks.addPairing(recipeId = 1, keywordId = SamplePantry.pairing.keywordId)

        assertEquals(listOf("Rioja Crianza"), drinks.observeRecipePairings(1).first().drinks.map { it.name })
        assertEquals(listOf(1L), drinks.observeRecipePairings(SamplePantry.rioja.id).first().dishIds)
        assertTrue(drinks.observeIsDrink(SamplePantry.rioja.id).first())

        val created = drinks.createDrink(DrinkDraft("Cava", types = listOf("Vin"))).getOrNull()
        assertEquals(-1L, created?.id)
        assertEquals(listOf("Cava", "Rioja Crianza"), drinks.observeDrinks("Vin").first().map { it.name })
        assertEquals("Ny koppling", drinks.addNewPairing(1, "Ny koppling").getOrNull()?.name)
    }

    @Test
    fun aiSettingsNeverExposeTheKeyAndTheFeatureNeedsOne() = runTest {
        val ai = FakeAiSettingsRepository()
        assertFalse(ai.settings.first().fridgePhotoAvailable)

        ai.setProvider(AiProvider.CLAUDE)
        ai.storeKey(AiProvider.CLAUDE, " sk-test-not-a-real-key ")
        val settings = ai.settings.first()
        assertTrue(settings.fridgePhotoAvailable)
        assertEquals("claude-opus-5-5", settings.effectiveModel)
        assertFalse(settings.warningAcknowledged)
        assertFalse(settings.toString().contains("sk-test"))
        assertTrue(ai.storeKey(AiProvider.GEMINI, " ").errorOrNull() != null)
        assertTrue(ai.setCustomBaseUrl("http://example.com").errorOrNull() != null)

        ai.acknowledgeWarning()
        ai.removeKey(AiProvider.CLAUDE)
        assertEquals(AiSettings(provider = AiProvider.CLAUDE, hasKey = false, warningAcknowledged = true), ai.settings.first())
    }

    @Test
    fun theFridgePhotoGivesItemsOrATypedError() = runTest {
        val vision = FakeFridgeVision()
        val items = (vision.recognize(ByteArray(10), "sv") as FridgeVisionResult.Items).items
        assertEquals(listOf("mjölk", "ägg", "gurka"), items.map { it.name })
        assertTrue(vision.recognize(ByteArray(0), "sv") is FridgeVisionResult.Failed)

        vision.result = FridgeVisionResult.Failed(FridgeVisionError.InvalidKey)
        assertEquals(FridgeVisionResult.Failed(FridgeVisionError.InvalidKey), vision.recognize(ByteArray(1), "sv"))
    }

    @Test
    fun aReviewItemIsReadyWhenIncludedAndMatched() {
        val item = ScanReviewItem(key = "1", text = "GRÄDDE 36% 5DL", source = ScanSource.RECEIPT)
        assertFalse(item.ready)
        assertTrue(item.copy(food = MatchedFood(101, "grädde", FoodMatchVia.ALIAS)).ready)
        assertFalse(item.copy(food = MatchedFood(null, "grädde", FoodMatchVia.NEW), include = false).ready)
    }
}
