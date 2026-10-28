package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 0.3.0 komplettering ("Delad med: …" on the list and the meal plan, plan 8.6): where the app
 * learns who else is in the household (core:domain `HouseholdMembers`, core:sync
 * `HouseholdMembersSync`), against the same temporary Tandoor AFTER Phase2ContractTest (its own
 * step in contract.yml). The workflow put the contract user (admin) and the second member in the
 * household "Contract Household" and added a guest without one.
 *
 * - `GET /api/user-space/` is paginated; an admin gets every membership of the space with `user`
 *   and `household`, a member only their own (so the app reads it only for an admin);
 * - `GET /api/user/` gives a member every user of the space as an array (names, no household).
 *
 * Recorded into `fas2/` (`user-space-list-admin`, `user-space-list-member`, `user-list-member`),
 * committed to api-schema/fixtures/<tag>/fas2/ and decoded by core:network DtoFixturesTest.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_GUEST_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HouseholdMembersContractTest {
    private val env = ContractEnv
    private val today: LocalDate = LocalDate.now(ZoneId.of("Europe/Stockholm"))
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val http = TandoorHttp(
        env.baseUrl,
        sanitizer,
        env.fas2RecordDir,
        numbered = false,
        defaultHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https"),
    )

    @Test
    fun anAdminListsTheHouseholdsMembersAndAMemberOnlyHimself() {
        val owner = userIdOf(env.fas1Token)
        val member = userIdOf(env.readerToken)
        val guest = userIdOf(env.guestToken)

        val all = http.call("user-space-list-admin", "GET", "/api/user-space/?page_size=200", bearer = env.fas1Token)
        assertEquals(200, all.status, "$all")
        val page = all.json as? JsonObject ?: error("not a page: $all")
        assertTrue("count" in page && "next" in page, "paginated: $all")
        val spaces = page.arrAt("results").objects()
        val byUser = spaces.associateBy { it.objAt("user")?.longAt("id") }
        assertTrue(byUser.keys.containsAll(listOf(owner, member, guest)), "every membership of the space: ${byUser.keys}")
        val household = byUser.getValue(owner).objAt("household")?.longAt("id")
        assertNotNull(household, "the owner's household")
        assertEquals(household, byUser.getValue(member).objAt("household")?.longAt("id"), "the member is in the same household")
        assertNull(byUser.getValue(guest).objAt("household"), "the guest is in no household")
        assertTrue(byUser.getValue(member).objAt("user")?.stringAt("display_name").orEmpty().isNotBlank(), "a name to show")

        val own = http.call("user-space-list-member", "GET", "/api/user-space/?page_size=200", bearer = env.readerToken)
        assertEquals(200, own.status, "$own")
        val ownIds = (own.json as? JsonObject)?.arrAt("results").objects().mapNotNull { it.objAt("user")?.longAt("id") }
        assertEquals(listOf(member), ownIds, "a member sees only their own membership")
    }

    @Test
    fun aMemberListsTheSpacesUsersWithTheirNames() {
        val owner = userIdOf(env.fas1Token)
        val member = userIdOf(env.readerToken)
        val users = http.call("user-list-member", "GET", "/api/user/", bearer = env.readerToken)
        assertEquals(200, users.status, "$users")
        val list = users.json as? JsonArray ?: error("not an array: $users")
        val ids = list.objects().mapNotNull { it.longAt("id") }
        assertTrue(ids.containsAll(listOf(owner, member)), "the space's users: $ids")
        assertTrue(list.objects().all { "display_name" in it }, "with display names")
        assertTrue(list.objects().none { "household" in it }, "no household in /api/user/")
    }

    private fun userIdOf(token: String): Long =
        http.call("household-user", "GET", "/api/user-preference/", bearer = token, record = false)
            .json.objects().single().objAt("user")?.longAt("id") ?: error("no user id")
}
