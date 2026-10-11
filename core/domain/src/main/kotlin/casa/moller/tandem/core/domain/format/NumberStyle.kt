package casa.moller.tandem.core.domain.format

/**
 * How numbers in recipe texts are written for one display language (plan 6.7 "Format per
 * språk"). Pure Kotlin on purpose (decision B2): the Android UI gets one from its `Locale` with
 * [casa.moller.tandem.core.domain.format.toNumberStyle]; an iOS port would build it from
 * `NSLocale` the same way.
 *
 * Tandoor's web always writes a dot and plain fractions (`1 1⁄2`); [Web] reproduces that exactly
 * and is what the parity tests use. The app shows the user's decimal separator on top
 * (FACTS.md "Fas 1: konsekvenser för appen" 6: Swedish `2,5`).
 *
 * @property decimalSeparator `.` or `,` (anything else is used as given).
 * @property fractionStyle how fractions are written when the user's Tandoor preference
 *   `use_fractions` is on.
 */
data class NumberStyle(val decimalSeparator: Char = '.', val fractionStyle: FractionStyle = FractionStyle.PLAIN) {
    companion object {
        /** Exactly what Tandoor's web shows as visible text: dot, `1 1⁄2`. */
        val Web: NumberStyle = NumberStyle()

        /** English display: dot, typographic fractions (`1 ½`). */
        val English: NumberStyle = NumberStyle(decimalSeparator = '.', fractionStyle = FractionStyle.TYPOGRAPHIC)

        /** Swedish display: decimal comma, typographic fractions (`2,5`, `1 ½`). */
        val Swedish: NumberStyle = NumberStyle(decimalSeparator = ',', fractionStyle = FractionStyle.TYPOGRAPHIC)
    }
}

/** How a fraction such as one and a half is written. */
enum class FractionStyle {
    /**
     * Like the web's visible text: whole number, a space, numerator, U+2044 FRACTION SLASH,
     * denominator (`1 1⁄2`, `3⁄11`). Used for parity tests and plain-text exports.
     */
    PLAIN,

    /**
     * For the screen: a vulgar-fraction glyph when Unicode has one (`½`, `⅔`, `⅜`), otherwise
     * superscript and subscript digits (`³⁄₁₁`); a no-break space joins the whole number
     * (`1 ½`), so the amount never wraps in the middle.
     */
    TYPOGRAPHIC,
}
