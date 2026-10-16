package casa.moller.tandem.core.domain.format

import casa.moller.tandem.core.domain.scaling.DisplayAmount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** TalkBack-friendly fractions (plan 6.6): superscript/subscript digits become plain digits. */
class SpokenNumbersTest {

    @Test
    fun typographicFractionsWithoutAGlyphBecomePlainDigits() {
        assertEquals("3/16 tsk", spokenNumbers("³⁄₁₆ tsk"))
        assertEquals("5/12", spokenNumbers("⁵⁄₁₂"))
        assertEquals("3/11 cups", spokenNumbers("³⁄₁₁ cups"))
    }

    @Test
    fun theNoBreakSpaceAfterTheWholeNumberBecomesASpace() {
        val amount = "1${DisplayAmount.NO_BREAK_SPACE}³${DisplayAmount.FRACTION_SLASH}₁₆ tsk"
        assertEquals("1 3/16 tsk", spokenNumbers(amount))
        assertTrue(needsSpokenNumbers(amount))
    }

    @Test
    fun plainFractionsUseAnAsciiSlash() {
        assertEquals("1 1/2", spokenNumbers("1 1⁄2"))
    }

    @Test
    fun vulgarGlyphsAndPlainTextStayAsTheyAre() {
        assertEquals("1 ½ dl", spokenNumbers("1 ½ dl"))
        assertEquals("⅛ tsk", spokenNumbers("⅛ tsk"))
        assertEquals("Koka pastan i 10 minuter.", spokenNumbers("Koka pastan i 10 minuter."))
        assertFalse(needsSpokenNumbers("1 ½ dl"))
    }
}
