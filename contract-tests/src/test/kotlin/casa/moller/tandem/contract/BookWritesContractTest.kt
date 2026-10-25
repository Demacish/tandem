package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Phase 2 (böcker lane): the books' writes exactly as the app's outbox sends them (`core:sync`
 * `BookPayloads` and the book senders, docs/BYGGE.md "Böcker, favoriter och Vill laga"), against
 * the same temporary Tandoor, AFTER Phase2ContractTest (its own step in contract.yml, so nothing
 * here can change that test's facts). Everything runs as the second member (group `user`, the
 * typical household member); nothing is recorded, and every assertion is something the app relies
 * on:
 *
 * - `GET /api/user/` lists the space's users as an array (who a favourites book is shared with);
 * - an own favourites book created with `shared` is visible, read-only, to the users it names;
 * - an entry `{book, recipe}` is listed by `?recipe=`, a second one gives 400 `non_field_errors`,
 *   DELETE answers 204 and then 404;
 * - "Vill laga": a member PATCHes the whole keyword list with `{name}` for the new keyword, then
 *   creates the saved filter on its id and the smart book with the filter object as the server
 *   returned it; the owner sees the book and its recipe through `?filter=`;
 * - `user-space/all_personal` gives the member's and the guest's groups.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_GUEST_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookWritesContractTest {
    private val env = ContractEnv
    private val today: LocalDate = LocalDate.now(ZoneId.of("Europe/Stockholm"))
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val http = TandoorHttp(
        env.baseUrl,
        sanitizer,
        File(env.recordDir, "fas2-book-writes"),
        numbered = false,
        defaultHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https"),
    )

    @Test
    fun theRolesComeFromAllPersonal() {
        val member = api("GET", "/api/user-space/all_personal/", token = env.readerToken)
        assertEquals(200, member.status, "$member")
        assertEquals(listOf("user"), groupsOf(member.json))
        val guest = api("GET", "/api/user-space/all_personal/", token = env.guestToken)
        assertEquals(listOf("guest"), groupsOf(guest.json))
    }

    @Test
    fun aMembersFavouritesBookIsSharedWithTheSpaceAndHoldsEntries() {
        val users = api("GET", "/api/user/", token = env.readerToken)
        assertEquals(200, users.status, "$users")
        val userList = users.json as? JsonArray ?: error("not an array: $users")
        val ids = userList.objects().mapNotNull { it.longAt("id") }
        val me = memberId()
        val owner = ownerId()
        assertTrue(me in ids && owner in ids, "the space's users: $ids")

        val others = ids.filter { it != me }
        val bookBody = obj(
            "name" to "Favoriter (contract-reader)",
            "description" to "",
            "shared" to others.map { mapOf("id" to it) },
            "filter" to null,
        )
        val book = api("POST", "/api/recipe-book/", bookBody, token = env.readerToken)
        assertEquals(201, book.status, "$book")
        val bookId = book.jsonObject.longAt("id") ?: error("book without id")
        assertEquals(me, book.jsonObject.objAt("created_by")?.longAt("id"))
        assertEquals(others.toSet(), book.jsonObject.arrAt("shared").objects().mapNotNull { it.longAt("id") }.toSet())
        val ownerSees = api("GET", "/api/recipe-book/?page_size=200", token = env.fas1Token).jsonObject.arrAt("results").objects()
        assertTrue(ownerSees.any { it.longAt("id") == bookId }, "the owner of the space sees the member's favourites")

        val recipe = recipeId()
        val entry = api("POST", "/api/recipe-book-entry/", obj("book" to bookId, "recipe" to recipe), token = env.readerToken)
        assertEquals(201, entry.status, "$entry")
        val entryId = entry.jsonObject.longAt("id") ?: error("entry without id")
        val byRecipe = api("GET", "/api/recipe-book-entry/?recipe=$recipe&page_size=200", token = env.readerToken)
        assertTrue(byRecipe.jsonObject.arrAt("results").objects().any { it.longAt("id") == entryId && it.longAt("book") == bookId })
        val twice = api("POST", "/api/recipe-book-entry/", obj("book" to bookId, "recipe" to recipe), token = env.readerToken)
        assertEquals(400, twice.status, "$twice")
        assertTrue(twice.jsonObject.arrAt("non_field_errors").isNotEmpty(), "unique set: $twice")
        assertEquals(204, api("DELETE", "/api/recipe-book-entry/$entryId/", token = env.readerToken).status)
        assertEquals(404, api("DELETE", "/api/recipe-book-entry/$entryId/", token = env.readerToken).status)
        assertEquals(204, api("DELETE", "/api/recipe-book/$bookId/", token = env.readerToken).status)
    }

    @Test
    fun wantToCookIsTheKeywordListAndASmartBookOnItsId() {
        val recipe = recipeId()
        val detail = api("GET", "/api/recipe/$recipe/", token = env.readerToken).jsonObject
        val kept = detail.arrAt("keywords").objects()
            .filter { it.stringAt("name") != WANT_TO_COOK }
            .map { mapOf("id" to it.longAt("id"), "name" to it.stringAt("name")) }
        val add = api("PATCH", "/api/recipe/$recipe/", obj("keywords" to kept + mapOf("name" to WANT_TO_COOK)), token = env.readerToken)
        assertEquals(200, add.status, "$add")
        val keyword = add.jsonObject.arrAt("keywords").objects().single { it.stringAt("name") == WANT_TO_COOK }
        val keywordId = keyword.longAt("id") ?: error("keyword without id")
        assertEquals(kept.size + 1, add.jsonObject.arrAt("keywords").size, "the other keywords stay")

        val owner = ownerId()
        val filterBody = obj(
            "name" to "Vill laga (kontrakt)",
            "search" to """{"keywords_or":[$keywordId]}""",
            "shared" to listOf(mapOf("id" to owner)),
        )
        val filter = api("POST", "/api/custom-filter/", filterBody, token = env.readerToken)
        assertEquals(201, filter.status, "$filter")
        // The book's filter is the object the server returned (core:sync encodes CustomFilterDto back).
        val returned = filter.jsonObject
        val smartBody = obj(
            "name" to "Vill laga (kontrakt)",
            "description" to "",
            "shared" to listOf(mapOf("id" to owner)),
            "filter" to JsonObject(returned.filterKeys { it in FILTER_FIELDS }),
        )
        val smart = api("POST", "/api/recipe-book/", smartBody, token = env.readerToken)
        assertEquals(201, smart.status, "$smart")
        val smartId = smart.jsonObject.longAt("id") ?: error("book without id")
        assertEquals(returned.longAt("id"), smart.jsonObject.objAt("filter")?.longAt("id"))
        val filterId = returned.longAt("id")
        val listed = api("GET", "/api/recipe/?filter=$filterId&page_size=100", token = env.fas1Token)
        assertTrue(listed.jsonObject.arrAt("results").objects().any { it.longAt("id") == recipe }, "the owner sees the queue: $listed")

        val remove = api("PATCH", "/api/recipe/$recipe/", obj("keywords" to kept), token = env.readerToken)
        assertEquals(200, remove.status, "$remove")
        assertTrue(remove.jsonObject.arrAt("keywords").objects().none { it.stringAt("name") == WANT_TO_COOK })
        assertEquals(204, api("DELETE", "/api/recipe-book/$smartId/", token = env.readerToken).status)
    }

    private fun groupsOf(json: JsonElement?): List<String> =
        json.objects().firstOrNull()?.arrAt("groups").objects().mapNotNull { it.stringAt("name") }

    private fun memberId(): Long = api("GET", "/api/user-preference/", token = env.readerToken).json.objects().single().objAt("user")
        ?.longAt("id") ?: error("no member id")

    private fun ownerId(): Long = api("GET", "/api/user-preference/", token = env.fas1Token).json.objects().single().objAt("user")
        ?.longAt("id") ?: error("no owner id")

    /** The recipe Phase2ContractTest seeded ("Potatisgratäng"). */
    private fun recipeId(): Long = api("GET", "/api/recipe/?query=Potatisgrat&page_size=10", token = env.readerToken)
        .jsonObject.arrAt("results").objects().firstOrNull()?.longAt("id") ?: error("the Phase 2 recipe is missing")

    private fun api(method: String, path: String, body: JsonElement? = null, token: String): Exchange = http.call(
        name = "book-writes",
        method = method,
        path = path,
        bearer = token,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in setOf("POST", "PATCH", "PUT") })?.toString()?.toRequestBody(JSON),
        record = false,
    )

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to Facts.toJson(value) })

    private companion object {
        val JSON = "application/json".toMediaType()
        const val WANT_TO_COOK = "vill-laga"

        /** The fields of `CustomFilterDto` (what the app sends back as the book's `filter`). */
        val FILTER_FIELDS = setOf("id", "name", "search", "shared", "created_by")
    }
}
