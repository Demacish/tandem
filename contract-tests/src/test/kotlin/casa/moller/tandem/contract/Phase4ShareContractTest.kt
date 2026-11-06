package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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
 * Fas 4, komplettering 3 (plan §6.2, §8.6): "Dela recept". What `GET /api/share-link/{id}` (no
 * trailing slash, plan §4.1) answers to the owner, a member, a guest and in a space with sharing
 * turned off, and what the link gives someone who is not logged in.
 *
 * Recordings go to `build/contract-recordings/<tag>/fas4/share/` and are committed to
 * `api-schema/fixtures/<version>/fas4/share/`. Soft checks as in the other phases: every fact is
 * recorded and the last step compares [EXPECTED].
 *
 * Skipped unless TANDOOR_NOSHARE_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_NOSHARE_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase4ShareContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas4/share")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val recipes = mutableListOf<Pair<String, Long>>()
    private var shared: Long? = null
    private var share: String? = null
    private var linkPath: String? = null

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun cleanUp() {
        recipes.forEach { (token, id) -> http.call("cleanup", "DELETE", "/api/recipe/$id/", bearer = token, record = false) }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    @Test
    @Order(10)
    fun theOwnersLink() {
        val id = recipe("recept", "Tandem delas", env.fas1Token) ?: return
        shared = id
        val first = http.call("lank", "GET", "/api/share-link/$id", bearer = env.fas1Token)
        observe("link.status", first.status)
        val answer = first.json as? JsonObject
        observe("link.fields", answer?.let(Facts::fieldTypes))
        observe("link.pkIsTheRecipe", answer?.longAt("pk") == id)
        share = answer?.stringAt("share")
        observe("link.shareIsUuid", share?.matches(UUID))
        val link = answer?.stringAt("link")
        observe("link.shape", link?.replace(share.orEmpty(), "<uuid>")?.replace("/$id", "/<id>"))
        linkPath = link?.removePrefix("https://${ContractEnv.PUBLIC_HOST}")?.takeIf { it.startsWith("/") }

        // Every call makes a new row on the server: why the app asks once per share and never ahead.
        val second = http.call("lank-igen", "GET", "/api/share-link/$id", bearer = env.fas1Token)
        observe("link.again.status", second.status)
        observe("link.again.isNew", (second.json as? JsonObject)?.stringAt("share") != share)

        observe("link.withSlash.status", http.call("lank-med-snedstreck", "GET", "/api/share-link/$id/", bearer = env.fas1Token).status)
        observe("link.unknown.status", http.call("lank-okant", "GET", "/api/share-link/$UNKNOWN_ID", bearer = env.fas1Token).status)
        observe("link.noToken.status", http.call("lank-utan-token", "GET", "/api/share-link/$id").status)
    }

    @Test
    @Order(20)
    fun whatTheLinkOpens() {
        val id = shared ?: return
        val page = linkPath?.let { path ->
            http.call("sida", "GET", path, record = false, extraHeaders = mapOf("Accept" to "text/html"))
        }
        observe("open.page.status", page?.status)
        observe("open.page.isHtml", page?.contentType?.startsWith("text/html"))
        val read = http.call("oppna-med-delning", "GET", "/api/recipe/$id/?share=$share")
        observe("open.api.status", read.status)
        observe("open.api.name", (read.json as? JsonObject)?.stringAt("name"))
        observe("open.withoutShare.status", http.call("oppna-utan-delning", "GET", "/api/recipe/$id/").status)
        observe("open.wrongShare.status", http.call("oppna-fel-delning", "GET", "/api/recipe/$id/?share=$WRONG_SHARE").status)
    }

    @Test
    @Order(30)
    fun otherPeople() {
        val id = shared ?: return
        val member = http.call("medlem", "GET", "/api/share-link/$id", bearer = env.readerToken)
        observe("member.status", member.status)
        val guest = http.call("gast", "GET", "/api/share-link/$id", bearer = env.guestToken)
        observe("guest.status", guest.status)
        observe("guest.body", guest.json)

        val hidden = recipe("privat", "Tandem privat", env.fas1Token, "private" to true) ?: return
        val own = http.call("privat-agaren", "GET", "/api/share-link/$hidden", bearer = env.fas1Token)
        observe("private.owner.status", own.status)
        observe("private.member.status", http.call("privat-medlem", "GET", "/api/share-link/$hidden", bearer = env.readerToken).status)
        val key = (own.json as? JsonObject)?.stringAt("share")
        observe("private.opened.status", http.call("privat-oppnad", "GET", "/api/recipe/$hidden/?share=$key").status)
    }

    /** `allow_sharing` is read-only in the API: contract.yml makes a second space with it off. */
    @Test
    @Order(40)
    fun aSpaceWithoutSharing() {
        val space = http.call("space-utan-delning", "GET", "/api/space/current/", bearer = env.noShareToken)
        observe("off.space.status", space.status)
        observe("off.space.allowSharing", (space.json as? JsonObject)?.boolAt("allow_sharing"))
        val id = recipe("utan-delning", "Tandem utan delning", env.noShareToken) ?: return
        val refused = http.call("lank-utan-delning", "GET", "/api/share-link/$id", bearer = env.noShareToken)
        observe("off.status", refused.status)
        observe("off.body", refused.json)
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the share link baseline:\n" + mismatches.joinToString("\n"))
    }

    private fun recipe(slug: String, name: String, token: String, vararg extra: Pair<String, Any?>): Long? {
        val own = listOf<Pair<String, Any?>>(
            "name" to name,
            "description" to "",
            "servings" to 2,
            "servings_text" to "",
            "working_time" to 0,
            "waiting_time" to 0,
            "internal" to true,
            "keywords" to JsonArray(emptyList()),
            "steps" to JsonArray(emptyList()),
        )
        val body: JsonElement = JsonObject((own + extra).associate { (k, v) -> k to Facts.toJson(v) })
        val created = http.call(
            "recept-$slug",
            "POST",
            "/api/recipe/",
            bearer = token,
            body = body.toString().toRequestBody(JSON),
            recordBody = body,
        )
        observe("recipe.$slug.status", created.status)
        val id = (created.json as? JsonObject)?.longAt("id") ?: return null
        recipes += token to id
        return id
    }

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        const val UNKNOWN_ID = 99_999_999L
        const val WRONG_SHARE = "00000000-0000-4000-8000-000000000000"

        /** What the app rests on. */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "link.status" to 200,
            "link.pkIsTheRecipe" to true,
            "link.shareIsUuid" to true,
            "link.again.status" to 200,
            "link.again.isNew" to true,
            "link.unknown.status" to 404,
            "open.page.status" to 200,
            "open.api.status" to 200,
            "open.api.name" to "Tandem delas",
            "guest.status" to 403,
            "off.space.allowSharing" to false,
            "off.status" to 403,
        )
    }
}
