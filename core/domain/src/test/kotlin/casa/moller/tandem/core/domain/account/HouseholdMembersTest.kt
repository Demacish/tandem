package casa.moller.tandem.core.domain.account

import casa.moller.tandem.core.domain.account.HouseholdMembers.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** "Delad med: …" (plan 8.6): who else in the household sees the list and the meal plan. */
class HouseholdMembersTest {
    @Test
    fun anAdminSeesTheHouseholdsMembersWithoutHimself() {
        val shared = HouseholdMembers.sharedWith(
            selfId = 1,
            inHousehold = true,
            confirmed = listOf(Member(1, "Erik"), Member(2, "Anna"), Member(4, "barn")),
            creators = emptyList(),
        )
        assertEquals(listOf("Anna", "barn"), shared)
    }

    @Test
    fun aMemberSeesWhoeverCreatedRowsOrMealsNamedFromTheSpacesUsers() {
        val shared = HouseholdMembers.sharedWith(
            selfId = 2,
            inHousehold = true,
            confirmed = emptyList(),
            // A list row brings its name, a meal only its id; the user himself is not listed.
            creators = listOf(Member(1, "Erik"), Member(2, "Anna"), Member(5, null)),
            // The space's users: 5 is named here, 7 (in another household) created nothing and is not listed.
            directory = listOf(Member(5, "Lisa"), Member(7, "Granne")),
        )
        assertEquals(listOf("Erik", "Lisa"), shared)
    }

    @Test
    fun withoutAHouseholdNothingIsShared() {
        val shared = HouseholdMembers.sharedWith(
            selfId = 1,
            inHousehold = false,
            confirmed = listOf(Member(2, "Anna")),
            creators = listOf(Member(3, "Erik")),
        )
        assertEquals(emptyList<String>(), shared)
    }

    @Test
    fun namelessUsersAndDuplicatesAreLeftOut() {
        val shared = HouseholdMembers.sharedWith(
            selfId = null,
            inHousehold = true,
            confirmed = listOf(Member(2, " Anna ")),
            creators = listOf(Member(2, "anna"), Member(3, " "), Member(4, "Bo")),
        )
        assertEquals(listOf("Anna", "Bo"), shared)
    }
}
