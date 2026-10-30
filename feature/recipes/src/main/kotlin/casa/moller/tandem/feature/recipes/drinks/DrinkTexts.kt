package casa.moller.tandem.feature.recipes.drinks

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.drink.DrinkProblem
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.feature.recipes.R
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

// Fas S (drycker lane): texts shared by the drink screens (#43, plan §8.8).

/**
 * The type keyword [name] in the UI's language: the server keeps the stable Swedish names of
 * [DrinkKeywords.TYPES] ("Vin"), any other type is shown as it is.
 */
@Composable
fun drinkTypeLabel(name: String): String = drinkTypeRes(name)?.let { stringResource(it) } ?: name

@StringRes
internal fun drinkTypeRes(name: String): Int? = when (DrinkKeywords.key(name)) {
    "vin" -> R.string.drinks_type_wine
    "öl" -> R.string.drinks_type_beer
    "cider" -> R.string.drinks_type_cider
    "alkoholfritt" -> R.string.drinks_type_alcohol_free
    "drink" -> R.string.drinks_type_cocktail
    else -> null
}

/**
 * "Sök på Systembolaget" (plan §8.8: only a web link, Systembolaget has no open API): the site's
 * search with the drink's [name] and nothing else.
 */
fun systembolagetUrl(name: String): String = SYSTEMBOLAGET_SEARCH + URLEncoder.encode(name.trim(), Charsets.UTF_8.name())

private const val SYSTEMBOLAGET_SEARCH = "https://www.systembolaget.se/sok/?textQuery="

/** The message for a failed drink write (a guest, a taken name, anything else). */
@StringRes
internal fun drinkErrorMessage(error: TandemError): Int = when {
    error.dataProblem() == DataProblem.READ_ONLY -> R.string.drinks_read_only
    DrinkProblem.of(error) == DrinkProblem.NAME_TAKEN -> R.string.drinks_name_taken
    DrinkProblem.of(error) == DrinkProblem.NAME_EMPTY -> R.string.drinks_create_name_missing
    DrinkProblem.of(error) == DrinkProblem.PHOTO_UNREADABLE -> R.string.drinks_create_photo_unreadable
    else -> R.string.drinks_change_failed
}

/** The words of a search, lower case without diacritics ("äppel" finds "Äppelmust"). */
internal fun searchWords(query: String): List<String> = searchKey(query).split(' ').filter { it.isNotBlank() }

/** Whether every word is in one of [texts] (substrings, also inside compound words). */
internal fun matches(words: List<String>, vararg texts: String): Boolean {
    if (words.isEmpty()) return true
    val text = searchKey(texts.joinToString(" "))
    return words.all { it in text }
}

private val combiningMarks = Regex("\\p{Mn}+")

private fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)
