package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

// Fas S (schema 5, grund lane): the pantry, learned barcodes and receipt names, the Open Food Facts
// cache, the "Kan laga nu" cache, staples and drinks created offline (plan §8.8, docs/BYGGE.md
// "Fas S – kontrakt"). Same keys as Phase 2: server rows have `local_id = server_id`; an object
// created offline gets a negative `local_id` from `IdRewriteDao.nextLocalId` until its CREATE is
// answered (`IdRewriteDao.completeCreate`). These tables are FINAL for the phase.

/**
 * A storage place (Tandoor `InventoryLocation`: Kyl, Frys, Skafferi), per household.
 *
 * @property isFreezer Tandoor's `is_freezer` (the freezer gets longer default expiry choices).
 * @property householdId the household the server stored it in (`household.id`). A nested write needs
 *   the WHOLE location object (FACTS "Fas S b"), so the sender rebuilds it from this row.
 * @property locationOrder the order on this device (the server has none), 0 = by name.
 */
@Entity(
    tableName = "inventory_location",
    primaryKeys = ["account_id", "local_id"],
    indices = [Index(value = ["account_id", "server_id"], unique = true)],
)
data class InventoryLocationEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "is_freezer") val isFreezer: Boolean = false,
    @ColumnInfo(name = "household_id") val householdId: Long? = null,
    @ColumnInfo(name = "household_name") val householdName: String? = null,
    @ColumnInfo(name = "location_order") val locationOrder: Int = 0,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long = 0,
)

/**
 * A stock entry (Tandoor `InventoryEntry`): a food in a location with amount, unit and expiry.
 * Amount 0 means "Slut" (the server keeps the row, lists it only with `empty`, FACTS "Fas S b").
 *
 * @property locationId the location's server id, or its temporary (negative) id while unsent.
 * @property code Tandoor's own label code (hex of the id), NOT a barcode (plan #27).
 * @property expires `yyyy-MM-dd` (a date without time zone), null = no date.
 */
@Entity(
    tableName = "inventory_entry",
    primaryKeys = ["account_id", "local_id"],
    indices = [
        Index(value = ["account_id", "server_id"], unique = true),
        Index(value = ["account_id", "location_id"]),
        Index(value = ["account_id", "food_id"]),
        Index(value = ["account_id", "expires"]),
    ],
)
data class InventoryEntryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "sub_location") val subLocation: String? = null,
    @ColumnInfo(name = "code") val code: String? = null,
    @ColumnInfo(name = "food_id") val foodId: Long? = null,
    @ColumnInfo(name = "food_name") val foodName: String,
    @ColumnInfo(name = "food_plural_name") val foodPluralName: String? = null,
    @ColumnInfo(name = "unit_id") val unitId: Long? = null,
    @ColumnInfo(name = "unit_name") val unitName: String? = null,
    @ColumnInfo(name = "amount") val amount: Double = 0.0,
    @ColumnInfo(name = "expires") val expires: String? = null,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: Long? = null,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long = 0,
)

/**
 * A learned barcode or receipt name (plan §8.8): a Tandoor automation of type `FOOD_ALIAS`, shared
 * by every phone of the space. `param_1` is [alias], `param_2` the food's name, `name` says what
 * kind it is ("Tandem: streckkod" / "Tandem: kvitto").
 *
 * @property aliasKey [alias] lower-cased and trimmed (the server matches `param_1` without case,
 *   FACTS "Fas S d"); the lookup column.
 * @property foodId the food's id when it is known locally (the server stores only the name).
 * @property kind a [FoodAliasKinds] value.
 * @property automationName the automation's `name` as stored on the server.
 */
@Entity(
    tableName = "food_alias",
    primaryKeys = ["account_id", "local_id"],
    indices = [
        Index(value = ["account_id", "server_id"], unique = true),
        Index(value = ["account_id", "alias_key"]),
    ],
)
data class FoodAliasEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "alias") val alias: String,
    @ColumnInfo(name = "alias_key") val aliasKey: String,
    @ColumnInfo(name = "food_name") val foodName: String,
    @ColumnInfo(name = "food_id") val foodId: Long? = null,
    @ColumnInfo(name = "kind") val kind: String = FoodAliasKinds.OTHER,
    @ColumnInfo(name = "automation_name") val automationName: String = "",
    @ColumnInfo(name = "disabled") val disabled: Boolean = false,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long = 0,
)

/** Values of [FoodAliasEntity.kind] (stored as text). */
object FoodAliasKinds {
    /** An EAN/UPC learned by the barcode scan (#27). */
    const val BARCODE: String = "BARCODE"

    /** A store's receipt text ("GRÄDDE 36% 5DL", #44). */
    const val RECEIPT: String = "RECEIPT"

    /** Any other FOOD_ALIAS of the space (made on the web): used for matching, never edited by the app. */
    const val OTHER: String = "OTHER"
}

/**
 * The Open Food Facts answer for one EAN (`/api/v2/product/{ean}`, only the code is sent, plan
 * §8.8), cached so a code is looked up once. Per account like every table (removed with it).
 *
 * @property found false = Open Food Facts does not know the code (cached too, retried after a while).
 * @property quantity the product's `quantity` text ("500 g"), if any.
 */
@Entity(tableName = "barcode_product", primaryKeys = ["account_id", "ean"])
data class BarcodeProductEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "ean") val ean: String,
    @ColumnInfo(name = "found") val found: Boolean,
    @ColumnInfo(name = "product_name") val productName: String? = null,
    @ColumnInfo(name = "brand") val brand: String? = null,
    @ColumnInfo(name = "quantity") val quantity: String? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long,
)

/**
 * "Kan laga nu" (#23): the server's answer to `recipe?makenow=0|1|2` (FACTS "Fas S a": an INTEGER,
 * at most N missing foods), stored as the smallest N that listed the recipe.
 *
 * @property missingCount 0 = Har allt, 1 = Saknar 1, 2 = Saknar 2.
 * @property missingFoods JSON array `[{"id":1,"name":"grädde"}]` of the missing foods, computed by
 *   the app (`MissingIngredients`) once the row is shown; null until then.
 */
@Entity(
    tableName = "make_now",
    primaryKeys = ["account_id", "recipe_id"],
    indices = [Index(value = ["account_id", "missing_count"])],
)
data class MakeNowEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "missing_count") val missingCount: Int,
    @ColumnInfo(name = "missing_foods") val missingFoods: String? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long,
)

/**
 * A staple ("Basvaror": salt, peppar, olja ...) the user chose on this device, so the staples
 * screen can show and re-mark them. The on-hand flag itself lives in Tandoor (`food_onhand`).
 *
 * @property nameKey [name] lower-cased and trimmed (the key: a staple may have no food yet).
 */
@Entity(tableName = "staple", primaryKeys = ["account_id", "name_key"])
data class StapleEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "food_id") val foodId: Long? = null,
    @ColumnInfo(name = "added_at") val addedAtEpochMillis: Long,
)

/**
 * A drink created in the app that is not on the server yet (#43, B20: a drink is a recipe with
 * the keyword `Dryck`). Queued as CREATE of [OutboxEntities.DRINK] with this [localId]; removed
 * by `IdRewriteDao.completeCreate` when the server answered (the recipe then comes with the sync).
 *
 * @property keywords JSON array of keyword names (`["Dryck","Vin","Kraftigt rött"]`).
 * @property imagePath an app-private copy of the chosen photo, uploaded after the CREATE.
 */
@Entity(tableName = "drink_draft", primaryKeys = ["account_id", "local_id"])
data class DrinkDraftEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "servings") val servings: Int = 1,
    @ColumnInfo(name = "keywords") val keywords: String = "[]",
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
)
