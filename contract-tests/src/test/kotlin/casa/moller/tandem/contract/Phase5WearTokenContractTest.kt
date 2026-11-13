package casa.moller.tandem.contract

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
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
 * Fas 5 (wear lane, plan §3.1, §7 #33): what the Wear OS app's own token rests on, on 2.6.13 and
 * 2.7.x. The phone creates it with its own `read write` token (`POST /api/access-token/` with
 * scope `read write` and an `expires` 90 days ahead) and reads token and id from the 201; the list
 * shows it with its scope and expiry; with it the watch reads the shopping list and the stores and
 * checks entries with `bulk`; the phone deletes it with its own token (204), after which the
 * watch's requests get Tandoor's JSON 403 ("log in again"); a token may also delete itself. For a
 * guest it records whether a token can be made and whether its `bulk` is refused (the app never
 * pairs a guest's watch: a guest may not check anything off).
 *
 * Recordings go to `build/contract-recordings/<tag>/fas5/wear/` and are committed to
 * `api-schema/fixtures/<version>/fas5/wear/`. Soft checks as in the other phases: every fact is
 * recorded and the last step compares [EXPECTED] (the guest's answers are recorded only).
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase5WearTokenContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas5/wear")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    /** 90 days ahead at noon UTC, so the server's own time zone never moves the date. */
    private val requestedExpiry: Instant = today.plusDays(WATCH_TOKEN_DAYS).atTime(12, 0).toInstant(ZoneOffset.UTC)

    private var watchToken: String? = null
    private var watchTokenId: Long? = null
    private var entryId: Long? = null
    private val tokensToDelete = mutableListOf<Long>()

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
        entryId?.let { api("cleanup", "DELETE", "/api/shopping-list-entry/$it/", env.fas1Token, record = false) }
        tokensToDelete.forEach { api("cleanup", "DELETE", "/api/access-token/$it/", env.fas1Token, record = false) }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    @Test
    @Order(10)
    fun thePhoneCreatesTheWatchsToken() {
        val created = api("watch-token-create", "POST", "/api/access-token/", env.fas1Token, createBody())
        val body = created.json as? JsonObject
        observe("create.status", created.status)
        observe("create.scope", body?.stringAt("scope"))
        val token = body?.stringAt("token").orEmpty()
        observe("create.plaintext", token.startsWith("tda_") && !token.contains('*'))
        val id = body?.longAt("id")
        observe("create.hasId", id != null)
        observe("create.expiresWithinADay", withinADay(body?.stringAt("expires")))
        if (token.isNotEmpty()) sanitizer.fakeTokenFor(token)
        watchToken = token.takeIf { it.isNotEmpty() }
        watchTokenId = id
        id?.let(tokensToDelete::add)
    }

    @Test
    @Order(20)
    fun theListShowsItWithItsScopeAndExpiry() {
        val id = watchTokenId ?: return
        val list = api("watch-token-list", "GET", "/api/access-token/", env.fas1Token).json as? JsonArray
        val entry = list.orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.longAt("id") == id }
        observe("list.found", entry != null)
        observe("list.scope", entry?.stringAt("scope"))
        observe("list.expiresWithinADay", withinADay(entry?.stringAt("expires")))
    }

    @Test
    @Order(30)
    fun theWatchReadsTheListAndChecksOff() {
        val token = watchToken ?: return
        val shopping = api("watch-shopping-list", "GET", "/api/shopping-list-entry/?page_size=5", token)
        observe("watch.read.shopping", shopping.status)
        observe("watch.read.supermarkets", api("watch-supermarkets", "GET", "/api/supermarket/?page_size=5", token).status)
        val entry = api(
            "phone-adds-entry",
            "POST",
            "/api/shopping-list-entry/",
            env.fas1Token,
            obj("food" to obj("name" to "Tandem klockmjölk"), "amount" to 1, "unit" to null),
        )
        val id = (entry.json as? JsonObject)?.longAt("id") ?: return
        entryId = id
        val check = obj("ids" to listOf(id), "checked" to true)
        val bulk = api("watch-bulk-check", "POST", "/api/shopping-list-entry/bulk/", token, check)
        observe("watch.bulk.status", bulk.status)
        val after = api("phone-sees-checked", "GET", "/api/shopping-list-entry/$id/", env.fas1Token).json as? JsonObject
        observe("watch.bulk.checkedOnServer", after?.boolAt("checked"))
        // A read+write token may create tokens (FACTS c); the watch never does, but it could.
        val extra = api("watch-creates-token", "POST", "/api/access-token/", token, createBody(), record = false)
        observe("watch.createsToken.status", extra.status)
        (extra.json as? JsonObject)?.longAt("id")?.let(tokensToDelete::add)
    }

    @Test
    @Order(40)
    fun thePhoneDeletesItAndTheWatchIsRefused() {
        val token = watchToken ?: return
        val id = watchTokenId ?: return
        val deleted = api("phone-deletes-watch-token", "DELETE", "/api/access-token/$id/", env.fas1Token)
        observe("phoneDeletes.status", deleted.status)
        tokensToDelete.remove(id)
        val refused = api("watch-after-delete", "GET", "/api/shopping-list-entry/?page_size=1", token)
        observe("afterDelete.read.status", refused.status)
        observe("afterDelete.read.hasDetail", (refused.json as? JsonObject)?.containsKey("detail"))
        val again = api("phone-deletes-again", "DELETE", "/api/access-token/$id/", env.fas1Token)
        observe("phoneDeletesAgain.status", again.status)
    }

    @Test
    @Order(50)
    fun aWatchTokenMayDeleteItself() {
        val created = api("watch-token-create-2", "POST", "/api/access-token/", env.fas1Token, createBody(), record = false)
        val body = created.json as? JsonObject ?: return
        val token = body.stringAt("token") ?: return
        val id = body.longAt("id") ?: return
        sanitizer.fakeTokenFor(token)
        tokensToDelete += id
        observe("selfDelete.status", api("watch-deletes-itself", "DELETE", "/api/access-token/$id/", token).status)
        tokensToDelete.remove(id)
        val refused = api("watch-after-self-delete", "GET", "/api/supermarket/?page_size=1", token)
        observe("afterSelfDelete.read.status", refused.status)
    }

    @Test
    @Order(60)
    fun aGuest() {
        if (env.guestToken.isBlank()) return
        val created = api("guest-token-create", "POST", "/api/access-token/", env.guestToken, createBody())
        guest("guest.create.status", created.status)
        val body = created.json as? JsonObject ?: return
        val token = body.stringAt("token") ?: return
        val id = body.longAt("id") ?: return
        sanitizer.fakeTokenFor(token)
        val entry = entryId
        if (entry != null) {
            val uncheck = obj("ids" to listOf(entry), "checked" to false)
            val bulk = api("guest-bulk-check", "POST", "/api/shopping-list-entry/bulk/", token, uncheck)
            guest("guest.bulk.status", bulk.status)
        }
        api("guest-token-delete", "DELETE", "/api/access-token/$id/", token, record = false)
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the watch token baseline:\n" + mismatches.joinToString("\n"))
    }

    private fun createBody(): JsonObject = obj("scope" to "read write", "expires" to DateTimeFormatter.ISO_INSTANT.format(requestedExpiry))

    /** True when [value] is within a day of the requested expiry (the server answers in its own zone). */
    private fun withinADay(value: String?): Boolean {
        val parsed = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return false
        return Duration.between(parsed, requestedExpiry).abs() <= Duration.of(1, ChronoUnit.DAYS)
    }

    private fun api(
        name: String,
        method: String,
        path: String,
        bearer: String,
        body: JsonElement? = null,
        record: Boolean = true,
    ): Exchange = http.call(
        name,
        method,
        path,
        bearer = bearer,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in BODY_METHODS })?.toString()?.toRequestBody(JSON),
        recordBody = body,
        record = record,
    )

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    /** Recorded for the record, not compared: the app decides about guests on its own. */
    private fun guest(key: String, value: Any?) {
        facts[key] = value
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    private companion object {
        val JSON = "application/json".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val WATCH_TOKEN_DAYS = 90L

        /** What the watch rests on. */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "create.status" to 201,
            "create.scope" to "read write",
            "create.plaintext" to true,
            "create.hasId" to true,
            "create.expiresWithinADay" to true,
            "list.found" to true,
            "list.scope" to "read write",
            "list.expiresWithinADay" to true,
            "watch.read.shopping" to 200,
            "watch.read.supermarkets" to 200,
            "watch.bulk.status" to 200,
            "watch.bulk.checkedOnServer" to true,
            "watch.createsToken.status" to 201,
            "phoneDeletes.status" to 204,
            "afterDelete.read.status" to 403,
            "afterDelete.read.hasDetail" to true,
            "phoneDeletesAgain.status" to 404,
            "selfDelete.status" to 204,
            "afterSelfDelete.read.status" to 403,
        )
    }
}
