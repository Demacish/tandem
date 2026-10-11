package casa.moller.tandem.core.domain.format

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The [NumberStyle] for this locale: its decimal separator (`,` for Swedish, `.` for English)
 * and [fractionStyle]. The only JVM-specific entry point of the display API: the UI calls it
 * once per configuration (e.g. `LocalConfiguration.current.locales[0].toNumberStyle()`).
 */
fun Locale.toNumberStyle(fractionStyle: FractionStyle = FractionStyle.TYPOGRAPHIC): NumberStyle =
    NumberStyle(decimalSeparator = DecimalFormatSymbols.getInstance(this).decimalSeparator, fractionStyle = fractionStyle)
