package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Fas S (hemma-data lane): the pantry (`inventory-location`, `inventory-entry`, `inventory-log`)
// and learned barcodes/receipt names (`automation` of type FOOD_ALIAS), plan §8.8, FACTS "Fas S"
// b and d. Tolerant like every DTO (plan 3.6): a default on every field, unknown keys ignored,
// dates as raw strings. Recorded in `fas-s/` for 2.6.13 and 2.7.1 (the same shapes).

/**
 * A storage place (`/api/inventory-location/`). Space-wide on the server: every household of the
 * space sees every location (FACTS "Fas S b"), so the app filters on [household].
 *
 * @property household required when creating (400 without); sent as `{id, name}`. The nested
 *   object is WRITTEN by the server: a different name renames the household.
 */
@Serializable
data class InventoryLocationDto(
    val id: Long? = null,
    val name: String = "",
    @SerialName("is_freezer") val isFreezer: Boolean = false,
    val household: HouseholdDto? = null,
)

/**
 * A stock entry (`/api/inventory-entry/`). Listed without `empty` only with amount > 0, sorted by
 * [expires] (null last).
 *
 * @property inventoryLocation the WHOLE location (a write needs the whole object, FACTS "Fas S b").
 * @property code Tandoor's label code (the id in hex), NOT a barcode.
 * @property amount a decimal; 0 = "Slut".
 * @property expires `yyyy-MM-dd` or null.
 * @property createdBy the creator's user id (a number, not an object).
 */
@Serializable
data class InventoryEntryDto(
    val id: Long? = null,
    @SerialName("inventory_location") val inventoryLocation: InventoryLocationDto? = null,
    @SerialName("sub_location") val subLocation: String? = null,
    val code: String? = null,
    val food: FoodDto? = null,
    val unit: UnitDto? = null,
    val amount: Double = 0.0,
    val expires: String? = null,
    val note: String? = null,
    val label: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by") val createdBy: Long? = null,
)

/**
 * One line of `/api/inventory-log/` (newest first): `add` on create, `remove` on every amount
 * change (also an increase), `move` on a location change (FACTS "Fas S b"). Read only.
 */
@Serializable
data class InventoryLogDto(
    val id: Long? = null,
    val entry: InventoryEntryDto? = null,
    @SerialName("booking_type") val bookingType: String = "",
    @SerialName("old_amount") val oldAmount: Double? = null,
    @SerialName("new_amount") val newAmount: Double? = null,
    @SerialName("old_inventory_location") val oldInventoryLocation: InventoryLocationDto? = null,
    @SerialName("new_inventory_location") val newInventoryLocation: InventoryLocationDto? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * A Tandoor automation (`/api/automation/`); the app reads and writes only `FOOD_ALIAS`:
 * [param1] = the barcode or receipt text (at most 128 characters, matched without case),
 * [param2] = the food's name, [name] = "Tandem: streckkod" / "Tandem: kvitto" (FACTS "Fas S d").
 * Space-wide; no uniqueness on the server (the same [param1] twice is accepted).
 */
@Serializable
data class AutomationDto(
    val id: Long? = null,
    val type: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("param_1") val param1: String? = null,
    @SerialName("param_2") val param2: String? = null,
    @SerialName("param_3") val param3: JsonElement? = null,
    val order: Int = 0,
    val disabled: Boolean = false,
    @SerialName("created_by") val createdBy: Long? = null,
) {
    companion object {
        /** `type` of a learned alias. */
        const val TYPE_FOOD_ALIAS: String = "FOOD_ALIAS"
    }
}
