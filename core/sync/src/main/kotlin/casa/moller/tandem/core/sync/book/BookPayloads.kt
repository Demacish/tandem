package casa.moller.tandem.core.sync.book

import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.outbox.OutboxRefs
import casa.moller.tandem.core.model.book.WantToCook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The outbox payloads of books, entries and "Vill laga" (docs/BYGGE.md "Fas 2 – kontrakt", table
 * "Outbox"; FACTS "Fas 2 g"). Temporary (negative) ids are listed in `refs`.
 */
internal object BookPayloads {
    /**
     * App-internal key of a book CREATE: share the book with every other user of the space when
     * it is sent (an own favourites book, plan 3.7 A). The sender replaces it with `shared` (the
     * users are read online, `GET /api/user/`) and never sends the key itself.
     */
    const val SHARE_WITH_SPACE: String = "tandem_share_with_space"

    /** `POST /api/recipe-book/`: `shared` is required (FACTS "Fas 1 h"); `filter` null for a plain book. */
    fun createBook(name: String, description: String, shareWithSpace: Boolean): String = buildJsonObject {
        put("name", name)
        put("description", description)
        put("shared", JsonArray(emptyList()))
        put("filter", JsonNull)
        if (shareWithSpace) put(SHARE_WITH_SPACE, true)
    }.toString()

    /** `POST /api/recipe-book-entry/` `{book, recipe}`; [bookId] may be a temporary id (see [createEntryRefs]). */
    fun createEntry(bookId: Long, recipeId: Long): String = buildJsonObject {
        put("book", bookId)
        put("recipe", recipeId)
    }.toString()

    /** `refs` of an entry CREATE whose book has no server id yet. */
    fun createEntryRefs(bookId: Long): String? = if (bookId < 0) OutboxRefs.encode(mapOf("book" to OutboxEntities.RECIPE_BOOK)) else null

    /** `RECIPE_KEYWORDS` (op on the recipe): the wished state of the want-to-cook keyword. */
    fun wantToCook(want: Boolean): String = buildJsonObject {
        put("keywords", buildJsonObject { put(WantToCook.KEYWORD, want) })
    }.toString()

    /** The wished want-to-cook state of a `RECIPE_KEYWORDS` payload, null when it says nothing about it. */
    fun wantToCookOf(payload: String): Boolean? {
        val keywords = parse(payload)?.get("keywords") as? JsonObject ?: return null
        return (keywords[WantToCook.KEYWORD] as? JsonPrimitive)?.booleanOrNull
    }

    /** The body actually sent for a book CREATE: [SHARE_WITH_SPACE] replaced by `shared: [{id} ...]`. */
    fun bookBody(payload: JsonObject, shareWith: List<Long>?): JsonObject {
        val share = (payload[SHARE_WITH_SPACE] as? JsonPrimitive)?.booleanOrNull == true
        val fields = payload.filterKeys { it != SHARE_WITH_SPACE }.toMutableMap()
        if (share && shareWith != null) fields["shared"] = userRefs(shareWith)
        return JsonObject(fields)
    }

    /** Whether a book CREATE wants to be shared with the space. */
    fun sharesWithSpace(payload: JsonObject): Boolean = (payload[SHARE_WITH_SPACE] as? JsonPrimitive)?.booleanOrNull == true

    /** `[{id} ...]` as Tandoor's `shared` takes it. */
    fun userRefs(ids: List<Long>): JsonArray = buildJsonArray { ids.forEach { add(buildJsonObject { put("id", it) }) } }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): JsonObject? = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
}
