package casa.moller.tandem.core.domain.mealplan

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Meals land on the same day in the app and on the web (Phase 2 "Klart när"): the days of the
 * recorded meal plans of Tandoor 2.6.13 and 2.7.1 (FACTS "Fas 2 f"), what the app sends, and
 * daylight saving time with a server in Europe/Stockholm.
 */
class MealPlanDatesTest {
    private val fixtures = File(System.getProperty("tandem.fixturesDir") ?: "../../api-schema/fixtures")
    private val json = Json { ignoreUnknownKeys = true }

    private fun recording(version: String, name: String): Pair<JsonObject, JsonObject> {
        val root = json.parseToJsonElement(File(fixtures, "$version/fas2/$name.json").readText()).jsonObject
        val request = root.getValue("request").jsonObject.getValue("body").jsonObject
        val response = root.getValue("response").jsonObject.getValue("body").jsonObject
        return request to response
    }

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    @ParameterizedTest
    @ValueSource(strings = ["2.6.13", "2.7.1"])
    fun aPlainDateStaysThatDay(version: String) {
        val (request, response) = recording(version, "meal-plan-create-date-only")
        assertEquals(LocalDate(2000, 1, 18), MealPlanDates.serverDay(request.text("from_date")))
        assertEquals(LocalDate(2000, 1, 18), MealPlanDates.serverDay(response.text("from_date")))
        assertEquals(LocalDate(2000, 1, 18), MealPlanDates.serverDay(response.text("to_date")))
        // Tandoor gives a date-only meal its meal type's time (Middag 18:00).
        assertEquals(LocalTime(18, 0), MealPlanDates.serverTime(response.text("from_date")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["2.6.13", "2.7.1"])
    fun whatTheAppSendsForANewMealIsTheVerifiedMidnightShape(version: String) {
        val (request, response) = recording(version, "meal-plan-create-two-days")
        // The app's create body is exactly the recorded naive midnight shape for both days.
        assertEquals(request.text("from_date"), MealPlanDates.toServer(LocalDate(2000, 1, 20)))
        assertEquals(request.text("to_date"), MealPlanDates.toServer(LocalDate(2000, 1, 21)))
        assertEquals(LocalDate(2000, 1, 20), MealPlanDates.serverDay(response.text("from_date")))
        assertEquals(LocalDate(2000, 1, 21), MealPlanDates.serverDay(response.text("to_date")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["2.6.13", "2.7.1"])
    fun aLateUtcTimeIsTheNextServerDay(version: String) {
        // Created elsewhere as 23:30Z: the server lists it under the next (server-local) day, and so does the app.
        val (request, response) = recording(version, "meal-plan-create-utc-late")
        assertEquals("2000-01-18T23:30:00Z", request.text("from_date"))
        assertEquals(LocalDate(2000, 1, 19), MealPlanDates.serverDay(response.text("from_date")))
        assertEquals(LocalTime(0, 30), MealPlanDates.serverTime(response.text("from_date")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["2.6.13", "2.7.1"])
    fun movingOnlyTheFirstDayLeavesTheLastDayBehind(version: String) {
        val (request, response) = recording(version, "meal-plan-patch-move")
        assertTrue(request.containsKey("from_date") && !request.containsKey("to_date"))
        val from = MealPlanDates.serverDay(response.text("from_date"))!!
        val to = MealPlanDates.serverDay(response.text("to_date"))!!
        assertTrue(to < from, "Tandoor kept the old to_date")
        // Such a meal is shown on its first day only; the app always moves both days.
        assertTrue(MealPlanDates.covers(from, to, from))
        assertFalse(MealPlanDates.covers(from, to, to))
    }

    @Test
    fun aMoveKeepsTheLength() {
        assertEquals(
            LocalDate(2026, 10, 30) to LocalDate(2026, 10, 31),
            MealPlanDates.moved(LocalDate(2026, 10, 20), LocalDate(2026, 10, 21), LocalDate(2026, 10, 30)),
        )
        assertEquals(
            LocalDate(2026, 10, 30) to LocalDate(2026, 10, 30),
            MealPlanDates.moved(LocalDate(2026, 10, 20), LocalDate(2026, 10, 19), LocalDate(2026, 10, 30)),
        )
    }

    @Test
    fun aMealMovedAcrossTheEndOfSummerTimeStaysOnItsDay() {
        // Made on the web at 00:30 the night into Sunday 25 October 2026 (still +02:00 then).
        val created = "2026-10-25T00:30:00+02:00"
        assertEquals(LocalDate(2026, 10, 25), MealPlanDates.serverDay(created))
        // Moved in the app to Monday (+01:00): the naive value is read in the server's zone.
        val sent = MealPlanDates.toServer(LocalDate(2026, 10, 26), MealPlanDates.serverTime(created))
        assertEquals("2026-10-26T00:30:00", sent)
        assertEquals(LocalDate(2026, 10, 26), MealPlanDates.serverDay(stockholmServer(sent)))
        // Sending the old offset instead would have put it on Sunday evening.
        val withOldOffset = java.time.OffsetDateTime.parse("2026-10-26T00:30:00+02:00").atZoneSameInstant(STOCKHOLM)
        assertEquals("2026-10-25", withOldOffset.toLocalDate().toString())
    }

    @Test
    fun aMealMovedIntoTheStartOfSummerTimeStaysOnItsDay() {
        // 02:30 does not exist on 29 March 2026 in Stockholm: the server moves the time, not the day.
        val sent = MealPlanDates.toServer(LocalDate(2026, 3, 29), LocalTime(2, 30))
        assertEquals(LocalDate(2026, 3, 29), MealPlanDates.serverDay(stockholmServer(sent)))
        val late = MealPlanDates.toServer(LocalDate(2026, 3, 28), LocalTime(23, 45))
        assertEquals(LocalDate(2026, 3, 28), MealPlanDates.serverDay(stockholmServer(late)))
    }

    @Test
    fun aMealCreatedOnTheWebInAnotherZoneIsOnTheServersDay() {
        // The browser sent UTC (a phone or laptop in UTC+0 planning "Sunday" at 23:30 local).
        val answer = stockholmServerFromInstant("2026-10-24T23:30:00Z")
        assertEquals("2026-10-25T01:30:00+02:00", answer)
        assertEquals(LocalDate(2026, 10, 25), MealPlanDates.serverDay(answer))
    }

    @Test
    fun unreadableValues() {
        assertNull(MealPlanDates.serverDay(null))
        assertNull(MealPlanDates.serverDay("2026-1"))
        assertNull(MealPlanDates.serverDay("not a date"))
        assertNull(MealPlanDates.serverTime("2026-10-25"))
        assertNull(MealPlanDates.serverTime("2026-10-25T25:99:00"))
        assertEquals(LocalDate(2026, 10, 25), MealPlanDates.serverDay(" 2026-10-25 "))
    }

    /** What a Tandoor in Europe/Stockholm answers for a naive value: read in its zone, sent back with the offset. */
    private fun stockholmServer(naive: String): String =
        LocalDateTime.parse(naive).atZone(STOCKHOLM).toOffsetDateTime().format(SERVER_FORMAT)

    private fun stockholmServerFromInstant(instant: String): String =
        java.time.Instant.parse(instant).atZone(STOCKHOLM).toOffsetDateTime().format(SERVER_FORMAT)

    private companion object {
        val STOCKHOLM: ZoneId = ZoneId.of("Europe/Stockholm")
        val SERVER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
