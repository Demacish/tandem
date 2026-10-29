package casa.moller.tandem.core.sync.pantry

import android.content.Context
import casa.moller.tandem.core.database.entity.InventoryEntryEntity
import casa.moller.tandem.core.database.entity.InventoryLocationEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.outbox.OutboxRefs
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryUnit
import casa.moller.tandem.core.network.dto.InventoryEntryDto
import casa.moller.tandem.core.network.dto.InventoryLocationDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fas S (hemma-data lane): the names Tandem gives what it creates for the pantry, in the device's
 * language (they are server data afterwards, seen on the web too). Tests use fixed names.
 */
internal interface PantryNames {
    /** The default locations created the first time the pantry is used: Kyl, Frys (`is_freezer`), Skafferi. */
    val fridge: String
    val freezer: String
    val pantry: String

    /** The unit of an entry without one ("st"; Tandoor requires a unit, FACTS "Fas S b"). */
    val defaultUnit: String
}

@Singleton
internal class ResourcePantryNames @Inject constructor(@ApplicationContext private val context: Context) : PantryNames {
    override val fridge: String
        get() = context.getString(R.string.sync_pantry_location_fridge)

    override val freezer: String
        get() = context.getString(R.string.sync_pantry_location_freezer)

    override val pantry: String
        get() = context.getString(R.string.sync_pantry_location_pantry)

    override val defaultUnit: String
        get() = context.getString(R.string.sync_pantry_default_unit)
}

/**
 * The outbox payloads of the pantry (docs/BYGGE.md "Fas S – kontrakt", table "Outboxen"). An
 * entry's `inventory_location` holds only the location's id (temporary while unsent, `refs`); the
 * sender replaces it with the WHOLE location object from Room just before sending ([PantryLocations]).
 */
internal object PantryPayloads {
    const val FIELD_LOCATION: String = "inventory_location"
    const val FIELD_AMOUNT: String = "amount"
    const val FIELD_EXPIRES: String = "expires"
    const val FIELD_NOTE: String = "note"
    const val FIELD_SUB_LOCATION: String = "sub_location"
    const val FIELD_ON_HAND: String = "on_hand"

    private val json = Json { ignoreUnknownKeys = true }

    fun location(name: String, isFreezer: Boolean): String = buildJsonObject {
        put("name", name)
        put("is_freezer", isFreezer)
    }.toString()

    @Suppress("LongParameterList") // The entry's fields.
    fun createEntry(
        locationId: Long,
        foodName: String,
        foodId: Long?,
        unitName: String,
        amount: Double,
        expires: LocalDate?,
        note: String?,
        subLocation: String?,
    ): String = buildJsonObject {
        put(FIELD_LOCATION, locationId)
        put(
            "food",
            buildJsonObject {
                put("name", foodName)
                if (foodId != null) put("id", foodId)
            },
        )
        put("unit", buildJsonObject { put("name", unitName) })
        put(FIELD_AMOUNT, amount)
        put(FIELD_EXPIRES, expires?.toString())
        // Tandoor stores "" for no note (as the web does); no sub-location is null (FACTS "Fas S b").
        put(FIELD_NOTE, note.orEmpty())
        put(FIELD_SUB_LOCATION, subLocation)
    }.toString()

    /** `refs` of a payload whose location may still be temporary. */
    fun locationRefs(locationId: Long?): String? =
        if (locationId != null && locationId < 0) OutboxRefs.encode(mapOf(FIELD_LOCATION to OutboxEntities.INVENTORY_LOCATION)) else null

    fun amount(amount: Double): String = buildJsonObject { put(FIELD_AMOUNT, amount) }.toString()

    /** A PATCH of the changed fields only (null = unchanged; [clearExpires] sends `expires: null`). */
    @Suppress("LongParameterList") // The entry's fields.
    fun patchEntry(
        amount: Double?,
        locationId: Long?,
        expires: LocalDate?,
        clearExpires: Boolean,
        note: String?,
        subLocation: String?,
    ): String = buildJsonObject {
        if (amount != null) put(FIELD_AMOUNT, amount)
        if (locationId != null) put(FIELD_LOCATION, locationId)
        when {
            clearExpires -> put(FIELD_EXPIRES, JsonNull)
            expires != null -> put(FIELD_EXPIRES, expires.toString())
        }
        if (note != null) put(FIELD_NOTE, note)
        if (subLocation != null) put(FIELD_SUB_LOCATION, subLocation)
    }.toString()

    /** One food's row of `BATCH_ON_HAND` (`food/batch_update/`, FACTS "Fas S e"). */
    fun onHand(onHand: Boolean): String = buildJsonObject { put(FIELD_ON_HAND, onHand) }.toString()

    /** Rows with the same key go out in one `PUT food/batch_update/`. */
    fun onHandBatchKey(onHand: Boolean): String = "${OutboxEntities.FOOD}:$FIELD_ON_HAND=$onHand"

    fun parse(payload: String): JsonObject? = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject

    /** [body]'s field [key] as a number, null when absent or not a number. */
    fun number(body: JsonObject, key: String): Double? =
        (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()

    fun string(body: JsonObject, key: String): String? = (body[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun hasNull(body: JsonObject, key: String): Boolean = body[key] is JsonNull
}

// ------------------------------------------------------------------------------------------ mappers

/** A server location as a row (`local_id = server_id`), keeping the device's [order]. */
internal fun InventoryLocationDto.toEntity(accountId: String, order: Int, now: Long): InventoryLocationEntity? {
    val serverId = id ?: return null
    return InventoryLocationEntity(
        accountId = accountId,
        localId = serverId,
        serverId = serverId,
        name = name,
        isFreezer = isFreezer,
        householdId = household?.id,
        householdName = household?.name,
        locationOrder = order,
        fetchedAtEpochMillis = now,
    )
}

/** A server entry as a row (`local_id = server_id`). */
internal fun InventoryEntryDto.toEntity(accountId: String, now: Long): InventoryEntryEntity? {
    val serverId = id ?: return null
    val locationId = inventoryLocation?.id ?: return null
    return InventoryEntryEntity(
        accountId = accountId,
        localId = serverId,
        serverId = serverId,
        locationId = locationId,
        subLocation = subLocation?.takeIf { it.isNotBlank() },
        code = code,
        foodId = food?.id,
        foodName = food?.name.orEmpty(),
        foodPluralName = food?.pluralName,
        unitId = unit?.id,
        unitName = unit?.name,
        amount = amount,
        expires = expires?.takeIf { parseDate(it) != null },
        note = note?.takeIf { it.isNotBlank() },
        createdBy = createdBy,
        createdAtEpochMillis = TandoorDates.parseInstantOrNull(createdAt)?.toEpochMilli(),
        fetchedAtEpochMillis = now,
    )
}

internal fun InventoryLocationEntity.toModel(pending: Boolean): InventoryLocation =
    InventoryLocation(id = localId, name = name, isFreezer = isFreezer, pending = pending)

internal fun InventoryEntryEntity.toModel(pending: Boolean): InventoryEntry = InventoryEntry(
    id = localId,
    locationId = locationId,
    food = PantryFood(id = foodId, name = foodName, pluralName = foodPluralName),
    unit = unitName?.let { PantryUnit(id = unitId, name = it) },
    amount = amount,
    expires = expires?.let(::parseDate),
    note = note,
    subLocation = subLocation,
    pending = pending,
)

/** `yyyy-MM-dd` as a date, null when it is not one. */
internal fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(DATE_LENGTH)) }.getOrNull()

private const val DATE_LENGTH = 10
