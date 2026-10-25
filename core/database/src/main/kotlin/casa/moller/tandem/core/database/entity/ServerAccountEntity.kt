package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Non-secret mirror of a logged-in account (plan 5.7 `server_account`). The device token is never
 * stored here; it lives only in the encrypted account store (`:core:datastore`).
 *
 * Every other table carries `account_id` = [id] as the first part of its key. There is no SQL
 * foreign key: account data is removed explicitly with `AccountDao.deleteAccountData`, so an
 * `INSERT OR REPLACE` of this row can never cascade-delete a whole account by accident.
 *
 * @property id the local account id (`ServerAccount.id`, a UUID).
 * @property baseUrl normalized server base URL.
 * @property userId Tandoor user id.
 * @property spaceId active space id.
 * @property householdId the user's household (Tandoor 2.6), when known.
 * @property serverVersion Tandoor version at the last check, e.g. `2.6.13`.
 * @property groupLevel `guest`, `user` or `admin`, when known.
 * @property tokenId server id of the device token (not the token itself), when known.
 * @property updatedAtEpochMillis when this row was last written.
 * @property householdName (schema 4) the household's name (`/api/user-space/all_personal/`
 *   `household.name`; `/api/household/` is admin-only, FACTS "Fas 2"), when known.
 * @property shoppingMinAutosyncSeconds (schema 4) the server's `shopping_min_autosync_interval`
 *   (a STRING such as "5" in server-settings, parsed), the floor for list polling (plan 3.5).
 */
@Entity(tableName = "server_account")
data class ServerAccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "space_id") val spaceId: Long,
    @ColumnInfo(name = "household_id") val householdId: Long? = null,
    @ColumnInfo(name = "server_version") val serverVersion: String? = null,
    @ColumnInfo(name = "group_level") val groupLevel: String? = null,
    @ColumnInfo(name = "token_id") val tokenId: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long = 0,
    @ColumnInfo(name = "household_name") val householdName: String? = null,
    @ColumnInfo(name = "shopping_min_autosync_seconds") val shoppingMinAutosyncSeconds: Int? = null,
)
