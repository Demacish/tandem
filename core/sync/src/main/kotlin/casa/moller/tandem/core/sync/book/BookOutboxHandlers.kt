package casa.moller.tandem.core.sync.book

import android.content.Context
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.domain.book.RecipeBookRules
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.dto.CustomFilterDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.sync.R
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.drink.DrinkPayloads
import casa.moller.tandem.core.sync.drink.PairingKeywords
import casa.moller.tandem.core.sync.drink.RecipeKeywordChanges
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.engine.OutboxResult
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.shopping.claimedServerIds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.withLock
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
    private val sync: BookSync,
) : OutboxHandler {
    // Under BookSync.lock (0.3.0 review, fix-data): a books read never removes a book created meanwhile.
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = sync.lock.withLock {
        when (row.op) {
            OutboxOps.CREATE -> create(remote, row, payload)
            OutboxOps.DELETE -> delete(remote, row)
            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
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
        val created = existing ?: when (val sent = caller.write(remote) { books.createBook(body) }) {
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

    /**
     * A book of the user with this name that no other create of this device claimed: the answer of
     * an earlier attempt was lost. A copy a books read stored meanwhile counts too (0.3.0 review,
     * fix-data): `completeCreate` drops it, so the retry never makes a second "Favoriter".
     */
    private suspend fun findLostCreate(remote: ActiveSource.Remote, name: String): Long? {
        val listed = caller.call(remote, background = true) { taxonomy.recipeBooks(page = null) }
        val claimed = claimedServerIds(databases, remote, OutboxEntities.RECIPE_BOOK)
        return (listed as? Outcome.Success)?.value?.results
            ?.firstOrNull { it.name == name && it.createdBy?.id == remote.account.userId && it.id != null && it.id !in claimed }
            ?.id
    }

    private suspend fun delete(remote: ActiveSource.Remote, row: OutboxEntity): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Dropped
        return when (val sent = caller.write(remote) { books.deleteBook(serverId) }) {
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
    private val sync: BookSync,
) : OutboxHandler {
    // Under BookSync.lock (0.3.0 review, fix-data): a books read never removes an entry (a heart) created meanwhile.
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = sync.lock.withLock {
        when (row.op) {
            OutboxOps.CREATE -> create(remote, row, payload)
            OutboxOps.DELETE -> delete(remote, row)
            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val body = BookPayloads.parse(payload) ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val bookId = (body["book"] as? JsonPrimitive)?.longOrNull
        val recipeId = (body["recipe"] as? JsonPrimitive)?.longOrNull
        if (bookId == null || recipeId == null || bookId < 0) return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        if (row.attempts > 0) findOnServer(remote, bookId, recipeId)?.let { return OutboxResult.Created(it) }
        return when (val sent = caller.write(remote) { books.createEntry(body) }) {
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
        return when (val sent = caller.write(remote) { books.deleteEntry(serverId) }) {
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
 * The recipe's `RECIPE_KEYWORDS` rows ("Vill laga", plan 3.7 B; Fas S: pairings, plan §8.8): the
 * recipe is read fresh (`GET /api/recipe/{id}/`), the row's changes are applied field by field in
 * order (`RecipeKeywordFields`: "Vill laga", `keyword:<id>`, `pairing:<name>`), and its WHOLE keyword
 * list is sent (`PATCH /api/recipe/{id}/`: a PATCH replaces the list, so a keyword a partner added
 * meanwhile is kept; FACTS "Fas S c"). A pairing by name is found or created under "Passar med"
 * first ([PairingKeywords]). Nothing to change = no PATCH. The answer is stored as the recipe's
 * detail. After the first "Vill laga" the shared smart book is created when none is visible yet (a
 * saved filter on the keyword id, so the web shows the list).
 */
internal class RecipeKeywordsOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val details: DetailFetcher,
    private val names: BookNames,
    private val sync: BookSync,
    private val pairingKeywords: PairingKeywords,
) : OutboxHandler {
    // Under BookSync.lock (0.3.0 review, fix-data): the "Vill laga" book it may create is never removed by a read.
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult =
        sync.lock.withLock { sendLocked(remote, row, payload) }

    private suspend fun sendLocked(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult {
        val recipeId = row.serverId
        val changes = DrinkPayloads.changesOf(payload)?.takeUnless { it.isEmpty }
        if (row.op != OutboxOps.RECIPE_KEYWORDS || recipeId == null || changes == null) {
            return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
        val current = when (
            val read = caller.call(remote, background = true, confirmUnauthorized = true) {
                recipes.recipeJson(recipeId)
            }
        ) {
            is Outcome.Failure -> return if (read.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(read.error)
            is Outcome.Success -> read.value
        }
        val before = (current["keywords"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { keyword ->
            SentKeyword(
                id = (keyword["id"] as? JsonPrimitive)?.longOrNull,
                name = keyword.string("name").orEmpty(),
                parentId = (keyword["parent"] as? JsonPrimitive)?.longOrNull,
            )
        }
        val list = when (val applied = apply(remote, before, changes)) {
            is Outcome.Failure -> return OutboxResult.Failed(applied.error)
            is Outcome.Success -> applied.value
        }
        if (list == before) {
            details.store(remote, recipeId, current)
            return OutboxResult.Sent
        }
        val body = JsonObject(mapOf("keywords" to JsonArray(list.map { it.json() })))
        val sent = caller.write(remote) { books.patchRecipe(recipeId, body) }
        val patched = when (sent) {
            is Outcome.Failure -> return if (sent.error == TandemError.NotFound) OutboxResult.Dropped else OutboxResult.Failed(sent.error)
            is Outcome.Success -> sent.value
        }
        details.store(remote, recipeId, patched)
        if (changes.wantToCook == true) {
            val keywordId = (patched["keywords"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .firstOrNull { RecipeBookRules.isWantToCookKeyword(it.string("name").orEmpty()) }
                ?.let { (it["id"] as? JsonPrimitive)?.longOrNull }
            if (keywordId != null) ensureWantToCookBook(remote, keywordId)
        }
        return OutboxResult.Sent
    }

    /** [before] (the recipe's keywords as read just now) with every change of the row applied in order. */
    private suspend fun apply(
        remote: ActiveSource.Remote,
        before: List<SentKeyword>,
        changes: RecipeKeywordChanges,
    ): Outcome<List<SentKeyword>> {
        var list = before
        changes.wantToCook?.let { want ->
            val has = list.any { RecipeBookRules.isWantToCookKeyword(it.name) }
            if (has != want) {
                list = list.filterNot { RecipeBookRules.isWantToCookKeyword(it.name) }
                if (want) list = list + SentKeyword(null, WantToCook.KEYWORD)
            }
        }
        val database = databases.get(remote.kind)
        val storedNames = if (changes.keywords.any { it.second }) {
            database.drinkDraftDao().getKeywords(remote.accountId).associate { it.id to it.name }
        } else {
            emptyMap()
        }
        for ((field, add) in changes.keywords) {
            // A temporary id that never reached the server has nothing to link.
            val id = database.idRewriteDao().current(remote.accountId, OutboxEntities.KEYWORD, field).takeIf { it >= 0 } ?: continue
            list = when {
                !add -> list.filterNot { it.id == id }
                list.any { it.id == id } -> list
                else -> list + SentKeyword(id, storedNames[id].orEmpty())
            }
        }
        for ((name, add) in changes.pairings) {
            list = if (add) {
                val id = when (val found = pairingKeywords.pairing(remote, name)) {
                    is Outcome.Failure -> return found
                    is Outcome.Success -> found.value
                }
                if (list.any { it.id == id }) list else list + SentKeyword(id, name.trim())
            } else {
                // Only a keyword with a parent (under "Passar med") goes; a root keyword of the same name stays.
                list.filterNot { it.parentId != null && DrinkKeywords.key(it.name) == DrinkKeywords.key(name) }
            }
        }
        return Outcome.Success(list)
    }

    /** One element of the keyword list sent back: `{id, name}`, or `{name}` for a keyword reused or created by name. */
    private data class SentKeyword(val id: Long?, val name: String, val parentId: Long? = null) {
        fun json(): JsonObject = buildJsonObject {
            id?.let { put("id", it) }
            put("name", name)
        }
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
        val filter = (caller.write(remote) { books.createFilter(filterBody) } as? Outcome.Success)?.value ?: return
        val bookBody = buildJsonObject {
            put("name", names.wantToCook)
            put("description", "")
            put("shared", others)
            put("filter", TandoorJson.encodeToJsonElement(CustomFilterDto.serializer(), filter))
        }
        val book = (caller.write(remote) { books.createBook(bookBody) } as? Outcome.Success)?.value ?: return
        book.toEntity(remote.accountId, setOf(keywordId))?.let { dao.upsertBooks(listOf(it)) }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
