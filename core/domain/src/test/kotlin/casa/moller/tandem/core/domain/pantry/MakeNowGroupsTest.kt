package casa.moller.tandem.core.domain.pantry

import casa.moller.tandem.core.model.makenow.MakeNowGroup
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The three groups of "Kan laga nu" from the server's `makenow=0/1/2` answers, against what the
 * contract recorded for 2.6.13 and 2.7.1 (`fas-s/observed-facts.json`, FACTS "Fas S a").
 */
class MakeNowGroupsTest {
    private val fixtures = File(System.getProperty("tandem.fixturesDir") ?: "../../api-schema/fixtures")
    private val json = Json { ignoreUnknownKeys = true }

    private fun recorded(version: String): Map<Int, List<String>> {
        val facts = json.parseToJsonElement(File(fixtures, "$version/fas-s/observed-facts.json").readText()).jsonObject
        // The facts are flat, with dotted keys ("makenow.0").
        return (0..2).associateWith { n -> facts.getValue("makenow.$n").jsonArray.map { it.jsonPrimitive.content } }
    }

    @Test
    fun theGroupsAreTheSetDifferencesOfTheRecordedAnswers() {
        for (version in listOf("2.6.13", "2.7.1")) {
            val answers = recorded(version)
            val names = answers.values.flatten().distinct()
            val ids = names.withIndex().associate { (index, name) -> name to index.toLong() + 1 }
            val groups = MakeNowGroups.fromServer(answers.mapValues { (_, list) -> list.map { ids.getValue(it) } })
            val byName = { group: MakeNowGroup -> MakeNowGroups.idsOf(groups, group).map { id -> names[(id - 1).toInt()] }.sorted() }

            assertEquals(listOf("Gröt med blåbär", "Tahinidressing"), byName(MakeNowGroup.HAS_ALL), version)
            assertEquals(listOf("Hummus"), byName(MakeNowGroup.MISSING_1), version)
            assertEquals(listOf("Falafel"), byName(MakeNowGroup.MISSING_2), version)
            // Every recipe of every answer is in exactly one group, the smallest N that listed it.
            for ((n, list) in answers) {
                for (name in list) assertEquals(true, groups.getValue(ids.getValue(name)) <= n, "$version $name in makenow=$n")
            }
        }
    }

    @Test
    fun aMissingAnswerCountsAsEmptyAndTheServersOrderIsKept() {
        val groups = MakeNowGroups.fromServer(mapOf(1 to listOf(5L, 3L), 2 to listOf(9L, 5L, 3L)))
        assertEquals(emptyList<Long>(), MakeNowGroups.idsOf(groups, MakeNowGroup.HAS_ALL))
        assertEquals(listOf(5L, 3L), MakeNowGroups.idsOf(groups, MakeNowGroup.MISSING_1))
        assertEquals(listOf(9L), MakeNowGroups.idsOf(groups, MakeNowGroup.MISSING_2))
    }
}
