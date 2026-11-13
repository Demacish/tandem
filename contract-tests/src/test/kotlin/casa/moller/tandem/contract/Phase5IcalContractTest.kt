package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Fas 5, lane kalender (0.8.0, plan §3.1, §7 #36 "iCal-länk"): what "Matsedeln i kalendern" builds
 * on, on a real server.
 *
 * - **The feed:** `GET /api/meal-plan/ical/` answers `text/calendar` with one VEVENT per meal
 *   (UID, SUMMARY "<meal type>: <title>", DESCRIPTION = the note, DTSTART/DTEND as date-times),
 *   filtered by `from_date`/`to_date` like the list.
 * - **The token:** only the scope `mealplan` opens the feed (a `read write` token gets 403), and a
 *   `mealplan` token opens nothing else. A calendar subscription can only carry the token in the
 *   URL, so `?access_token=` (OAuth 2 bearer in the query, RFC 6750 §2.3) is checked next to the
 *   `Authorization` header, and other parameter names are refused.
 * - **Creating and revoking:** the app's own `read write` token creates a `mealplan` token with an
 *   expiry (201, plaintext), the list keeps showing it in plaintext after 15 s (Tandoor's rule for
 *   `mealplan`), `DELETE` revokes it (204) and the feed then answers 403. A guest is recorded too.
 *
 * Runs after Phase1ContractTest (the server's zone is Europe/Stockholm, contract.yml). It creates
 * and deletes its own meal types, meals and tokens. Recordings: `build/contract-recordings/<tag>/
 * fas5/kalender/`, committed to `api-schema/fixtures/<version>/fas5/kalender/`; the token in a path
 * is replaced by the sanitizer's fake one and the feed's dates are shifted like every other date.
 * Soft checks as in the other phases: every fact is recorded, the last step compares [EXPECTED].
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase5IcalContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas5/kalender")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val mealTypes = mutableListOf<Long>()
    private val meals = mutableListOf<Long>()
    private val tokenIds = mutableListOf<Long>()
    private var timedMeal: Long? = null
    private var allDayMeal: Long? = null
    private var twoDayMeal: Long? = null
    private var mealplanToken: String? = null
    private var mealplanTokenId: Long? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        if (env.guestToken.isNotBlank()) sanitizer.fakeTokenFor(env.guestToken)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun cleanUp() {
        meals.forEach { api("cleanup", "DELETE", "/api/meal-plan/$it/", record = false) }
        mealTypes.forEach { api("cleanup", "DELETE", "/api/meal-type/$it/", record = false) }
        tokenIds.forEach { api("cleanup", "DELETE", "/api/access-token/$it/", record = false) }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    @Test
    @Order(10)
    fun seedMeals() {
        val dinner = api(
            "meal-type-med-tid",
            "POST",
            "/api/meal-type/",
            obj("name" to "$PREFIX middag", "order" to MEAL_TYPE_ORDER, "time" to "18:00:00"),
        )
        val untimed = api("meal-type-utan-tid", "POST", "/api/meal-type/", obj("name" to "$PREFIX matlåda", "order" to MEAL_TYPE_ORDER + 1))
        observe("seed.mealType.timed", dinner.status)
        observe("seed.mealType.untimed", untimed.status)
        observe("seed.mealType.untimed.time", (untimed.json as? JsonObject)?.get("time"))
        val dinnerRef = (dinner.json as? JsonObject)?.let(::ref)?.also { r -> r.longAt("id")?.let(mealTypes::add) } ?: return
        val untimedRef = (untimed.json as? JsonObject)?.let(::ref)?.also { r -> r.longAt("id")?.let(mealTypes::add) } ?: return

        // A date-only from_date: the meal type's time (18:00) for "middag", Tandoor's 12:00 without one.
        timedMeal = meal("maltid-med-tid", "Kalenderns middag", "${today.plusDays(1)}", dinnerRef, note = "Dubbel sats.")
        allDayMeal = meal("maltid-utan-tid", "Kalenderns matlåda", "${today.plusDays(2)}", untimedRef)
        twoDayMeal = meal(
            "maltid-tva-dagar",
            "Kalenderns gryta",
            "${today.plusDays(3)}T00:00:00",
            dinnerRef,
            toDate = "${today.plusDays(4)}T00:00:00",
        )
        val read = api("maltid-med-tid-get", "GET", "/api/meal-plan/$timedMeal/").json as? JsonObject
        observe("seed.timed.fromDate", read?.stringAt("from_date")?.let(::timeOfDay))
    }

    @Test
    @Order(20)
    fun createAMealplanToken() {
        val expires = ZonedDateTime.now(zone).plusDays(TOKEN_DAYS).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString()
        val created = api("token-skapa", "POST", "/api/access-token/", obj("scope" to "mealplan", "expires" to expires))
        observe("token.create.status", created.status)
        val body = created.json as? JsonObject
        observe("token.create.scope", body?.stringAt("scope"))
        observe("token.create.plaintext", body?.stringAt("token")?.let { Sanitizer.TOKEN.matches(it) })
        observe("token.create.fields", body?.keys?.sorted())
        mealplanToken = body?.stringAt("token")?.also { sanitizer.fakeTokenFor(it) }
        mealplanTokenId = body?.longAt("id")?.also(tokenIds::add)

        // The list after 16 s: other tokens are masked by then, a mealplan token is not (Tandoor's rule).
        Thread.sleep(MASK_WAIT_MILLIS)
        val listed = api("token-lista-efter-16s", "GET", "/api/access-token/").json.objects()
            .firstOrNull { it.longAt("id") == mealplanTokenId }
        observe("token.listAfter16s.found", listed != null)
        observe("token.listAfter16s.plaintext", listed?.stringAt("token")?.let { it == mealplanToken })
    }

    @Test
    @Order(30)
    fun theFeedWithTheHeader() {
        val token = mealplanToken ?: return
        val feed = feed("ical-header", "/api/meal-plan/ical/", authorization = "Bearer $token")
        observe("ical.header.status", feed.status)
        observe("ical.header.contentType", feed.contentType)
        observe("ical.header.contentDisposition", feed.header("Content-Disposition")?.substringBefore(';'))
        val events = events(feed.bodyText)
        observe("ical.header.hasCalendar", feed.bodyText.contains("BEGIN:VCALENDAR"))
        observe("ical.ours", events.count { it.summary.contains(PREFIX) })
        val timed = events.firstOrNull { it.uid == "mealplan-$timedMeal@tandoor.recipes" }
        observe("ical.timed.found", timed != null)
        observe("ical.timed.summary", timed?.summary)
        observe("ical.timed.description", timed?.description)
        observe("ical.timed.dtstart", timed?.dtstart?.let(::shape))
        observe("ical.timed.dtend", timed?.dtend?.let(::shape))
        observe("ical.timed.startHourLocal", timed?.dtstart?.let(::localHour))
        observe("ical.timed.durationMinutes", timed?.let(::minutes))
        val allDay = events.firstOrNull { it.uid == "mealplan-$allDayMeal@tandoor.recipes" }
        observe("ical.untimed.summary", allDay?.summary)
        observe("ical.untimed.startHourLocal", allDay?.dtstart?.let(::localHour))
        observe("ical.untimed.description", allDay?.description)
        val twoDays = events.firstOrNull { it.uid == "mealplan-$twoDayMeal@tandoor.recipes" }
        val twoDaysStart = twoDays?.dtstart?.let(::localDate)
        val twoDaysEnd = twoDays?.dtend?.let(::localDate)
        val twoDaysLength = if (twoDaysStart != null && twoDaysEnd != null) ChronoUnit.DAYS.between(twoDaysStart, twoDaysEnd) else null
        observe("ical.twoDays.days", twoDaysLength)
    }

    @Test
    @Order(40)
    fun theFeedWithTheTokenInTheUrl() {
        val token = mealplanToken ?: return
        val query = feed("ical-access-token", "/api/meal-plan/ical/?access_token=$token")
        observe("ical.queryAccessToken.status", query.status)
        observe("ical.queryAccessToken.ours", events(query.bodyText).count { it.summary.contains(PREFIX) })
        observe("ical.queryToken.status", feed("ical-token", "/api/meal-plan/ical/?token=$token").status)
        observe("ical.queryAuth.status", feed("ical-auth", "/api/meal-plan/ical/?auth=$token").status)
        val day = today.plusDays(1)
        val ranged = feed("ical-intervall", "/api/meal-plan/ical/?from_date=$day&to_date=$day&access_token=$token")
        observe("ical.range.status", ranged.status)
        observe("ical.range.ours", events(ranged.bodyText).count { it.summary.contains(PREFIX) })
    }

    @Test
    @Order(50)
    fun whoMayReadTheFeed() {
        val token = mealplanToken ?: return
        observe("ical.noAuth.status", feed("ical-utan-token", "/api/meal-plan/ical/").status)
        observe("ical.readWrite.status", feed("ical-read-write", "/api/meal-plan/ical/", authorization = "Bearer ${env.fas1Token}").status)
        observe("ical.readWriteInUrl.status", feed("ical-read-write-i-url", "/api/meal-plan/ical/?access_token=${env.fas1Token}").status)
        val list = http.call("mealplan-token-lista", "GET", "/api/meal-plan/", bearer = token)
        observe("mealplanToken.mealPlanList.status", list.status)
        val recipes = http.call("mealplan-token-recept", "GET", "/api/recipe/?page_size=1", bearer = token)
        observe("mealplanToken.recipeList.status", recipes.status)
        observe("mealplanToken.tokenList.status", http.call("mealplan-token-tokens", "GET", "/api/access-token/", bearer = token).status)
        if (env.guestToken.isNotBlank()) {
            val expires = ZonedDateTime.now(zone).plusDays(1).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString()
            val guest = http.call(
                "gast-token-skapa",
                "POST",
                "/api/access-token/",
                bearer = env.guestToken,
                body = obj("scope" to "mealplan", "expires" to expires).toString().toRequestBody(JSON),
                recordBody = obj("scope" to "mealplan", "expires" to expires),
            )
            observe("guest.create.status", guest.status)
            (guest.json as? JsonObject)?.longAt("id")?.let { id ->
                http.call("cleanup", "DELETE", "/api/access-token/$id/", bearer = env.guestToken, record = false)
            }
        }
    }

    @Test
    @Order(60)
    fun revokeAndReplace() {
        val oldToken = mealplanToken ?: return
        val oldId = mealplanTokenId ?: return
        // "Byt länk": a new token first, then the old one is deleted.
        val expires = ZonedDateTime.now(zone).plusDays(TOKEN_DAYS).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString()
        val replacement = api("token-ny", "POST", "/api/access-token/", obj("scope" to "mealplan", "expires" to expires))
        val newToken = (replacement.json as? JsonObject)?.stringAt("token")?.also { sanitizer.fakeTokenFor(it) }
        (replacement.json as? JsonObject)?.longAt("id")?.let(tokenIds::add)
        val deleted = api("token-radera", "DELETE", "/api/access-token/$oldId/")
        observe("token.delete.status", deleted.status)
        if (deleted.status == NO_CONTENT) tokenIds.remove(oldId)
        observe("ical.revoked.status", feed("ical-aterkallad", "/api/meal-plan/ical/?access_token=$oldToken").status)
        observe("ical.replacement.status", newToken?.let { feed("ical-ny-token", "/api/meal-plan/ical/?access_token=$it").status })
        val again = api("token-radera-igen", "DELETE", "/api/access-token/$oldId/")
        observe("token.deleteAgain.status", again.status)

        // An expired mealplan token: created (201) and then refused like any other.
        val past = ZonedDateTime.now(zone).minusDays(1).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime().toString()
        val expired = api("token-utgangen", "POST", "/api/access-token/", obj("scope" to "mealplan", "expires" to past))
        val expiredToken = (expired.json as? JsonObject)?.stringAt("token")?.also { sanitizer.fakeTokenFor(it) }
        (expired.json as? JsonObject)?.longAt("id")?.let(tokenIds::add)
        observe("ical.expired.status", expiredToken?.let { feed("ical-utgangen", "/api/meal-plan/ical/?access_token=$it").status })
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the iCal baseline:\n" + mismatches.joinToString("\n"))
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** One VEVENT, with folded lines unfolded (RFC 5545 §3.1). */
    private data class Event(val uid: String?, val summary: String, val description: String?, val dtstart: String?, val dtend: String?)

    private fun events(ics: String): List<Event> {
        val lines = ics.replace("\r\n ", "").replace("\n ", "").lines().map { it.trimEnd('\r') }
        val out = mutableListOf<Event>()
        var current: MutableMap<String, String>? = null
        for (line in lines) {
            val open = current
            if (line == "BEGIN:VEVENT") {
                current = mutableMapOf()
            } else if (line == "END:VEVENT" && open != null) {
                val summary = unescape(open["SUMMARY"].orEmpty())
                out += Event(open["UID"], summary, open["DESCRIPTION"]?.let(::unescape), open["DTSTART"], open["DTEND"])
                current = null
            } else if (open != null && ':' in line) {
                val name = line.substringBefore(':').substringBefore(';')
                // Keep the parameters of the dates (TZID, VALUE=DATE): "DTSTART;TZID=…:2026…" -> ";TZID=…:2026…".
                open[name] = if (name.startsWith("DT")) line.substringAfter(name) else line.substringAfter(':')
            }
        }
        return out
    }

    private fun unescape(text: String): String = text.replace("\\,", ",").replace("\\;", ";").replace("\\n", "\n").replace("\\\\", "\\")

    /** ";TZID=Europe/Stockholm:20260101T180000" -> ";TZID=Europe/Stockholm:yyyyMMddTHHmmss". */
    private fun shape(value: String): String = value.replace(Regex("\\d{8}"), "yyyyMMdd").replace(Regex("T\\d{6}"), "THHmmss")

    private fun parse(value: String): ZonedDateTime? {
        val params = value.substringBefore(':')
        val text = value.substringAfter(':')
        val tz = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
        return runCatching {
            when {
                text.length == DATE_ONLY -> LocalDate.parse(text, BASIC_DATE).atStartOfDay(zone)
                text.endsWith("Z") -> java.time.LocalDateTime.parse(text.dropLast(1), BASIC).atZone(ZoneId.of("UTC"))
                else -> java.time.LocalDateTime.parse(text, BASIC).atZone(tz?.let(ZoneId::of) ?: zone)
            }
        }.getOrNull()
    }

    private fun localHour(value: String): Int? = parse(value)?.withZoneSameInstant(zone)?.hour

    private fun localDate(value: String): LocalDate? = parse(value)?.withZoneSameInstant(zone)?.toLocalDate()

    private fun minutes(event: Event): Long? {
        val start = event.dtstart?.let(::parse) ?: return null
        val end = event.dtend?.let(::parse) ?: return null
        return ChronoUnit.MINUTES.between(start, end)
    }

    /** "2000-01-16T18:00:00+01:00" -> "18:00:00+zone". */
    private fun timeOfDay(value: String): String = value.substringAfter('T').replace(Regex("[+-]\\d{2}:\\d{2}$"), "+offset")

    private fun meal(
        name: String,
        title: String,
        fromDate: String,
        mealType: JsonObject,
        note: String = "",
        toDate: String? = null,
    ): Long? {
        val pairs = mutableListOf<Pair<String, Any?>>(
            "title" to "$PREFIX $title",
            "recipe" to null,
            "servings" to 2,
            "note" to note,
            "from_date" to fromDate,
            "meal_type" to mealType,
        )
        if (toDate != null) pairs += "to_date" to toDate
        val created = api(name, "POST", "/api/meal-plan/", obj(*pairs.toTypedArray()))
        return (created.json as? JsonObject)?.longAt("id")?.also(meals::add)
    }

    /** GETs the feed and records it with the token in the path replaced and the dates shifted. */
    private fun feed(name: String, path: String, authorization: String? = null): Exchange {
        val raw = http.call(
            name,
            "GET",
            path,
            authorization = authorization,
            extraHeaders = mapOf("Accept" to "text/calendar"),
            record = false,
        )
        http.record(
            Exchange(
                name = raw.name,
                method = raw.method,
                path = sanitizer.sanitize(raw.path),
                requestHeaders = raw.requestHeaders,
                requestBody = null,
                status = raw.status,
                responseHeaders = raw.responseHeaders,
                bodyText = shiftIcsDates(raw.bodyText),
            ),
        )
        return raw
    }

    /** Moves every `yyyyMMdd` of the feed like the sanitizer moves ISO dates (run day -> 2000-01-15). */
    private fun shiftIcsDates(text: String): String = ICS_DATE.replace(text) { match ->
        val date = runCatching { LocalDate.parse(match.value, BASIC_DATE) }.getOrNull() ?: return@replace match.value
        Sanitizer.SHIFTED_TODAY.plusDays(ChronoUnit.DAYS.between(today, date)).format(BASIC_DATE)
    }

    private fun api(name: String, method: String, path: String, body: JsonElement? = null, record: Boolean = true): Exchange = http.call(
        name,
        method,
        path,
        bearer = env.fas1Token,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in BODY_METHODS })?.toString()?.toRequestBody(JSON),
        recordBody = body,
        record = record,
    )

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun ref(source: JsonObject): JsonObject = obj("id" to source.longAt("id"), "name" to source.stringAt("name"))

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    private companion object {
        val JSON = "application/json".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val PREFIX = "Tandem kalender"
        const val MEAL_TYPE_ORDER = 70
        const val TOKEN_DAYS = 365L
        const val MASK_WAIT_MILLIS = 16_000L
        const val NO_CONTENT = 204
        const val DATE_ONLY = 8
        val BASIC: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        val BASIC_DATE: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        val ICS_DATE = Regex("(?<![0-9])20\\d{6}(?![0-9])")

        /** What the app rests on. */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "token.create.status" to 201,
            "token.create.scope" to "mealplan",
            "token.create.plaintext" to true,
            "ical.header.status" to 200,
            "ical.header.contentType" to "text/calendar",
            "ical.ours" to 3,
            "ical.timed.found" to true,
            "ical.timed.summary" to "Tandem kalender middag: Tandem kalender Kalenderns middag",
            "ical.timed.description" to "Dubbel sats.",
            "ical.timed.startHourLocal" to 18,
            "ical.timed.durationMinutes" to 60,
            "ical.untimed.startHourLocal" to 12,
            "ical.queryAccessToken.status" to 200,
            "ical.queryAccessToken.ours" to 3,
            "ical.range.status" to 200,
            "ical.range.ours" to 1,
            "ical.noAuth.status" to 403,
            "ical.readWrite.status" to 403,
            "ical.readWriteInUrl.status" to 403,
            "mealplanToken.mealPlanList.status" to 403,
            "token.delete.status" to 204,
            "ical.revoked.status" to 403,
            "ical.replacement.status" to 200,
            "ical.expired.status" to 403,
        )
    }
}
