package casa.moller.tandem.core.sync.book

import casa.moller.tandem.core.database.entity.RecipeBookEntity
import casa.moller.tandem.core.database.entity.RecipeBookEntryEntity
import casa.moller.tandem.core.database.entity.RecipeBookKinds
import casa.moller.tandem.core.domain.book.RecipeBookRules
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.network.dto.RecipeBookDto
import casa.moller.tandem.core.network.dto.RecipeBookEntryDto
import casa.moller.tandem.core.network.dto.UserDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Phase 2 (böcker lane): books between Tandoor's JSON, Room and core:model (plan 3.7, #17).

/** A server book as a row (`local_id = server_id`); null without an id. [kind] from [RecipeBookRules]. */
internal fun RecipeBookDto.toEntity(accountId: String, wantToCookKeywordIds: Set<Long>): RecipeBookEntity? {
    val bookId = id ?: return null
    val filterObject = filter as? JsonObject
    val filterId = (filterObject?.get("id") as? JsonPrimitive)?.longOrNull
    val search = (filterObject?.get("search") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val kind = RecipeBookRules.kind(name, description, hasFilter = filterObject != null, filterSearch = search, wantToCookKeywordIds)
    return RecipeBookEntity(
        accountId = accountId,
        localId = bookId,
        serverId = bookId,
        name = name,
        description = description,
        createdBy = createdBy?.id,
        createdByName = createdBy?.label(),
        sharedUserIds = BookIds.encode(shared.mapNotNull { it.id }),
        filterId = filterId,
        filterSearch = search,
        bookOrder = order,
        kind = kind.toStored(),
    )
}

/** A server entry as a row; null without an id, book or recipe. */
internal fun RecipeBookEntryDto.toEntity(accountId: String): RecipeBookEntryEntity? {
    val entryId = id ?: return null
    return RecipeBookEntryEntity(
        accountId = accountId,
        localId = entryId,
        serverId = entryId,
        bookId = book ?: bookContent?.id ?: return null,
        recipeId = recipe ?: recipeContent?.id ?: return null,
    )
}

/** The display name Tandoor shows (`display_name`, else the username), null when both are blank. */
internal fun UserDto.label(): String? = displayName.ifBlank { username }.takeIf { it.isNotBlank() }

internal fun RecipeBookKind.toStored(): String = when (this) {
    RecipeBookKind.BOOK -> RecipeBookKinds.BOOK
    RecipeBookKind.SMART -> RecipeBookKinds.SMART
    RecipeBookKind.FAVORITES -> RecipeBookKinds.FAVORITES
    RecipeBookKind.WANT_TO_COOK -> RecipeBookKinds.WANT_TO_COOK
}

internal fun storedKind(kind: String): RecipeBookKind = when (kind) {
    RecipeBookKinds.SMART -> RecipeBookKind.SMART
    RecipeBookKinds.FAVORITES -> RecipeBookKind.FAVORITES
    RecipeBookKinds.WANT_TO_COOK -> RecipeBookKind.WANT_TO_COOK
    else -> RecipeBookKind.BOOK
}

/** A row as the model; [isOwn] = the active user created it (only then may the app write it, plan 3.7). */
internal fun RecipeBookEntity.toModel(userId: Long?, recipeCount: Int): RecipeBook = RecipeBook(
    id = localId,
    name = name,
    description = description,
    kind = storedKind(kind),
    ownerId = createdBy,
    ownerName = createdByName,
    isOwn = userId != null && createdBy == userId,
    sharedWith = BookIds.decode(sharedUserIds).toSet(),
    recipeCount = recipeCount,
)

/** `shared_user_ids` as a JSON array of numbers. */
internal object BookIds {
    fun encode(ids: Collection<Long>): String = ids.distinct().sorted().joinToString(prefix = "[", postfix = "]", separator = ",")

    fun decode(json: String?): List<Long> = runCatching {
        (BookPayloads.parse("{\"ids\":${json ?: "[]"}}")?.get("ids") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }.getOrNull().orEmpty()
}
