package casa.moller.tandem.core.domain.shopping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The offline parser gives the same split as Tandoor's parser for the recorded lines (FACTS "Fas 2 e"). */
class LocalIngredientParserTest {
    private fun parsed(line: String, units: List<String> = emptyList()) =
        LocalIngredientParser.parse(line, units)?.let { Triple(it.amount, it.unitName, it.foodName) }

    @Test
    fun theRecordedLinesSplitLikeTandoor() {
        assertEquals(Triple(2.0, "dl", "grädde"), parsed("2 dl grädde"))
        assertEquals(Triple(0.0, null, "mjölk"), parsed("mjölk"))
        assertEquals(Triple(6.0, null, "ägg"), parsed("6 ägg"))
        assertEquals(Triple(1.5, "tsk", "salt"), parsed("1 1/2 tsk salt"))
        assertEquals(Triple(1.5, "kg", "potatis"), parsed("1,5 kg potatis"))
        assertEquals(Triple(200.0, "g", "ca smör"), parsed("ca 200 g smör, rumsvarmt"))
        assertEquals(Triple(1.0, "paket", "smör"), parsed("1 paket smör"))
        assertEquals(Triple(0.5, null, "citron"), parsed("½ citron"))
    }

    @Test
    fun theNoteIsTheTextAfterTheFirstComma() {
        val line = LocalIngredientParser.parse("ca 200 g smör, rumsvarmt, gärna osaltat")
        assertEquals("rumsvarmt, gärna osaltat", line?.note)
        assertEquals("ca 200 g smör, rumsvarmt, gärna osaltat", line?.originalText)
        assertFalse(line?.fromServer ?: true)
    }

    @Test
    fun otherNumberFormats() {
        assertEquals(Triple(1.5, "dl", "mjölk"), parsed("1.5 dl mjölk"))
        assertEquals(Triple(1.5, "dl", "mjölk"), parsed("1½ dl mjölk"))
        assertEquals(Triple(0.75, "l", "vatten"), parsed("3/4 l vatten"))
        assertEquals(Triple(2.0, null, "lökar"), parsed("2-3 lökar"))
        assertEquals(Triple(2.0, "st", "avokado"), parsed("2 st. avokado"))
        assertEquals(Triple(1.0, "cup", "flour"), parsed("1 cup flour"))
    }

    @Test
    fun theServersUnitsAreKnownToo() {
        assertEquals(Triple(2.0, null, "nävar spenat"), parsed("2 nävar spenat"))
        assertEquals(Triple(2.0, "nävar", "spenat"), parsed("2 nävar spenat", listOf("Nävar")))
    }

    @Test
    fun blankTextAndAnAmountAloneGiveNothing() {
        assertNull(LocalIngredientParser.parse("   "))
        assertNull(LocalIngredientParser.parse("2 dl"))
        assertNull(LocalIngredientParser.parse(", bara en anteckning"))
    }
}
