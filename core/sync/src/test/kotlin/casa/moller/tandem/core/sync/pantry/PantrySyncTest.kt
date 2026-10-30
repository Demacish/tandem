package casa.moller.tandem.core.sync.pantry

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.pantry.InventoryEntryDraft
import casa.moller.tandem.core.model.pantry.InventoryEntryUpdate
import casa.moller.tandem.core.model.pantry.UnpackChoice
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.shopping.DroppedChanges
import casa.moller.tandem.core.sync.shopping.FakeShoppingTandoor
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas S (hemma-data lane): the pantry's data and sync (#26, #46, plan §8.8, 3.5, 5.7) against a
 * stateful Tandoor answering in the recorded shapes of BOTH 2.6.13 and 2.7.1 ([FakePantryTandoor]):
 * offline creates, edits and deletes and their drain (temporary ids, a location created offline,
 * merges), the household scope of a space-wide list, the default locations on first use, "Packa
 * upp", entries deleted elsewhere, guests, accounts and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PantrySyncTest {
    private fun forEachVersion(block: suspend PantrySyncEnv.() -> Unit) = PantrySyncEnv.forEachVersion(block)

    @Test
    fun offlineCreatesEditsAndDeletesDrainAsFewRequests() = forEachVersion {
        val today = graph.clock.today()
        offline()
        val fridge = value(pantry.createLocation("Kyl", isFreezer = false))
        assertTrue(fridge.isLocalOnly)
        val tomorrow = today.plus(DatePeriod(days = 1))
        val cream = value(pantry.addEntry(InventoryEntryDraft(fridge.id, "grädde", amount = 2.0, unitName = "dl", expires = tomorrow)))
        ok(pantry.updateEntry(cream.id, InventoryEntryUpdate(note = "Öppnad")))
        // Created and marked "Slut" before the sync: ONE create with amount 0.
        ok(pantry.markEmpty(cream.id))
        val milk = value(pantry.addEntry(InventoryEntryDraft(fridge.id, "mjölk")))
        // Created and deleted offline: nothing at all.
        ok(pantry.deleteEntry(milk.id))
        val butter = value(pantry.addEntry(InventoryEntryDraft(fridge.id, "smör", amount = 3.0)))
        ok(pantry.decrement(butter.id))
        assertEquals("[$version] location + two entries", 3, outboxCount())
        assertEquals(listOf("smör"), pantry.observePantry().first().single().entries.map { it.food.name })

        online()
        drainAll()

        val location = tandoor.locations().single()
        assertEquals("Kyl", location.name)
        assertEquals(FakePantryTandoor.OWN_HOUSEHOLD, location.householdId)
        val entries = tandoor.entries().associateBy { it.foodName }
        assertEquals(setOf("grädde", "smör"), entries.keys)
        assertEquals(0.0, entries.getValue("grädde").amount, 0.0)
        assertEquals("Öppnad", entries.getValue("grädde").note)
        assertEquals("dl", entries.getValue("grädde").unit)
        assertEquals(tomorrow.toString(), entries.getValue("grädde").expires)
        assertEquals(2.0, entries.getValue("smör").amount, 0.0)
        // No unit chosen: the default unit (Tandoor requires one, FACTS "Fas S b").
        assertEquals("st", entries.getValue("smör").unit)
        assertTrue(entries.values.all { it.locationId == location.id })
        assertEquals(2, tandoor.requests("POST", "/api/inventory-entry/").size)
        assertTrue(tandoor.requests("PATCH", "/api/inventory-entry/").isEmpty())
        assertTrue(tandoor.requests("DELETE", "/api/inventory-entry/").isEmpty())
        assertTrue(tandoor.notFound.isEmpty())

        // The screen's old temporary ids still find the entries.
        val sent = pantry.observeEntry(cream.id).first()
        assertNotNull("[$version] temporary id resolves", sent)
        assertEquals(entries.getValue("grädde").id, sent!!.id)
        assertEquals(false, sent.pending)
        assertEquals(location.id, pantry.observeLocations().first().single().id)
    }

    @Test
    fun anEntryMovesToALocationCreatedOfflineWithTheWholeLocation() = forEachVersion {
        val fridge = tandoor.seedLocation("Kyl")
        val chickpeas = tandoor.seedEntry(fridge, "kikärtor", amount = 2.0, by = FakePantryTandoor.OWN_USER)
        read()

        offline()
        val freezer = value(pantry.createLocation("Frys", isFreezer = true))
        ok(pantry.updateEntry(chickpeas, InventoryEntryUpdate(locationId = freezer.id)))
        online()
        drainAll()

        val created = tandoor.locations().single { it.name == "Frys" }
        assertTrue(created.isFreezer)
        assertEquals(created.id, tandoor.entries().single().locationId)
        val (_, body) = tandoor.requests("PATCH", "/api/inventory-entry/").single()
        val location = Json.parseToJsonElement(body).jsonObject.getValue("inventory_location").jsonObject
        assertEquals(created.id.toString(), location.getValue("id").jsonPrimitive.content)
        assertEquals("Frys", location.getValue("name").jsonPrimitive.content)
        assertEquals("true", location.getValue("is_freezer").jsonPrimitive.content)
        val household = location.getValue("household") as JsonObject
        assertEquals(FakePantryTandoor.OWN_HOUSEHOLD_NAME, household.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun onlyTheAccountsHouseholdIsShownFromTheSpaceWideLists() = forEachVersion {
        val own = tandoor.seedLocation("Kyl")
        val other = tandoor.seedLocation("Kyl", householdId = FakePantryTandoor.OTHER_HOUSEHOLD)
        tandoor.seedEntry(own, "kikärtor", amount = 2.0)
        tandoor.seedEntry(other, "blåbär")
        read()

        assertEquals(listOf(own), pantry.observeLocations().first().map { it.id })
        assertEquals(listOf("kikärtor"), pantry.observePantry().first().flatMap { group -> group.entries.map { it.food.name } })
        assertEquals(setOf(tandoor.foodId("kikärtor")), pantry.observeAvailableFoodIds().first())
    }

    @Test
    fun harHemmaListsOnHandFoodsWithoutStockInTheHousehold() = forEachVersion {
        // hemma-ui lane: Hemma's "Har hemma" (observeOnHandFoods).
        val own = tandoor.seedLocation("Kyl")
        val other = tandoor.seedLocation("Kyl", householdId = FakePantryTandoor.OTHER_HOUSEHOLD)
        tandoor.seedEntry(own, "kikärtor", amount = 2.0)
        tandoor.seedEntry(other, "blåbär")
        read()
        graph.real.referenceDao().replaceFoods(
            ACCOUNT_ID,
            listOf(
                FoodEntity(ACCOUNT_ID, tandoor.foodId("kikärtor"), "kikärtor", onHand = true),
                FoodEntity(ACCOUNT_ID, tandoor.foodId("blåbär"), "blåbär", onHand = true),
                FoodEntity(ACCOUNT_ID, 9001, "salt", onHand = true),
                FoodEntity(ACCOUNT_ID, 9002, "socker", onHand = false),
            ),
        )

        // Stocked in the household's own location: not repeated; stocked only elsewhere: listed.
        assertEquals(listOf("blåbär", "salt"), pantry.observeOnHandFoods().first().map { it.name })
    }

    @Test
    fun theDefaultLocationsAreCreatedOnceOnFirstUse() = forEachVersion {
        // Another household's locations do not count.
        tandoor.seedLocation("Pantry", householdId = FakePantryTandoor.OTHER_HOUSEHOLD)
        ok(pantry.refresh())
        drainAll()
        val own = tandoor.locations().filter { it.householdId == FakePantryTandoor.OWN_HOUSEHOLD }
        assertEquals(listOf("Kyl", "Frys", "Skafferi"), own.map { it.name })
        assertEquals(listOf(false, true, false), own.map { it.isFreezer })

        // Deleted again: not brought back (once per household on this device).
        own.forEach { location -> ok(pantry.deleteLocation(location.id)) }
        drainAll()
        ok(pantry.refresh())
        assertEquals(0, outboxCount())
        assertTrue(tandoor.locations().none { it.householdId == FakePantryTandoor.OWN_HOUSEHOLD })
    }

    @Test
    fun anEntryDeletedElsewhereDrainsWithoutA404() = forEachVersion {
        val fridge = tandoor.seedLocation("Kyl")
        val cream = tandoor.seedEntry(fridge, "grädde", amount = 2.0)
        val milk = tandoor.seedEntry(fridge, "mjölk", amount = 1.0)
        read()
        ok(pantry.markEmpty(cream))
        ok(pantry.decrement(milk))
        tandoor.partnerDeleteEntry(cream)
        tandoor.partnerDeleteEntry(milk)

        drainAll()

        assertTrue(tandoor.requests("PATCH", "/api/inventory-entry/").isEmpty())
        assertTrue(tandoor.notFound.isEmpty())
        assertNull(pantry.observeEntry(cream).first())
        assertNull(pantry.observeEntry(milk).first())
    }

    @Test
    fun aSlutEntryLeavesThePantryAndTheNextReadKeepsQueuedChanges() = forEachVersion {
        val fridge = tandoor.seedLocation("Kyl")
        val cream = tandoor.seedEntry(fridge, "grädde", amount = 2.0)
        read()
        offline()
        ok(pantry.markEmpty(cream))
        assertTrue(pantry.observePantry().first().single().entries.isEmpty())
        online()
        // A read before the change is sent does not bring the old amount back.
        read()
        assertEquals(0.0, pantry.observeEntry(cream).first()!!.amount, 0.0)
        drainAll()
        assertEquals(0.0, tandoor.entries().single().amount, 0.0)
        // After the next read the "Slut" entry is gone from the device (the list leaves out amount 0).
        read()
        assertNull(pantry.observeEntry(cream).first())
    }

    @Test
    fun unpackingPutsTodaysCheckedItemsInThePantryOnce() = forEachVersion {
        val today = graph.clock.today()
        val fridge = tandoor.seedLocation("Kyl")
        read()
        val now = graph.clock.nowMillis()
        graph.real.shoppingDao().upsertEntries(
            listOf(
                ShoppingEntryEntity(
                    ACCOUNT_ID,
                    11,
                    11,
                    foodId = 7,
                    foodName = "grädde",
                    unitName = "dl",
                    amount = 3.0,
                    checked = true,
                    completedAt = now,
                ),
                ShoppingEntryEntity(ACCOUNT_ID, 12, 12, foodName = "mjölk", checked = true, completedAt = now - 1.days.inWholeMilliseconds),
                ShoppingEntryEntity(ACCOUNT_ID, 13, 13, foodName = "ost"),
                ShoppingEntryEntity(ACCOUNT_ID, 14, 14, foodName = "bröd", checked = true, completedAt = now),
                // Checked today, but by the partner (another phone): never offered here (Fas S review, fix-data).
                ShoppingEntryEntity(ACCOUNT_ID, 15, 15, foodName = "ägg", checked = true, completedAt = now),
            ),
        )
        // What this phone checked (OfflineFirstShoppingRepository.setChecked records it).
        graph.real.syncStateDao().upsert(
            SyncStateEntity(
                accountId = ACCOUNT_ID,
                entity = OfflineFirstPantryRepository.CHECKED_HERE_KEY,
                hash = UnpackedToday.encode(today, listOf(11L, 12L, 14L)),
            ),
        )

        val items = pantry.observeUnpackable(today).first()
        assertEquals(setOf("grädde", "bröd"), items.map { it.food.name }.toSet())
        val choices = listOf(
            UnpackChoice(items.first { it.food.name == "grädde" }, fridge, expires = today.plus(DatePeriod(days = 3))),
            UnpackChoice(items.first { it.food.name == "bröd" }, fridge, include = false),
        )
        assertEquals(1, value(pantry.unpack(choices)))
        assertTrue(pantry.observeUnpackable(today).first().isEmpty())

        drainAll()
        val entry = tandoor.entries().single()
        assertEquals("grädde", entry.foodName)
        assertEquals(3.0, entry.amount, 0.0)
        assertEquals("dl", entry.unit)
        assertEquals(today.plus(DatePeriod(days = 3)).toString(), entry.expires)
        // The next day the list starts over.
        assertTrue(pantry.observeUnpackable(today.plus(DatePeriod(days = 1))).first().isEmpty())
    }

    /** Fas S review (fix-data): an item unpacked by its temporary list id is not offered again once the list entry is sent. */
    @Test
    fun anItemUnpackedBeforeItsListEntryWasSentIsNotOfferedAgain() = forEachVersion {
        // The shopping fake answers the list; the pantry fake goes in front of it again.
        FakeShoppingTandoor(version, graph.clock).install(graph.server)
        tandoor.install(graph.server)
        val today = graph.clock.today()
        val fridge = tandoor.seedLocation("Kyl")
        read()
        offline()
        val milk = value(graph.shopping.add(ShoppingEntryDraft("mjölk", amount = 1.0)))
        assertTrue("[$version] created offline", milk < 0)
        ok(graph.shopping.setChecked(listOf(milk), checked = true))
        val item = pantry.observeUnpackable(today).first().single()
        assertEquals(1, value(pantry.unpack(listOf(UnpackChoice(item, fridge)))))
        assertTrue(pantry.observeUnpackable(today).first().isEmpty())

        online()
        drainAll()

        val entry = graph.real.shoppingDao().getEntries(ACCOUNT_ID).single()
        assertTrue("[$version] the list entry has its server id: $entry", entry.localId > 0)
        assertTrue("[$version] not offered again", pantry.observeUnpackable(today).first().isEmpty())
        assertEquals(1, tandoor.entries().size)
    }

    /** Fas S review (fix-data): a member in no household sees no other household's pantry and cannot change it. */
    @Test
    fun withoutAHouseholdNoOtherHouseholdsPantryIsShownOrChanged() = forEachVersion {
        val theirs = tandoor.seedLocation("Kyl", householdId = FakePantryTandoor.OTHER_HOUSEHOLD)
        val cream = tandoor.seedEntry(theirs, "grädde", amount = 2.0)
        graph.real.accountDao().upsert(graph.real.accountDao().get(ACCOUNT_ID)!!.copy(householdId = null, householdName = null))
        read()

        assertTrue(pantry.observePantry().first().isEmpty())
        assertTrue(pantry.observeLocations().first().isEmpty())
        assertTrue(pantry.observeAvailableFoodIds().first().isEmpty())
        assertTrue(pantry.markEmpty(cream) is Outcome.Failure)
        assertTrue(pantry.decrement(cream) is Outcome.Failure)
        ok(pantry.deleteEntry(cream))
        assertTrue(pantry.renameLocation(theirs, "Min kyl", isFreezer = false) is Outcome.Failure)
        assertTrue(pantry.addEntry(InventoryEntryDraft(theirs, "mjölk")) is Outcome.Failure)
        assertEquals(0, outboxCount())
        drainAll()
        assertEquals(2.0, tandoor.entries().single().amount, 0.0)
        assertEquals("Kyl", tandoor.locations().single().name)
    }

    /** Fas S review (fix-data): two phones opening Hemma for the first time at once share one Kyl, Frys and Skafferi. */
    @Test
    fun twoPhonesOpeningHemmaAtOnceShareTheDefaultLocations() = forEachVersion {
        val phoneB = SyncTestGraph(server = graph.server, databasePrefix = "b-", serverVersion = version)
        try {
            PantrySyncEnv.mirror(phoneB)
            // Both read an empty pantry and queue the defaults before either is sent.
            ok(pantry.ensureDefaultLocations(graph.remote))
            ok(phoneB.pantry.ensureDefaultLocations(phoneB.remote))
            val bFridge = phoneB.pantry.observeLocations().first().single { it.name == "Kyl" }
            value(phoneB.pantry.addEntry(InventoryEntryDraft(bFridge.id, "mjölk")))

            drainAll()
            for (attempt in 1..10) {
                val result = phoneB.outbox.drain(phoneB.remote)
                if (result == DrainResult.DONE && phoneB.real.outboxDao().observeCount(ACCOUNT_ID).first() == 0) break
            }

            assertEquals(0, phoneB.real.outboxDao().observeCount(ACCOUNT_ID).first())
            val own = tandoor.locations().filter { it.householdId == FakePantryTandoor.OWN_HOUSEHOLD }
            assertEquals(listOf("Kyl", "Frys", "Skafferi"), own.map { it.name })
            val fridge = own.single { it.name == "Kyl" }
            assertEquals(fridge.id, tandoor.entries().single().locationId)
            assertEquals(3, tandoor.requests("POST", "/api/inventory-location/").size)
        } finally {
            // Not phoneB.close(): the server is this phone's too.
            phoneB.scope.cancel()
            phoneB.real.close()
            phoneB.demoDatabase.close()
        }
    }

    /** Fas S review (fix-data): an entry moved offline to a location created after it never stalls the outbox. */
    @Test
    fun anEntryMovedToALaterLocationOfflineIsSentWithIt() = forEachVersion {
        offline()
        val fridge = value(pantry.createLocation("Kyl", isFreezer = false))
        val cream = value(pantry.addEntry(InventoryEntryDraft(fridge.id, "grädde")))
        val freezer = value(pantry.createLocation("Frys", isFreezer = true))
        ok(pantry.updateEntry(cream.id, InventoryEntryUpdate(locationId = freezer.id)))
        val butter = value(pantry.addEntry(InventoryEntryDraft(fridge.id, "smör")))
        assertTrue(butter.id < 0)
        online()

        drainAll()

        val locations = tandoor.locations().associateBy { it.name }
        assertEquals(setOf("Kyl", "Frys"), locations.keys)
        val entries = tandoor.entries().associateBy { it.foodName }
        assertEquals(locations.getValue("Frys").id, entries.getValue("grädde").locationId)
        assertEquals(locations.getValue("Kyl").id, entries.getValue("smör").locationId)
    }

    /** Fas S review (fix-data): an entry nests the server's current location, so a partner's rename on the web stays. */
    @Test
    fun anEntryNeverRenamesTheLocationBackToTheStoredName() = forEachVersion {
        val fridge = tandoor.seedLocation("Kyl")
        val chickpeas = tandoor.seedEntry(fridge, "kikärtor", amount = 2.0, by = FakePantryTandoor.OWN_USER)
        val freezer = tandoor.seedLocation("Frys", isFreezer = true)
        read()
        tandoor.partnerEditLocation(fridge, "Kylskåp")
        tandoor.partnerEditLocation(freezer, "Frysboxen", isFreezer = false)

        value(pantry.addEntry(InventoryEntryDraft(fridge, "mjölk")))
        ok(pantry.updateEntry(chickpeas, InventoryEntryUpdate(locationId = freezer)))
        drainAll()

        val names = tandoor.locations().associate { it.id to it.name }
        assertEquals("Kylskåp", names[fridge])
        assertEquals("Frysboxen", names[freezer])
        assertEquals(false, tandoor.locations().single { it.id == freezer }.isFreezer)
        val (_, body) = tandoor.requests("POST", "/api/inventory-entry/").single()
        val nested = Json.parseToJsonElement(body).jsonObject.getValue("inventory_location").jsonObject
        assertEquals("Kylskåp", nested.getValue("name").jsonPrimitive.content)
        assertEquals(FakePantryTandoor.OWN_HOUSEHOLD_NAME, nested.getValue("household").jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue("[$version] the device-only field is never sent", "tandem_" !in body)
        // The device shows the server's names now.
        assertEquals(setOf("Kylskåp", "Frysboxen"), pantry.observeLocations().first().map { it.name }.toSet())
    }

    /** Fas S review (fix-data): an entry added offline to a location deleted elsewhere is dropped, never re-created there. */
    @Test
    fun anEntryForALocationDeletedElsewhereIsDropped() = forEachVersion {
        val fridge = tandoor.seedLocation("Kyl")
        read()
        offline()
        value(pantry.addEntry(InventoryEntryDraft(fridge, "mjölk")))
        tandoor.partnerDeleteLocation(fridge)
        online()

        drainAll()

        assertTrue(tandoor.locations().isEmpty())
        assertTrue(tandoor.requests("POST", "/api/inventory-entry/").isEmpty())
        assertEquals(0, outboxCount())
        assertTrue(graph.droppedChanges.recent.value.any { it.entity == "inventory_entry" && it.reason == DroppedChanges.NOT_FOUND })
    }

    /** Fas S review (fix-data): "-1" on two phones takes two, and a stale "-1" keeps a partner's edit. */
    @Test
    fun minusOneIsAChangeNotAStaleValue() = forEachVersion {
        val fridge = tandoor.seedLocation("Kyl")
        val cream = tandoor.seedEntry(fridge, "grädde", amount = 3.0)
        val milk = tandoor.seedEntry(fridge, "mjölk", amount = 3.0)
        read()
        offline()
        ok(pantry.decrement(cream))
        ok(pantry.decrement(milk))
        // The partner took one cream too, and set the milk to 5, before this phone sends.
        tandoor.partnerSetAmount(cream, 2.0)
        tandoor.partnerSetAmount(milk, 5.0)
        online()

        drainAll()

        assertEquals(1.0, tandoor.entries().single { it.id == cream }.amount, 0.0)
        assertEquals(4.0, tandoor.entries().single { it.id == milk }.amount, 0.0)
        assertTrue(tandoor.requests("PATCH", "/api/inventory-entry/").none { (_, body) -> "tandem_" in body })

        // "Slut" and Undo stay values.
        ok(pantry.markEmpty(milk))
        ok(pantry.updateEntry(milk, InventoryEntryUpdate(amount = 4.0)))
        ok(pantry.decrement(milk))
        tandoor.partnerSetAmount(milk, 9.0)
        drainAll()
        assertEquals(3.0, tandoor.entries().single { it.id == milk }.amount, 0.0)
    }

    @Test
    fun aGuestWritesNothingAndReadsNothing() = forEachVersion {
        graph.permissions.setGuest(true)
        assertEquals(Outcome.Failure(DataProblem.READ_ONLY.toError()), pantry.createLocation("Kyl", false))
        assertEquals(Outcome.Failure(DataProblem.READ_ONLY.toError()), pantry.addEntry(InventoryEntryDraft(1, "grädde")))
        assertEquals(0, outboxCount())

        // Tandoor answers 403 on the lists for a guest: the reader does not ask.
        PantrySyncEnv.mirror(graph, groupLevel = "guest")
        read()
        assertTrue(tandoor.calls.isEmpty())
    }

    @Test
    fun accountsAndDemoModeNeverSeeEachOthersPantry() = forEachVersion {
        val fridge = tandoor.seedLocation("Kyl")
        tandoor.seedEntry(fridge, "kikärtor")
        read()
        assertEquals(1, pantry.observePantry().first().size)

        val other = graph.account.copy(id = "account-b")
        graph.sources.source.value = ActiveSource.Remote(other, graph.api)
        assertTrue(pantry.observePantry().first().isEmpty())

        graph.sources.source.value = ActiveSource.Demo
        ok(graph.demo.ensureSeeded())
        val before = graph.server.requests.size
        val demo = pantry.observePantry().first()
        assertEquals(listOf("Kyl", "Frys", "Skafferi"), demo.map { it.location.name })
        assertTrue(demo.flatMap { it.entries }.any { it.expiresSoon(graph.clock.today()) })
        ok(pantry.addEntry(InventoryEntryDraft(demo[0].location.id, "grädde")))
        assertEquals("demo mode sends nothing", before, graph.server.requests.size)

        graph.sources.source.value = graph.remote
        assertEquals(listOf("kikärtor"), pantry.observePantry().first().flatMap { group -> group.entries.map { it.food.name } })
    }
}
