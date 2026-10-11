package casa.moller.tandem.core.database.fts

import java.text.Normalizer
import java.util.Locale

/**
 * The folding `unicode61 remove_diacritics=2` applies, in Kotlin: lower case without combining
 * marks, so "Krämig Ärtsoppa" -> "kramig artsoppa". Used for `recipe_overview.search_name` and
 * the substring half of the list search.
 */
object SearchText {
    private val combiningMarks = Regex("\\p{Mn}+")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)
}
