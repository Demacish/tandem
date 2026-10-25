package casa.moller.tandem.core.sync.permissions

import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Phase 2 (böcker lane): the guest mode's role detection (feature #42, plan 8.6; FACTS "Fas 2 a").

/**
 * [SpacePermissionsRepository] from the group stored in `server_account.group_level` (read by
 * [SpaceRoleSync] with the small tables, so at the first sync after a login and then at most once
 * an hour). Until it is known the role is [SpaceRole.UNKNOWN], which is not read-only. Demo mode
 * is never read-only; without an account there is nothing to write anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultSpacePermissionsRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
) : SpacePermissionsRepository {
    override val permissions: Flow<SpacePermissions> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Remote -> databases.get(source.kind).accountDao().observeGroupLevel(source.accountId)
                    .map { level -> SpacePermissions(role = SpaceRole.of(listOfNotNull(level))) }

                ActiveSource.Demo -> flowOf(SpacePermissions(role = SpaceRole.USER, isDemo = true))

                ActiveSource.Loading, ActiveSource.None -> flowOf(SpacePermissions())
            }
        }
        .catch { emit(SpacePermissions()) }
        .distinctUntilChanged()
}

/**
 * Reads the user's group in the active space from `GET /api/user-space/all_personal/` (the entry
 * of the account's space, else the active one) and stores the highest of `admin`/`user`/`guest`
 * in `server_account.group_level`. A failed read keeps the stored value.
 */
@Singleton
internal class SpaceRoleSync @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
) {
    suspend fun sync(remote: ActiveSource.Remote, background: Boolean) {
        val memberships = caller.call(remote, background) { userSpaces.allPersonal() } as? Outcome.Success ?: return
        if (sources.source.value.identity != remote.identity) return
        val own = memberships.value.firstOrNull { it.space == remote.account.spaceId }
            ?: memberships.value.firstOrNull { it.active }
            ?: return
        val role = SpaceRole.of(own.groups.map { it.name })
        val level = when (role) {
            SpaceRole.ADMIN -> GROUP_ADMIN
            SpaceRole.USER -> GROUP_USER
            SpaceRole.GUEST -> GROUP_GUEST
            SpaceRole.UNKNOWN -> null
        }
        databases.get(remote.kind).accountDao().setGroupLevel(remote.accountId, level)
    }

    private companion object {
        const val GROUP_ADMIN = "admin"
        const val GROUP_USER = "user"
        const val GROUP_GUEST = "guest"
    }
}

/**
 * Guest mode (#42) for repositories that take no [SpacePermissionsRepository]: true when the
 * stored group of [accountId] in [database] is `guest`, so nothing is queued for the server.
 */
internal suspend fun isGuest(database: TandemDatabase, accountId: String): Boolean =
    SpaceRole.of(listOfNotNull(database.accountDao().observeGroupLevel(accountId).first())) == SpaceRole.GUEST
