package casa.moller.tandem.core.model.account

import casa.moller.tandem.core.model.server.ServerCompatibility

/**
 * The active user's group in the space (Tandoor `UserSpace.groups`: `guest` < `user` < `admin`,
 * `/api/user-space/all_personal/`).
 */
enum class SpaceRole {
    GUEST,
    USER,
    ADMIN,

    /** Not read yet (or an unknown group): treated like [USER] for showing, never for a write the server refused. */
    UNKNOWN,
    ;

    companion object {
        /** The highest known group in [names] (`["user"]`, `["admin"]`), [UNKNOWN] when none. */
        fun of(names: Collection<String>): SpaceRole = when {
            names.any { it.equals("admin", ignoreCase = true) } -> ADMIN
            names.any { it.equals("user", ignoreCase = true) } -> USER
            names.any { it.equals("guest", ignoreCase = true) } -> GUEST
            else -> UNKNOWN
        }
    }
}

/**
 * What the app lets the active user change (plan 8.6, feature #42 "Skrivskyddat läge för gäster").
 *
 * A guest reads recipes, the list and the plan and can cook, but sees no write buttons and the app
 * queues nothing in the outbox for them. The server is not consistently read-only for guests (it
 * accepts a guest's own list entries and meals but refuses bulk checks and recipe edits, FACTS
 * "Fas 2"), so the app decides: read-only means read-only everywhere. Demo mode can write (locally).
 *
 * @property householdMissing (0.3.0 review, fix-data; plan 8.6) the role was read and the user is
 *   in no household: Tandoor then shares neither the shopping list nor the meal plan with the other
 *   members, so the screens warn and say how to join one on the web. Never in demo mode.
 */
data class SpacePermissions(val role: SpaceRole = SpaceRole.UNKNOWN, val isDemo: Boolean = false, val householdMissing: Boolean = false) {
    /** Read-only mode (guest). */
    val readOnly: Boolean
        get() = role == SpaceRole.GUEST && !isDemo

    val canEditShoppingList: Boolean
        get() = !readOnly

    val canEditMealPlan: Boolean
        get() = !readOnly

    /** Own books, favourites and "Vill laga" (a guest's recipe PATCH is refused with 403). */
    val canEditBooks: Boolean
        get() = !readOnly

    /** "Klart!" and cook-log entries. */
    val canLogCooking: Boolean
        get() = !readOnly
}

/**
 * The household of the active user (Tandoor 2.6 `Household`, plan 8.6), from
 * `/api/user-space/all_personal/` (`household: {id, name}`; `/api/household/` itself is admin-only).
 */
data class Household(val id: Long, val name: String)

/**
 * One stored account for the account switcher (plan 6.2 "Konto/server", Stitch `konto_servrar`):
 * one account is active at a time. Demo mode is never an account (it has no entry here).
 *
 * @property household the household last read for the account (`all_personal`), null when unknown or none.
 * @property role the user's group in the account's space, [SpaceRole.UNKNOWN] until read.
 * @property pendingChanges the account's rows in the outbox (also while another account is active).
 * @property compatibility how the app treats the account's last known server version (konto lane).
 */
data class AccountSummary(
    val account: ServerAccount,
    val isActive: Boolean,
    val household: Household? = null,
    val role: SpaceRole = SpaceRole.UNKNOWN,
    val pendingChanges: Int = 0,
    val compatibility: ServerCompatibility = ServerCompatibility.UNRECOGNIZED,
)
