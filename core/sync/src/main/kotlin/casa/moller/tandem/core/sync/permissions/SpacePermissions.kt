package casa.moller.tandem.core.sync.permissions

import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.server.ServerStatusRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncEntities
import casa.moller.tandem.core.domain.account.HouseholdMembers
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.book.DemoBooks
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
 *
 * [SpacePermissions.householdMissing] (0.3.0 review, fix-data; plan 6.2, 8.6): the role was read
 * (the same answer carries the household), `household` was null, and the server's resolved
 * capabilities ([ServerStatus.capabilitiesResolved]) show no finished household setup (no
 * [ServerFeature.HOUSEHOLDS]: `space.household_setup_completed` is false, plan 8.6
 * "UserSpace.household == null medan household_setup_completed == false"). Tandoor then shares
 * neither the list nor the meal plan with the other members.
 *
 * [SpacePermissions.sharedWith] (0.3.0 komplettering, plan 8.6 "Delad med: …"): core:domain
 * `HouseholdMembers` over what [HouseholdMembersSync] stored and the creators of the stored list
 * rows and meals. In demo mode: the demo list's other creator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultSpacePermissionsRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val serverStatus: ServerStatusRepository,
) : SpacePermissionsRepository {
    override val permissions: Flow<SpacePermissions> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Remote -> combine(
                    databases.get(source.kind).accountDao().observe(source.accountId),
                    serverStatus.observeStatus().map { it.withoutHouseholds() }.distinctUntilChanged(),
                    observeCreators(source),
                    databases.get(source.kind).syncStateDao().observeOne(source.accountId, SyncEntities.HOUSEHOLD_MEMBERS)
                        .map { HouseholdMembersSync.decode(it?.hash) }
                        .distinctUntilChanged(),
                ) { row, noHouseholds, creators, stored ->
                    val level = row?.groupLevel
                    SpacePermissions(
                        role = SpaceRole.of(listOfNotNull(level)),
                        householdMissing = row != null && level != null && row.householdId == null && noHouseholds,
                        sharedWith = HouseholdMembers.sharedWith(
                            selfId = source.account.userId,
                            inHousehold = row?.householdId != null,
                            confirmed = stored.confirmed,
                            creators = creators,
                            directory = stored.directory,
                        ),
                    )
                }

                // 0.3.0 komplettering: the demo household is whoever else created the demo list's rows and meals.
                ActiveSource.Demo -> observeCreators(ActiveSource.Demo).map { creators ->
                    SpacePermissions(
                        role = SpaceRole.USER,
                        isDemo = true,
                        sharedWith = HouseholdMembers.sharedWith(
                            selfId = DemoBooks.OWN_USER_ID,
                            inHousehold = true,
                            confirmed = emptyList(),
                            creators = creators,
                        ),
                    )
                }

                ActiveSource.Loading, ActiveSource.None -> flowOf(SpacePermissions())
            }
        }
        .catch { emit(SpacePermissions()) }
        .distinctUntilChanged()

    /**
     * 0.3.0 komplettering ("Delad med: …"): the creators of the stored list rows (with their names)
     * and meals (ids only); Tandoor shows other users' rows only within the household.
     */
    private fun observeCreators(source: ActiveSource.Stored): Flow<List<HouseholdMembers.Member>> {
        val database = databases.get(source.kind)
        return combine(
            database.shoppingDao().observeCreators(source.accountId),
            database.mealPlanDao().observeCreatorIds(source.accountId),
        ) { rows, meals ->
            rows.map { HouseholdMembers.Member(it.id, it.name) } + meals.map { HouseholdMembers.Member(it, null) }
        }.distinctUntilChanged()
    }

    /** The server was checked and its space has not finished the household setup. */
    // Fas S (rester lane): `checkedAt` is only set by a version check (periodic sync, resume), so after a
    // login the warning waited up to 15 minutes; the capabilities are resolved by every sync's server check.
    private fun ServerStatus.withoutHouseholds(): Boolean = !isDemo && capabilitiesResolved && ServerFeature.HOUSEHOLDS !in capabilities
}

/**
 * Reads the user's group in the active space from `GET /api/user-space/all_personal/` (the entry
 * of the account's space, else the active one) and stores the highest of `admin`/`user`/`guest`
 * in `server_account.group_level`. A failed read keeps the stored value.
 *
 * Phase 2 (konto lane): the same answer carries the household (`household: {id, name}` or null;
 * `/api/household/` is admin-only, FACTS "Fas 2"), stored with the group in one write for the
 * account card and the account switcher (`AccountDirectory`).
 */
@Singleton
internal class SpaceRoleSync @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
) {
    /** Reads and stores group and household; a failure leaves the stored values (and is returned). */
    suspend fun sync(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> {
        val memberships = when (val result = caller.call(remote, background) { userSpaces.allPersonal() }) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        if (sources.source.value.identity != remote.identity) return Outcome.Success(Unit)
        val own = memberships.firstOrNull { it.space == remote.account.spaceId }
            ?: memberships.firstOrNull { it.active }
            ?: return Outcome.Success(Unit)
        val role = SpaceRole.of(own.groups.map { it.name })
        val level = when (role) {
            SpaceRole.ADMIN -> GROUP_ADMIN
            SpaceRole.USER -> GROUP_USER
            SpaceRole.GUEST -> GROUP_GUEST
            SpaceRole.UNKNOWN -> null
        }
        val household = own.household?.takeIf { it.id != null }
        databases.get(remote.kind).accountDao().setMembership(
            accountId = remote.accountId,
            groupLevel = level,
            householdId = household?.id,
            householdName = household?.name?.takeIf { it.isNotBlank() },
        )
        return Outcome.Success(Unit)
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
