package casa.moller.tandem.core.domain.timer

import java.util.Locale

internal enum class TokenKind { NUMBER, WORD, SYMBOL }

internal enum class NumberForm { NONE, INTEGER, DECIMAL, SLASH_FRACTION, GLYPH_FRACTION }

/** A word, number or single symbol with its character span in the source text. */
internal data class Token(
    val kind: TokenKind,
    /** Lowercased word, normalized symbol ("-" for every dash) or the number as written. */
    val text: String,
    val start: Int,
    val end: Int,
    val value: Double = 0.0,
    val form: NumberForm = NumberForm.NONE,
) {
    val isProperFraction: Boolean
        get() = (form == NumberForm.SLASH_FRACTION || form == NumberForm.GLYPH_FRACTION) && value < 1.0
}

/**
 * Splits text into words, numbers and symbols. Whitespace separates tokens and is dropped.
 * Numbers: `10`, `1,5` / `1.5` (both separators, whatever the language), `1/2`, and the
 * vulgar fraction glyphs. A number glued to a fraction glyph ("1½") stays two tokens.
 */
internal object TimerTokenizer {
    private val GLYPHS = mapOf(
        '½' to 1.0 / 2, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 1.0 / 4, '¾' to 3.0 / 4,
        '⅕' to 1.0 / 5, '⅖' to 2.0 / 5, '⅗' to 3.0 / 5, '⅘' to 4.0 / 5, '⅙' to 1.0 / 6, '⅚' to 5.0 / 6,
        '⅛' to 1.0 / 8, '⅜' to 3.0 / 8, '⅝' to 5.0 / 8, '⅞' to 7.0 / 8,
    )
    private val DASHES = setOf('-', '‐', '‑', '‒', '–', '—', '―', '−')
    private const val FRACTION_SLASH = '⁄'

    fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i = when {
                c.isWhitespace() -> i + 1

                c.isAsciiDigit() -> readNumber(text, i, tokens)

                c in GLYPHS -> {
                    tokens += Token(TokenKind.NUMBER, c.toString(), i, i + 1, GLYPHS.getValue(c), NumberForm.GLYPH_FRACTION)
                    i + 1
                }

                c.isLetter() -> {
                    var j = i
                    while (j < text.length && text[j].isLetter()) j++
                    tokens += Token(TokenKind.WORD, text.substring(i, j).lowercase(Locale.ROOT), i, j)
                    j
                }

                else -> {
                    val symbol = if (c in DASHES) "-" else c.toString()
                    tokens += Token(TokenKind.SYMBOL, symbol, i, i + 1)
                    i + 1
                }
            }
        }
        return tokens
    }

    private fun readNumber(text: String, start: Int, out: MutableList<Token>): Int {
        val intEnd = digitsEnd(text, start)
        val digits = text.substring(start, intEnd)
        val next = text.getOrNull(intEnd)
        val afterNext = text.getOrNull(intEnd + 1)
        if ((next == '.' || next == ',') && afterNext != null && afterNext.isAsciiDigit()) {
            val end = digitsEnd(text, intEnd + 1)
            val value = "$digits.${text.substring(intEnd + 1, end)}".toDouble()
            out += Token(TokenKind.NUMBER, text.substring(start, end), start, end, value, NumberForm.DECIMAL)
            return end
        }
        if ((next == '/' || next == FRACTION_SLASH) && afterNext != null && afterNext.isAsciiDigit()) {
            val end = digitsEnd(text, intEnd + 1)
            val denominator = text.substring(intEnd + 1, end).toDouble()
            if (denominator > 0.0) {
                val value = digits.toDouble() / denominator
                out += Token(TokenKind.NUMBER, text.substring(start, end), start, end, value, NumberForm.SLASH_FRACTION)
                return end
            }
        }
        out += Token(TokenKind.NUMBER, digits, start, intEnd, digits.toDouble(), NumberForm.INTEGER)
        return intEnd
    }

    private fun digitsEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length && text[j].isAsciiDigit()) j++
        return j
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
