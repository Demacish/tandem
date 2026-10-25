package casa.moller.tandem.core.sync.book

import android.content.Context
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.domain.book.RecipeBookRules
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.dto.CustomFilterDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.sync.R
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.engine.OutboxResult
import casa.moller.tandem.core.sync.remote.RemoteCaller
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Phase 2 (böcker lane): the senders of `recipe_book`, `recipe_book_entry` and the recipe's
// `RECIPE_KEYWORDS` rows (docs/BYGGE.md "Fas 2 – kontrakt", table "Outbox"; FACTS "Fas 2 g").
// OutboxSender applies the shared rules (order, refs, id rewrite, 400 = FAILED, 401/403/429 stop).
// The app queues writes only for OWN books (plan 3.7) and never for a guest (#42).

/** Error code of a row this version cannot read (kept FAILED for "Pending changes"). */
private const val UNSUPPORTED: String = "tandem.sync.unsupported-outbox-op"

/** The names of the books Tandem creates, in the app's language (they are seen on the web too). */
internal interface BookNames {
    /** "Favoriter" / "Favourites": the favourites book is "<word> (<owner>)". */
    val favoritesWord: String

    /** "Vill laga" / "Want to cook": the shared smart book. */
    val wantToCook: String
}

@Singleton
internal class ResourceBookNames @Inject constructor(@ApplicationContext private val context: Context) : BookNames {
    override val favoritesWord: String
        get() = context.getString(R.string.sync_book_favorites_word)

    override val wantToCook: String
        get() = context.getString(R.string.sync_book_want_to_cook_name)
}

/**
 * `recipe_book`: CREATE `POST /api/recipe-book/` (an own favourites book is shared with the
 * space's other users, read just before sending), DELETE `DELETE /api/recipe-book/{id}/`.
 */
internal class RecipeBookOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = when (row.op) {
        OutboxOps.CREATE -> create(remote, row, payload)
        OutboxOps.DELETE -> delete(remote, row)
        else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val queued = BookPayloads.parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val shareWith = if (BookPayloads.sharesWithSpace(queued)) {
            when (val users = caller.call(remote, background = true) { books.spaceUsers() }) {
                is Outcome.Failure -> return OutboxResult.Failed(users.error)
                is Outcome.Success -> users.value.mapNotNull { it.id }.filter { it != remote.account.userId }
            }
        } else {
            null
        }
        val body = BookPayloads.bookBody(queued, shareWith)
        val name = (body["name"] as? JsonPrimitive)?.content.orEmpty()
        val existing = if (row.attempts > 0) findLostCreate(remote, name) else null
        val created = existing ?: when (val sent = caller.call(remote, background = true) { books.createBook(body) }) {
            is Outcome.Failure -> return OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value.id ?: return OutboxResult.Failed(TandemError.Decode("recipe-book-without-id"))
        }
        val localId = row.localId
        if (shareWith != null && localId != null) {
            // Shown at once; the next re-read brings the server's own list.
            val dao = databases.get(remote.kind).recipeBookDao()
            dao.getBook(remote.accountId, localId)?.let { dao.upsertBooks(listOf(it.copy(sharedUserIds = BookIds.encode(shareWith)))) }
        }
        return OutboxResult.Created(created)
    }

    /** A book of the user with this name that the device does not know yet: the answer of an earlier attempt was lost. */
    private suspend fun findLostCreate(remote: ActiveSource.Remote, name: String): Long? {
        val listed = caller.call(remote, background = true) { taxonomy.recipeBooks(page = null) }
        val known = databases.get(remote.kind).recipeBookDao().getBooks(remote.accountId).mapNotNull { it.serverId }.toSet()
        return (listed as? Outcome.Success)?.value?.results
            ?.firstOrNull { it.name == name && it.createdBy?.id == remote.account.userId && it.id !in known }
            ?.id
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Dropped
        return when (val sent = caller.call(remote, background = true, expectedNotFound = true) { books.deleteBook(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent
            is Outcome.Failure -> if (sent.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(sent.error)
        }
    }
}

/**
 * `recipe_book_entry`: CREATE `POST /api/recipe-book-entry/` `{book, recipe}`, DELETE
 * `DELETE /api/recipe-book-entry/{id}/`. The recipe already in the book (400 "unique set", or an
 * answer lost to a timeout) becomes that entry; a book or recipe that is gone drops the entry.
 */
internal class RecipeBookEntryOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = when (row.op) {
        OutboxOps.CREATE -> create(remote, row, payload)
        OutboxOps.DELETE -> delete(remote, row)
        else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val body = BookPayloads.parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val bookId = (body["book"] as? JsonPrimitive)?.longOrNull
        val recipeId = (body["recipe"] as? JsonPrimitive)?.longOrNull
        if (bookId == null || recipeId == null || bookId < 0) return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        if (row.attempts > 0) findOnServer(remote, bookId, recipeId)?.let { return OutboxResult.Created(it) }
        return when (val sent = caller.call(remote, background = true) { books.createEntry(body) }) {
            is Outcome.Success -> sent.value.id?.let { OutboxResult.Created(it) }
                ?: OutboxResult.Failed(TandemError.Decode("entry-without-id"))
            is Outcome.Failure -> failedCreate(remote, row, bookId, recipeId, sent.error)
        }
    }

    private suspend fun failedCreate(
        remote: ActiveSource.Remote,
        row: OutboxEntity,
        bookId: Long,
        recipeId: Long,
        error: TandemError,
    ): OutboxResult {
        if (error !is TandemError.BadRequest) return OutboxResult.Failed(error)
        if (error.nonFieldErrors.isNotEmpty()) {
            // "The fields recipe, book must make a unique set": it is in the book already.
            findOnServer(remote, bookId, recipeId)?.let { return OutboxResult.Created(it) }
        }
        if (FIELD_BOOK in error.fieldErrors || FIELD_RECIPE in error.fieldErrors) {
            // Tandoor answers 400 "Invalid pk" for a book or recipe deleted on the server.
            row.localId?.let { databases.get(remote.kind).recipeBookDao().deleteEntries(remote.accountId, listOf(it)) }
            return OutboxResult.Dropped
        }
        return OutboxResult.Failed(error)
    }

    private suspend fun findOnServer(remote: ActiveSource.Remote, bookId: Long, recipeId: Long): Long? {
        val listed = caller.call(remote, background = true) { books.entriesOfRecipe(recipe = recipeId, page = null) }
        return (listed as? Outcome.Success)?.value?.results?.firstOrNull { it.book == bookId && it.recipe == recipeId }?.id
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Dropped
        return when (val sent = caller.call(remote, background = true, expectedNotFound = true) { books.deleteEntry(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent
            is Outcome.Failure -> if (sent.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(sent.error)
        }
    }

    private companion object {
        const val FIELD_BOOK = "book"
        const val FIELD_RECIPE = "recipe"
    }
}

/**
 * The recipe's `RECIPE_KEYWORDS` rows ("Vill laga", plan 3.7 B): the recipe is read fresh
 * (`GET /api/recipe/{id}/`), then its WHOLE keyword list is sent with or without the
 * [WantToCook.KEYWORD] keyword (`PATCH /api/recipe/{id}/`, the server reuses the keyword by name).
 * The answer is stored as the recipe's detail. After the first "Vill laga" the shared smart book is
 * created when none is visible yet (a saved filter on the keyword id, so the web shows the list).
 */
internal class RecipeKeywordsOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val details: DetailFetcher,
    private val names: BookNames,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val recipeId = row.serverId
        val want = BookPayloads.wantToCookOf(payload)
        if (row.op != OutboxOps.RECIPE_KEYWORDS || recipeId == null || want == null) {
            return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
        val current = when (
            val read = caller.call(remote, background = true, confirmUnauthorized = true, expectedNotFound = true) {
                recipes.recipeJson(recipeId)
            }
        ) {
            is Outcome.Failure -> return if (read.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(read.error)
            is Outcome.Success -> read.value
        }
        val keywords = (current["keywords"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val has = keywords.any { RecipeBookRules.isWantToCookKeyword(it.string("name").orEmpty()) }
        if (has == want) {
            details.store(remote, recipeId, current)
            return OutboxResult.Sent
        }
        val kept = keywords.filterNot { RecipeBookRules.isWantToCookKeyword(it.string("name").orEmpty()) }.map { keyword ->
            buildJsonObject {
                keyword["id"]?.let { put("id", it) }
                put("name", keyword.string("name").orEmpty())
            }
        }
        val list = if (want) kept + buildJsonObject { put("name", WantToCook.KEYWORD) } else kept
        val body = JsonObject(mapOf("keywords" to JsonArray(list)))
        val sent = caller.call(remote, background = true, expectedNotFound = true) { books.patchRecipe(recipeId, body) }
        val patched = when (sent) {
            is Outcome.Failure -> return if (sent.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        details.store(remote, recipeId, patched)
        if (want) {
            val keywordId = (patched["keywords"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { RecipeBookRules.isWantToCookKeyword(it.string("name").orEmpty()) }
                ?.let { (it["id"] as? JsonPrimitive)?.longOrNull }
            if (keywordId != null) ensureWantToCookBook(remote, keywordId)
        }
        return OutboxResult.Sent
    }

    /**
     * Creates "Vill laga" (a saved filter on [keywordId] and a smart book on it, shared with the
     * space) unless a want-to-cook book is visible already. Best effort: a failure leaves it for
     * the next "Vill laga"; the list in the app works without the book.
     */
    private suspend fun ensureWantToCookBook(remote: ActiveSource.Remote, keywordId: Long) {
        val dao = databases.get(remote.kind).recipeBookDao()
        if (dao.getBooks(remote.accountId).any { storedKind(it.kind) == RecipeBookKind.WANT_TO_COOK }) return
        val users = (caller.call(remote, background = true) { books.spaceUsers() } as? Outcome.Success)?.value ?: return
        val others = BookPayloads.userRefs(users.mapNotNull { it.id }.filter { it != remote.account.userId })
        val filterBody = buildJsonObject {
            put("name", names.wantToCook)
            put("search", WantToCook.filterSearch(keywordId))
            put("shared", others)
        }
        val filter = (caller.call(remote, background = true) { books.createFilter(filterBody) } as? Outcome.Success)?.value ?: return
        val bookBody = buildJsonObject {
            put("name", names.wantToCook)
            put("description", "")
            put("shared", others)
            put("filter", TandoorJson.encodeToJsonElement(CustomFilterDto.serializer(), filter))
        }
        val book = (caller.call(remote, background = true) { books.createBook(bookBody) } as? Outcome.Success)?.value ?: return
        book.toEntity(remote.accountId, setOf(keywordId))?.let { dao.upsertBooks(listOf(it)) }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

