package casa.moller.tandem.core.domain.pantry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The curated staples ("Basvaror", plan §8.8) and how a staple finds the server's food. */
class StapleCatalogTest {
    private val swedishSpace = listOf(
        StapleFood(2, "salt"),
        StapleFood(3, "svartpeppar"),
        StapleFood(7, "olivolja"),
        StapleFood(15, "vatten"),
        StapleFood(17, "strösocker"),
        StapleFood(45, "vetemjöl"),
        StapleFood(46, "ägg", pluralName = "äggen"),
    )

    @Test
    fun theListHasTheStaplesOfThePlanInBothLanguages() {
        val names = StapleCatalog.suggestions.map { it.displayName("sv") }
        assertTrue(names.containsAll(listOf("salt", "svartpeppar", "olivolja", "vatten", "strösocker", "vetemjöl")), "$names")
        val english = StapleCatalog.suggestions.map { it.displayName("en") }
        assertTrue(english.containsAll(listOf("salt", "black pepper", "olive oil", "water", "caster sugar", "plain flour")), "$english")
        // Every suggestion has both languages, and no name belongs to two staples.
        StapleCatalog.suggestions.forEach { assertTrue(it.swedish.isNotEmpty() && it.english.isNotEmpty(), it.key) }
        val all = StapleCatalog.suggestions.flatMap { s -> s.names.map { StapleCatalog.normalize(it) to s.key }.distinct() }
        assertEquals(all.map { it.first }.distinct().size, all.size, "a name in two staples")
    }

    @Test
    fun theSameNameWinsWithoutCaseAndAlsoThePlural() {
        assertEquals(2L, StapleCatalog.matchFood(" Salt ", swedishSpace))
        assertEquals(46L, StapleCatalog.matchFood("ÄGGEN", swedishSpace))
    }

    @Test
    fun anotherNameOfTheSameStapleFindsTheSpacesFood() {
        // An English phone on a Swedish space, and a short Swedish name for the full one.
        assertEquals(3L, StapleCatalog.matchFood("Black pepper", swedishSpace))
        assertEquals(3L, StapleCatalog.matchFood("peppar", swedishSpace))
        assertEquals(7L, StapleCatalog.matchFood("olive oil", swedishSpace))
        assertEquals(45L, StapleCatalog.matchFood("plain flour", swedishSpace))
        assertEquals(17L, StapleCatalog.matchFood("sugar", swedishSpace))
    }

    @Test
    fun anUnknownStapleOrOneTheSpaceLacksHasNoFood() {
        assertNull(StapleCatalog.matchFood("saffran", swedishSpace))
        assertNull(StapleCatalog.matchFood("vinegar", swedishSpace))
        assertNull(StapleCatalog.matchFood("  ", swedishSpace))
        assertNull(StapleCatalog.suggestionFor("saffran"))
        assertEquals("vinegar", StapleCatalog.suggestionFor("Ättika")?.key)
    }
}
