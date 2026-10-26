package casa.moller.tandem.core.sync.mealplan

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealTypeDraft
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * The meal plan's data and sync (feature #15, plan 3.5, 5.7; Phase 2 "Klart när": meals created on
 * the web land on the right day in the app and the other way round) against a stateful Tandoor in
 * Europe/Stockholm answering in the recorded shapes of BOTH 2.6.13 and 2.7.1 ([FakeMealPlanTandoor]):
 * days across daylight saving time, the `to_date` filter, offline create + move + delete with
 * temporary ids (also of a new meal type), the field comparison before a PATCH and its conflicts,
 * meals deleted elsewhere without a 404, guests and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MealPlanSyncTest {
    private class Env(val graph: SyncTestGraph, val tandoor: FakeMealPlanTandoor, val version: String) {
        val repo = graph.mealPlans

        suspend fun refresh() = assertOk(repo.refresh())

        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                val left = graph.real.outboxDao().observeAll(ACCOUNT_ID).first().filter { it.state != OutboxState.FAILED }
                if (result == DrainResult.DONE && left.isEmpty()) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${graph.real.outboxDao().observeAll(ACCOUNT_ID).first()}")
        }

        suspend fun week(day: LocalDate): MealPlanWeek = repo.observeWeek(MealPlanWeek.startOf(day)).first()

        suspend fun mealsOn(day: LocalDate): List<MealPlanEntry> = week(day).days.first { it.date == day }.meals

        fun offline() {
            graph.network.online.value = false
        }

        fun online() {
            graph.network.online.value = true
        }

        fun body(call: Pair<String, String>): JsonObject = Json.parseToJsonElement(call.second).jsonObject
    }

    private fun forEachVersion(block: suspend Env.() -> Unit) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version)
            val tandoor = FakeMealPlanTandoor(version).also { it.install(graph.server) }
            try {
                runBlocking {
                    graph.real.referenceDao().replaceMealTypes(
                        ACCOUNT_ID,
                        listOf(
                            MealTypeEntity(ACCOUNT_ID, FakeMealPlanTandoor.DINNER, "Middag", 0, "18:00:00"),
                            MealTypeEntity(ACCOUNT_ID, FakeMealPlanTandoor.BREAKFAST, "Frukost", 2, "07:30:00"),
                        ),
                    )
                    Env(graph, tandoor, version).block()
                }
            } finally {
                graph.close()
            }
        }
    }

    // ------------------------------------------------------------------ days and the window

    @Test
    fun mealsFromTheWebLandOnTheirServerDay() = forEachVersion {
        val lateUtc = tandoor.web("2026-10-18T23:30:00Z", title = "Sent UTC")
        val summer = tandoor.web("2026-10-24T22:30:00.000Z", title = "Natten till söndag")
        val winter = tandoor.web("2026-10-26T00:00:00", title = "Måndag")
        val twoDays = tandoor.web("2026-10-20T00:00:00", "2026-10-21T00:00:00", title = "Två dagar")
        refresh()
        assertEquals(listOf("Sent UTC"), mealsOn(LocalDate(2026, 10, 19)).map { it.title })
        assertEquals(listOf("Natten till söndag"), mealsOn(LocalDate(2026, 10, 25)).map { it.title })
        assertTrue(mealsOn(LocalDate(2026, 10, 24)).isEmpty())
        assertEquals(listOf("Måndag"), mealsOn(LocalDate(2026, 10, 26)).map { it.title })
        assertEquals(listOf("Två dagar"), mealsOn(LocalDate(2026, 10, 20)).map { it.title })
        assertEquals(listOf("Två dagar"), mealsOn(LocalDate(2026, 10, 21)).map { it.title })
        val stored = listOf(lateUtc, summer, winter, twoDays).map { graph.real.mealPlanDao().get(ACCOUNT_ID, it) }
        assertTrue("[$version] every meal is stored with its server id", stored.all { it != null && it.serverId == it.localId })
        // The window is asked for with plain dates (the server filters on to_date's server-local day).
        val asked = tandoor.requests("GET", "/api/meal-plan/?").first().first
        assertTrue(asked, "from_date=2026-09-22" in asked && "to_date=2026-11-17" in asked)
    }

    @Test
    fun aMealIsReplacedOnlyWhenItsToDateIsInTheWindow() = forEachVersion {
        val inside = tandoor.web("2026-10-10T18:00:00+02:00", title = "Inne")
        refresh()
        // A meal from long ago stays stored: the window read cannot say anything about it.
        val old = graph.real.mealPlanDao().get(ACCOUNT_ID, inside)!!
            .copy(localId = 7, serverId = 7, fromDate = "2026-08-01", toDate = "2026-08-01")
        graph.real.mealPlanDao().upsert(listOf(old))
        tandoor.partnerDelete(inside)
        refresh()
        assertNull("[$version] deleted inside the window", graph.real.mealPlanDao().get(ACCOUNT_ID, inside))
        assertNotNull("[$version] outside the window", graph.real.mealPlanDao().get(ACCOUNT_ID, 7))
    }

    @Test
    fun aMealPlannedInTheAppLandsOnItsDayOnTheServer() = forEachVersion {
        val day = LocalDate(2026, 10, 25)
        val id = assertOk(repo.create(MealPlanDraft(date = day, mealTypeId = FakeMealPlanTandoor.DINNER, title = "Rester", servings = 2.0)))
        assertTrue("[$version] a temporary id until sent", id < 0)
        assertEquals(listOf("Rester"), mealsOn(day).map { it.title })
        assertTrue(mealsOn(day).single().pending)
        drainAll()
        val created = tandoor.all().single()
        assertEquals(day.toString(), created.fromDay.toString())
        assertEquals(LocalTime.of(18, 0), created.from.toLocalTime())
        val body = body(tandoor.requests("POST", "/api/meal-plan/").single())
        assertEquals("2026-10-25T00:00:00", body.getValue("from_date").jsonPrimitive.content)
        assertFalse("[$version] one day: no to_date", body.containsKey("to_date"))
        assertFalse("[$version] addshopping only when ticked", body.containsKey("addshopping"))
        // The meal is now the server's (id rewritten), followed from its old temporary id too.
        val meal = repo.observeMeal(id).first()
        assertEquals(created.id, meal?.id)
        assertFalse(meal!!.pending)
        refresh()
        assertEquals(1, mealsOn(day).size)
    }

    @Test
    fun aMoveAcrossTheEndOfSummerTimeKeepsTheDayAndTheTime() = forEachVersion {
        val id = tandoor.web("2026-10-24T00:30:00+02:00", title = "Midnattsmacka")
        refresh()
        assertEquals(1, mealsOn(LocalDate(2026, 10, 24)).size)
        assertOk(repo.update(id, MealPlanUpdate.move(LocalDate(2026, 10, 26))))
        assertEquals(listOf("Midnattsmacka"), mealsOn(LocalDate(2026, 10, 26)).map { it.title })
        drainAll()
        val patch = body(tandoor.requests("PATCH", "/api/meal-plan/$id/").single())
        assertEquals(JsonObject(patch).keys, setOf("from_date", "to_date"))
        assertEquals("2026-10-26T00:30:00", patch.getValue("from_date").jsonPrimitive.content)
        assertEquals("2026-10-26T00:30:00", patch.getValue("to_date").jsonPrimitive.content)
        val moved = tandoor.meal(id)!!
        assertEquals(LocalDate(2026, 10, 26).toString(), moved.fromDay.toString())
        assertEquals("+01:00", moved.from.offset.toString())
        refresh()
        assertEquals(listOf("Midnattsmacka"), mealsOn(LocalDate(2026, 10, 26)).map { it.title })
    }

    // ------------------------------------------------------------------ offline: create, move, delete

    @Test
    fun offlineCreateMoveAndChangeAreOnePostWithTheFinalValues() = forEachVersion {
        offline()
        val typeId = assertOk(repo.createMealType(MealTypeDraft("Fika", time = kotlinx.datetime.LocalTime(15, 0))))
        assertTrue(typeId < 0)
        val id = assertOk(
            repo.create(
                MealPlanDraft(LocalDate(2026, 10, 20), mealTypeId = FakeMealPlanTandoor.DINNER, recipeId = FakeMealPlanTandoor.RECIPE),
            ),
        )
        assertOk(repo.update(id, MealPlanUpdate.move(LocalDate(2026, 10, 22))))
        assertOk(repo.update(id, MealPlanUpdate(mealTypeId = typeId, servings = 6.0, note = "Dubbel sats")))
        assertEquals(1, mealsOn(LocalDate(2026, 10, 22)).size)
        assertEquals("Fika", mealsOn(LocalDate(2026, 10, 22)).single().mealType.name)
        assertEquals(2, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        online()
        drainAll()
        assertEquals(listOf("POST /api/meal-type/", "POST /api/meal-plan/"), tandoor.writes().map { it.first })
        val fika = tandoor.mealType("Fika")!!
        val body = body(tandoor.requests("POST", "/api/meal-plan/").single())
        assertEquals("2026-10-22T00:00:00", body.getValue("from_date").jsonPrimitive.content)
        assertEquals(fika.id.toString(), body.getValue("meal_type").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("6.0", body.getValue("servings").jsonPrimitive.content)
        assertEquals("Dubbel sats", body.getValue("note").jsonPrimitive.content)
        // Both ids rewritten: the meal and its meal type are the server's.
        val meal = mealsOn(LocalDate(2026, 10, 22)).single()
        assertEquals(tandoor.all().single().id, meal.id)
        assertEquals(fika.id, meal.mealType.id)
        assertEquals(LocalTime.of(15, 0), tandoor.all().single().from.toLocalTime())
    }

    @Test
    fun offlineCreateMoveAndDeleteSendNothing() = forEachVersion {
        offline()
        val id = assertOk(repo.create(MealPlanDraft(LocalDate(2026, 10, 20), mealTypeId = FakeMealPlanTandoor.DINNER, title = "Tacos")))
        assertOk(repo.update(id, MealPlanUpdate.move(LocalDate(2026, 10, 21))))
        assertOk(repo.delete(id))
        assertTrue(mealsOn(LocalDate(2026, 10, 21)).isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        online()
        drainAll()
        assertTrue("[$version] nothing to send: ${tandoor.writes()}", tandoor.writes().isEmpty())
    }

    @Test
    fun deletingAServerMealIsOneDelete() = forEachVersion {
        val id = tandoor.web("2026-10-12T18:00:00+02:00", title = "Fisk")
        refresh()
        offline()
        assertOk(repo.update(id, MealPlanUpdate(servings = 2.0)))
        assertOk(repo.delete(id))
        assertTrue(mealsOn(LocalDate(2026, 10, 12)).isEmpty())
        online()
        drainAll()
        assertEquals(listOf("DELETE /api/meal-plan/$id/"), tandoor.writes().map { it.first })
        assertNull(tandoor.meal(id))
    }

    // ------------------------------------------------------------------ field comparison and conflicts

    @Test
    fun onlyTheChangedFieldsAreSentAndTheWebsChangeStays() = forEachVersion {
        val id = tandoor.web("2026-10-14T18:00:00+02:00", title = "Lax")
        refresh()
        offline()
        assertOk(repo.update(id, MealPlanUpdate(servings = 6.0)))
        // Meanwhile on the web: a note and the title.
        tandoor.partnerEdit(id) {
            note = "Köp dill"
            title = "Ugnsbakad lax"
        }
        online()
        drainAll()
        val patch = body(tandoor.requests("PATCH", "/api/meal-plan/$id/").single())
        assertEquals(setOf("servings"), patch.keys)
        val server = tandoor.meal(id)!!
        assertEquals(6.0, server.servings, 0.0)
        assertEquals("Köp dill", server.note)
        assertEquals("Ugnsbakad lax", server.title)
        refresh()
        assertEquals("Ugnsbakad lax", repo.observeMeal(id).first()?.title)
    }

    @Test
    fun aChangeTheServerAlreadyHasIsNotSent() = forEachVersion {
        val id = tandoor.web("2026-10-14T18:00:00+02:00", title = "Lax")
        refresh()
        offline()
        assertOk(repo.update(id, MealPlanUpdate(title = "Lax med dill")))
        tandoor.partnerEdit(id) { title = "Lax med dill" }
        online()
        drainAll()
        assertTrue(tandoor.requests("PATCH", "/api/meal-plan/").isEmpty())
        assertTrue(repo.observeConflicts().first().isEmpty())
    }

    @Test
    fun aMoveThatCollidesWithAnotherMoveWaitsForTheUser() = forEachVersion {
        val id = tandoor.web("2026-10-14T18:00:00+02:00", title = "Lax")
        refresh()
        offline()
        assertOk(repo.update(id, MealPlanUpdate.move(LocalDate(2026, 10, 16))))
        assertOk(repo.update(id, MealPlanUpdate(servings = 3.0)))
        tandoor.partnerEdit(id) {
            from = from.plusDays(1)
            to = to.plusDays(1)
        }
        online()
        drainAll()
        // The servings went; the dates wait.
        val patch = body(tandoor.requests("PATCH", "/api/meal-plan/$id/").single())
        assertEquals(setOf("servings"), patch.keys)
        val conflict = repo.observeConflicts().first().single()
        assertEquals("from_date", conflict.field)
        assertEquals("2026-10-16", conflict.mine)
        assertEquals("2026-10-15", conflict.theirs)
        assertEquals(id, conflict.mealPlanId)
        assertTrue("[$version] still mine on the device", mealsOn(LocalDate(2026, 10, 16)).single().pending)
        // A read leaves the meal as the user left it while the conflict waits.
        refresh()
        assertEquals(1, mealsOn(LocalDate(2026, 10, 16)).size)
        assertOk(repo.resolveConflict(conflict, keepMine = true))
        drainAll()
        assertEquals("2026-10-16", tandoor.meal(id)!!.fromDay.toString())
        assertTrue(repo.observeConflicts().first().isEmpty())
        refresh()
        assertFalse(mealsOn(LocalDate(2026, 10, 16)).single().pending)
    }

    @Test
    fun takingTheirsSendsNothingAndShowsTheirDay() = forEachVersion {
        val id = tandoor.web("2026-10-14T18:00:00+02:00", title = "Lax")
        refresh()
        offline()
        assertOk(repo.update(id, MealPlanUpdate(title = "Min titel")))
        tandoor.partnerEdit(id) { title = "Deras titel" }
        online()
        drainAll()
        val conflict = repo.observeConflicts().first().single()
        assertEquals("title" to "Min titel", conflict.field to conflict.mine)
        assertEquals("Deras titel", conflict.theirs)
        assertOk(repo.resolveConflict(conflict, keepMine = false))
        assertEquals("Deras titel", repo.observeMeal(id).first()?.title)
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        drainAll()
        assertTrue(tandoor.requests("PATCH", "/api/meal-plan/").isEmpty())
        assertEquals("Deras titel", tandoor.meal(id)!!.title)
    }

    @Test
    fun mealsDeletedElsewhereDrainWithoutA404() = forEachVersion {
        val ids = (10..14).map { day -> tandoor.web("2026-10-${day}T18:00:00+02:00", title = "Dag $day") }
        refresh()
        offline()
        assertOk(repo.update(ids[0], MealPlanUpdate(servings = 2.0)))
        assertOk(repo.update(ids[1], MealPlanUpdate.move(LocalDate(2026, 10, 20))))
        assertOk(repo.delete(ids[2]))
        ids.take(3).forEach { tandoor.partnerDelete(it) }
        online()
        refresh()
        drainAll()
        assertTrue("[$version] no 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
        assertTrue(tandoor.writes().isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        assertTrue(mealsOn(LocalDate(2026, 10, 20)).isEmpty())
        assertEquals(listOf("Dag 13", "Dag 14"), week(LocalDate(2026, 10, 13)).days.flatMap { day -> day.meals.map { it.title } })
    }

    @Test
    fun aMealDeletedJustBeforeAPatchIsDroppedQuietly() = forEachVersion {
        val id = tandoor.web("2026-10-14T18:00:00+02:00", title = "Lax")
        refresh()
        assertOk(repo.update(id, MealPlanUpdate(servings = 2.0)))
        tandoor.partnerDelete(id)
        drainAll()
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
        val stopped = graph.guard.check(ACCOUNT_ID, background = true)
        assertNull("[$version] one 404 alone does not trip the circuit breaker (five within 100 s do)", stopped)
    }

    @Test
    fun theSyncRunReadsTheWindowAndSendsTheOutbox() = forEachVersion {
        tandoor.web("2026-10-06T18:00:00+02:00", title = "Idag")
        offline()
        assertOk(repo.create(MealPlanDraft(LocalDate(2026, 10, 7), mealTypeId = FakeMealPlanTandoor.DINNER, title = "Imorgon")))
        online()
        graph.engine.run(SyncReason.USER_REFRESH)
        assertEquals(listOf("Idag"), mealsOn(LocalDate(2026, 10, 6)).map { it.title })
        assertEquals(listOf("Imorgon"), mealsOn(LocalDate(2026, 10, 7)).map { it.title })
        assertEquals(2, tandoor.all().size)
        // Today's "Ikväll" reads the same table.
        assertEquals(listOf("Idag"), graph.home.observeUpcomingMeals().first().todayMeals.map { it.title })
    }

    // ------------------------------------------------------------------ guests and demo

    @Test
    fun aGuestCannotPlanAndNothingIsStored() = forEachVersion {
        graph.permissions.setGuest(true)
        val created = repo.create(MealPlanDraft(LocalDate(2026, 10, 20), mealTypeId = FakeMealPlanTandoor.DINNER, title = "Gäst"))
        assertEquals(DataProblem.READ_ONLY.toError(), (created as Outcome.Failure).error)
        assertTrue(repo.createMealType(MealTypeDraft("Fika")) is Outcome.Failure)
        assertTrue(mealsOn(LocalDate(2026, 10, 20)).isEmpty())
        assertEquals(0, graph.real.outboxDao().observeCount(ACCOUNT_ID).first())
    }

    @Test
    fun demoModeHasASampleWeekAndNeverTouchesTheNetwork() = forEachVersion {
        graph.sources.source.value = ActiveSource.Demo
        assertOk(graph.demo.ensureSeeded())
        val today = graph.clock.today()
        val days = week(today).days
        assertTrue("[$version] a planned week", days.count { it.meals.isNotEmpty() } >= 5)
        assertTrue(days.flatMap { it.meals }.any { it.recipe == null && it.title.isNotBlank() })
        val types = repo.observeMealTypes().first()
        val id = assertOk(repo.create(MealPlanDraft(today, mealTypeId = types.first().id, title = "Pyttipanna")))
        assertOk(repo.update(id, MealPlanUpdate.move(today)))
        assertOk(repo.refresh())
        assertTrue(mealsOn(today).any { it.title == "Pyttipanna" })
        assertEquals(0, graph.demoDatabase.outboxDao().observeCount("demo").first())
        assertTrue("demo mode sends nothing: ${graph.server.requests}", graph.server.requests.isEmpty())
    }

    private companion object {
        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 10

        fun <T> assertOk(outcome: Outcome<T>): T {
            assertTrue("$outcome", outcome is Outcome.Success)
            return (outcome as Outcome.Success).value
        }
    }
}
