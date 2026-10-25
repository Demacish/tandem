package casa.moller.tandem.core.data.permissions

import casa.moller.tandem.core.model.account.SpacePermissions
import kotlinx.coroutines.flow.Flow

/**
 * What the active user may change (feature #42, plan 8.6): the group in the space
 * (`/api/user-space/all_personal/` `groups`), read at login and refreshed with the household.
 * Screens hide write buttons when [SpacePermissions.readOnly]; repositories refuse writes with
 * `DataProblem.READ_ONLY` as well (defence in depth). Demo mode is never read-only.
 */
interface SpacePermissionsRepository {
    val permissions: Flow<SpacePermissions>
}
