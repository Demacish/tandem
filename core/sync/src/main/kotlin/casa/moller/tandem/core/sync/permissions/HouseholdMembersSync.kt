package casa.moller.tandem.core.sync.permissions

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncEntities
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.domain.account.HouseholdMembers
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.network.dto.UserDto
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 0.3.0 komplettering ("Delad med: …" on Handla and Veckan, plan 8.6): reads who else is in the
 * user's household with the small tables (after the role, [SpaceRoleSync], which stores the
 * household) and keeps it in `sync_state` ([SyncEntities.HOUSEHOLD_MEMBERS], JSON in `hash`), so
 * it is known offline and after a restart. One request per run (FACTS "Fas 2 h"):
 *
 * - an **admin** reads `GET /api/user-space/` (every membership of the space with its household)
 *   and keeps the members of the own household ([Stored.confirmed]);
 * - **anyone else** sees only their own membership there, so the app reads the space's users
 *   (`GET /api/user/`, [Stored.directory]) to name the creators of stored rows and meals
 *   ([HouseholdMembers]); which of them are in the household follows from those rows;
 * - **no household**: nothing is shared, nothing is read, and the stored members are cleared.
 *
 * A failed read keeps what was stored. [DefaultSpacePermissionsRepository] turns it into
 * `SpacePermissions.sharedWith`.
 */
@Singleton
internal class HouseholdMembersSync @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
    private val clock: SyncClock,
) {
    /** What is stored: [confirmed] household members (admin) and the space's users ([directory]). */
    data class Stored(
        val confirmed: List<HouseholdMembers.Member> = emptyList(),
        val directory: List<HouseholdMembers.Member> = emptyList(),
    )

    suspend fun sync(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> {
        val database = databases.get(remote.kind)
        val account = database.accountDao().get(remote.accountId) ?: return Outcome.Success(Unit)
        val household = account.householdId
        val read: Outcome<Stored> = when {
            household == null -> Outcome.Success(Stored())

            SpaceRole.of(listOfNotNull(account.groupLevel)) == SpaceRole.ADMIN -> {
                val result = caller.call(remote, background) {
                    val api = this
                    Paginator.fetchAll(maxPages = MAX_PAGES) { page -> api.userSpaces.list(page.takeIf { it > 1 }) }
                }
                when (result) {
                    is Outcome.Failure -> result

                    is Outcome.Success -> Outcome.Success(
                        Stored(confirmed = result.value.items.filter { it.household?.id == household }.mapNotNull { it.user?.toMember() }),
                    )
                }
            }

            else -> when (val result = caller.call(remote, background) { books.spaceUsers() }) {
                is Outcome.Failure -> result
                is Outcome.Success -> Outcome.Success(Stored(directory = result.value.mapNotNull { it.toMember() }))
            }
        }
        if (read is Outcome.Failure) return read
        if (sources.source.value.identity != remote.identity) return Outcome.Success(Unit)
        val stored = (read as Outcome.Success).value
        database.syncStateDao().upsert(
            SyncStateEntity(
                accountId = remote.accountId,
                entity = SyncEntities.HOUSEHOLD_MEMBERS,
                hash = encode(stored),
                lastFullRelistEpochMillis = clock.nowMillis(),
            ),
        )
        return Outcome.Success(Unit)
    }

    private fun UserDto.toMember(): HouseholdMembers.Member? = id?.let { HouseholdMembers.Member(it, displayName.ifBlank { username }) }

    companion object {
        private const val MAX_PAGES = 5
        private const val CONFIRMED = "confirmed"
        private const val DIRECTORY = "directory"
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(stored: Stored): String = JsonObject(
            mapOf(CONFIRMED to members(stored.confirmed), DIRECTORY to members(stored.directory)),
        ).toString()

        /** The stored JSON; [Stored] without members when it is missing or cannot be read. */
        fun decode(text: String?): Stored {
            if (text.isNullOrBlank()) return Stored()
            return runCatching {
                val root = json.parseToJsonElement(text).jsonObject
                Stored(confirmed = membersOf(root[CONFIRMED]), directory = membersOf(root[DIRECTORY]))
            }.getOrDefault(Stored())
        }

        private fun members(list: List<HouseholdMembers.Member>): JsonArray = JsonArray(
            list.map { member -> JsonObject(mapOf("id" to JsonPrimitive(member.id), "name" to JsonPrimitive(member.name))) },
        )

        private fun membersOf(element: JsonElement?): List<HouseholdMembers.Member> = element?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            HouseholdMembers.Member(id, obj["name"]?.jsonPrimitive?.takeIf { it.isString }?.content)
        }
    }
}
