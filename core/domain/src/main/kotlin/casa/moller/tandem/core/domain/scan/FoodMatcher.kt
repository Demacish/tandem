package casa.moller.tandem.core.domain.scan

import casa.moller.tandem.core.model.recipe.Food

/** A food the scan text may mean, with how well it fits (0..100). */
data class FoodMatch(val food: Food, val score: Int)

/**
 * Suggests the space's foods for a scanned text (#27 product name, #44 receipt line, plan §8.8:
 * "Produktnamnet ger förslag bland Tandoors livsmedel"), on the device over the stored food list,
 * so it works offline and in demo mode. The text is first turned into search words
 * ([ReceiptNormalizer.searchText]). Swedish compounds count: "vispgrädde" fits the food "grädde"
 * (the last part) better than "grädde" fits "vispgrädde". Plural endings are ignored. Pure.
 */
object FoodMatcher {
    /** At least this score: the food is chosen for the row (the user can change it). */
    const val STRONG: Int = 70

    /** At least this score: the food is offered as a suggestion. */
    const val WEAK: Int = 40

    private const val EXACT = 100
    private const val WORD = 90
    private const val ALL_WORDS = 85
    private const val STEM = 85
    private const val COMPOUND_HEAD = 70
    private const val LONGER_FOOD = 60
    private const val PREFIX = 50
    private const val CONTAINS = WEAK
    private const val MIN_PART = 4
    private const val MIN_STEM = 3

    private val pluralEndings = listOf("arna", "erna", "orna", "ar", "er", "or", "na", "s")

    /** The best foods for [text] (already normalised or printed), best first; at most [limit], each at least [WEAK]. */
    fun rank(text: String, foods: List<Food>, limit: Int = 5, normalizer: ReceiptNormalizer = ReceiptNormalizer.bundled): List<FoodMatch> {
        val query = normalizer.searchText(text).ifBlank { text.trim().lowercase() }
        if (query.isBlank()) return emptyList()
        val words = query.split(' ').filter { it.isNotBlank() }
        return foods.asSequence()
            .map { food -> FoodMatch(food, score(query, words, food)) }
            .filter { it.score >= WEAK }
            .sortedWith(compareByDescending<FoodMatch> { it.score }.thenBy { it.food.name.length }.thenBy { it.food.name.lowercase() })
            .distinctBy { it.food.name.lowercase() }
            .take(limit)
            .toList()
    }

    /** The food to choose for [text], or null when none fits well enough ([STRONG]). */
    fun best(text: String, foods: List<Food>, normalizer: ReceiptNormalizer = ReceiptNormalizer.bundled): Food? =
        rank(text, foods, limit = 1, normalizer = normalizer).firstOrNull()?.takeIf { it.score >= STRONG }?.food

    /**
     * The foods whose name contains what the user typed in the food picker ([query], no
     * normalising): names that start with it first, then the rest, each by name; at most [limit].
     */
    fun search(query: String, foods: List<Food>, limit: Int = 20): List<Food> {
        val text = query.trim().lowercase()
        if (text.isEmpty()) return emptyList()
        val hits = foods.filter { food -> listOfNotNull(food.name, food.pluralName).any { text in it.lowercase() } }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
        val (starts, rest) = hits.partition { food -> listOfNotNull(food.name, food.pluralName).any { it.lowercase().startsWith(text) } }
        return (starts + rest).take(limit)
    }

    private fun score(query: String, words: List<String>, food: Food): Int =
        listOfNotNull(food.name, food.pluralName).maxOf { name -> scoreName(query, words, name.trim().lowercase()) }

    private fun scoreName(query: String, words: List<String>, name: String): Int {
        if (name.isEmpty()) return 0
        return wholeScore(query, words, name) ?: partScore(words, name)
    }

    /** The food's whole name is the text, one of its words, all of them, or the same word in another number. */
    private fun wholeScore(query: String, words: List<String>, name: String): Int? {
        val nameWords = name.split(' ').filter { it.isNotBlank() }
        return when {
            name == query || name.replace(" ", "") == words.joinToString("") -> EXACT
            name in words -> WORD
            nameWords.size > 1 && words.containsAll(nameWords) -> ALL_WORDS
            stem(name) in words.map(::stem) -> STEM
            else -> null
        }
    }

    /** Part of a word: the last part of a compound, the start of a word, or anywhere inside. */
    private fun partScore(words: List<String>, name: String): Int {
        val long = words.filter { it.length >= MIN_PART }
        return when {
            name.length >= MIN_PART && words.any { it.length > name.length && it.endsWith(name) } -> COMPOUND_HEAD
            long.any { name.length > it.length && name.endsWith(it) } -> LONGER_FOOD
            long.any { name.startsWith(it) } || (name.length >= MIN_PART && long.any { it.startsWith(name) }) -> PREFIX
            long.any { it in name } -> CONTAINS
            else -> 0
        }
    }

    private fun stem(word: String): String =
        pluralEndings.firstOrNull { word.endsWith(it) && word.length - it.length >= MIN_STEM }?.let { word.dropLast(it.length) } ?: word
}
