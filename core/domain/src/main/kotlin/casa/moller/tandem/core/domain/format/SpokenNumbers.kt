package casa.moller.tandem.core.domain.format

/**
 * The same text with fractions a screen reader can say (plan 6.6). [FractionStyle.TYPOGRAPHIC]
 * writes fractions without a vulgar-fraction glyph in superscript and subscript digits
 * (`³⁄₁₆`), which TalkBack reads digit by digit as "superscript three, subscript one six" or
 * skips. This maps them back to plain digits with a `/` (`3/16`), the U+2044 fraction slash of
 * [FractionStyle.PLAIN] to `/` too, and no-break spaces to normal spaces. Vulgar glyphs (`½`,
 * `⅛`) stay: text-to-speech reads them as fractions.
 *
 * "1 ³⁄₁₆ tsk" becomes "1 3/16 tsk"; "1 ½ dl" and texts without fractions are returned unchanged.
 */
fun spokenNumbers(text: String): String {
    if (text.none { it in SPOKEN_REPLACEMENTS }) return text
    return buildString(text.length) {
        text.forEach { char -> append(SPOKEN_REPLACEMENTS[char] ?: char) }
    }
}

/**
 * True when [text] holds a fraction a screen reader cannot say as written (superscript or
 * subscript digits, the fraction slash); a no-break space alone does not count.
 */
fun needsSpokenNumbers(text: String): Boolean = text.any { it != NO_BREAK_SPACE && it in SPOKEN_REPLACEMENTS }

private const val NO_BREAK_SPACE = '\u00A0'

private val SPOKEN_REPLACEMENTS: Map<Char, Char> = buildMap {
    "⁰¹²³⁴⁵⁶⁷⁸⁹".forEachIndexed { digit, char -> put(char, '0' + digit) }
    "₀₁₂₃₄₅₆₇₈₉".forEachIndexed { digit, char -> put(char, '0' + digit) }
    put('\u2044', '/')
    put(NO_BREAK_SPACE, ' ')
}
