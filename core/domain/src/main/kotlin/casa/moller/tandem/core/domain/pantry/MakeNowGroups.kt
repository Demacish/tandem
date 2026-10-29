package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.makenow.MakeNowGroup

/**
 * "Kan laga nu" (#23) from the server's three answers (plan §8.8, FACTS "Fas S a"):
 * `recipe?makenow=N` lists the recipes with AT MOST N missing foods, so `makenow=0` ⊆ `=1` ⊆ `=2`.
 * A recipe belongs to the smallest N that lists it: Har allt = answer 0, Saknar 1 = answer 1
 * without answer 0, Saknar 2 = answer 2 without answer 1 (set differences). The order of each group
 * is the server's order of its own answer.
 */
object MakeNowGroups {
    /**
     * @param answers the recipe ids of `makenow=0`, `=1` and `=2` in the server's order (a missing
     *   answer counts as empty).
     * @return the missing count (0, 1 or 2) per recipe id, in the order Har allt, Saknar 1, Saknar 2.
     */
    fun fromServer(answers: Map<Int, List<Long>>): LinkedHashMap<Long, Int> {
        val groups = LinkedHashMap<Long, Int>()
        for (group in MakeNowGroup.entries) {
            for (id in answers[group.missing].orEmpty()) {
                if (id !in groups) groups[id] = group.missing
            }
        }
        return groups
    }

    /** The recipe ids of [group] in [groups] (as [fromServer] returns them), in order. */
    fun idsOf(groups: Map<Long, Int>, group: MakeNowGroup): List<Long> = groups.filterValues { it == group.missing }.keys.toList()
}
