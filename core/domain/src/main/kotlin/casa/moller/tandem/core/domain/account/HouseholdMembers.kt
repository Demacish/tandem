package casa.moller.tandem.core.domain.account

/**
 * 0.3.0 komplettering (plan 8.6 "Delad med: …"): who else in the household sees the shopping list
 * and the meal plan. Tandoor 2.6+ shares both by household (`get_household_user_ids`: own rows
 * plus rows created by the household's members), but no endpoint lists a household's members
 * for an ordinary member (`/api/household/` is admin-only and has no members, `/api/user-space/`
 * lists everyone only for an admin, FACTS "Fas 2"). So the app combines what it can know:
 *
 * - **[confirmed]**: for an admin, the members of the user's household from `/api/user-space/`
 *   (`household.id` equal to the user's);
 * - **[creators]**: whoever created a row on the stored list or a stored meal; the server shows
 *   another user's rows only when that user is in the same household, so each of them is a
 *   member too;
 * - **[directory]**: the space's users (`/api/user/`), only for the names of [creators] that come
 *   without one (a meal carries `created_by` as an id).
 *
 * Without a household ([inHousehold] false) nothing is shared, so the result is empty. The user
 * ([selfId]) is never listed; users without a known name are left out. Pure Kotlin (KMP-friendly),
 * tested in `HouseholdMembersTest`.
 */
object HouseholdMembers {
    /** A user as the rule sees one: the Tandoor user id and the display name, if known. */
    data class Member(val id: Long, val name: String?)

    /** The other members' display names, sorted case-insensitively and without duplicates. */
    fun sharedWith(
        selfId: Long?,
        inHousehold: Boolean,
        confirmed: List<Member>,
        creators: List<Member>,
        directory: List<Member> = emptyList(),
    ): List<String> {
        if (!inHousehold) return emptyList()
        val names = mutableMapOf<Long, String>()
        // Later sources only fill in names the earlier ones do not have.
        for (source in listOf(confirmed, creators, directory)) {
            for (member in source) {
                val name = member.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                names.putIfAbsent(member.id, name)
            }
        }
        val ids = (confirmed.map { it.id } + creators.map { it.id }).filter { it != selfId }.distinct()
        return ids.mapNotNull { names[it] }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}
