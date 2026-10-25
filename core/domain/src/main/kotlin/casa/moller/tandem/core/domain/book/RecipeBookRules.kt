package casa.moller.tandem.core.domain.book

import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.book.WantToCook

/**
 * How the app recognises its books among the user's Tandoor books (plan 3.7, B17, feature #17).
 * Tandoor has no "favourites" or "want to cook" flag, so the books are recognised by what the app
 * (or the web, or Kitshn) writes into them:
 *
 * - **Favourites (one book per person, plan 3.7 A):** a plain book named "Favoriter",
 *   "Favorites" or "Favourites", optionally followed by the owner in brackets ("Favoriter (Anna)",
 *   the name the app gives the book it creates), or a book tagged `#KTSHNFAV` in its description
 *   (Kitshn's favourites book, so a household that switches keeps its favourites).
 * - **"Vill laga" (plan 3.7 B):** a smart book (with a saved filter) whose filter searches the
 *   [WantToCook.KEYWORD] keyword, or a smart book named "Vill laga" / "Want to cook".
 * - Any other book with a filter is [RecipeBookKind.SMART], the rest [RecipeBookKind.BOOK].
 *
 * Pure Kotlin (KMP-friendly); unit-tested in `RecipeBookRulesTest`.
 */
object RecipeBookRules {
    /** Kitshn's tag for its favourites book (in the book's description). */
    const val KITSHN_FAVORITES_TAG: String = "#KTSHNFAV"

    private val favoritesName = Regex("""^(favoriter|favorites|favourites)(\s*\(.*\))?$""", RegexOption.IGNORE_CASE)
    private val wantToCookName = Regex("""^(vill laga|want to cook)$""", RegexOption.IGNORE_CASE)

    /**
     * The kind of a book.
     *
     * @param filterSearch the saved filter's `search` JSON, null for a plain book.
     * @param hasFilter the book has a saved filter (even when its search could not be read).
     * @param wantToCookKeywordIds ids of the keyword(s) named [WantToCook.KEYWORD] (case-insensitive).
     */
    fun kind(
        name: String,
        description: String,
        hasFilter: Boolean,
        filterSearch: String?,
        wantToCookKeywordIds: Set<Long>,
    ): RecipeBookKind {
        val trimmed = name.trim()
        return when {
            hasFilter && (wantToCookName.matches(trimmed) || SmartFilter.parse(filterSearch)?.isWantToCook(wantToCookKeywordIds) == true) ->
                RecipeBookKind.WANT_TO_COOK

            hasFilter -> RecipeBookKind.SMART

            favoritesName.matches(trimmed) || description.contains(KITSHN_FAVORITES_TAG, ignoreCase = true) -> RecipeBookKind.FAVORITES

            else -> RecipeBookKind.BOOK
        }
    }

    /** The name of the favourites book the app creates: "<word> (<owner>)", e.g. "Favoriter (Anna)". */
    fun favoritesBookName(word: String, owner: String): String = if (owner.isBlank()) word else "$word (${owner.trim()})"

    /** True for the keyword of "Vill laga" (by name, case-insensitive, as Tandoor reuses keywords by name). */
    fun isWantToCookKeyword(name: String): Boolean = name.trim().equals(WantToCook.KEYWORD, ignoreCase = true)
}

/**
 * The part of a saved filter's `search` JSON that the app can evaluate on the device (a smart
 * book offline): keyword ids with "any of" ([keywordsOr]) and "all of" ([keywordsAnd]). A search
 * with any other non-empty criterion (text, foods, rating ...) is not [localOnly]; the server's
 * `GET /api/recipe/?filter=<id>` is the truth for it, and offline the app shows what it read last.
 */
data class SmartFilter(val keywordsOr: Set<Long>, val keywordsAnd: Set<Long>, val localOnly: Boolean) {
    /** A search that only finds recipes with the want-to-cook keyword. */
    fun isWantToCook(wantToCookKeywordIds: Set<Long>): Boolean = localOnly &&
        keywordsAnd.isEmpty() &&
        keywordsOr.isNotEmpty() &&
        wantToCookKeywordIds.containsAll(keywordsOr)

    /** Whether a recipe with [keywordIds] matches (only meaningful when [localOnly]). */
    fun matches(keywordIds: Set<Long>): Boolean {
        if (keywordsOr.isEmpty() && keywordsAnd.isEmpty()) return false
        val anyOf = keywordsOr.isEmpty() || keywordsOr.any { it in keywordIds }
        return anyOf && keywordIds.containsAll(keywordsAnd)
    }

    companion object {
        private val entry = Regex(""""([a-z_]+)"\s*:\s*(\[[^\]]*]|"[^"]*"|[^,}\s]+)""")
        private val number = Regex("""-?\d+""")

        /** Reads a `search` JSON object; null when [search] is null or not an object. Never throws. */
        fun parse(search: String?): SmartFilter? {
            val text = search?.trim() ?: return null
            if (!text.startsWith("{") || !text.endsWith("}")) return null
            var or = emptySet<Long>()
            var and = emptySet<Long>()
            var local = true
            for (match in entry.findAll(text)) {
                val (key, value) = match.destructured
                when (key) {
                    "keywords_or" -> or = ids(value) ?: return SmartFilter(emptySet(), emptySet(), localOnly = false)
                    "keywords_and" -> and = ids(value) ?: return SmartFilter(emptySet(), emptySet(), localOnly = false)
                    else -> if (!isEmptyValue(value)) local = false
                }
            }
            return SmartFilter(or, and, local)
        }

        /** Ids of a JSON array of numbers; null for an array of anything else (e.g. objects). */
        private fun ids(array: String): Set<Long>? {
            if (!array.startsWith("[")) return null
            val inner = array.removePrefix("[").removeSuffix("]").trim()
            if (inner.isEmpty()) return emptySet()
            val parts = inner.split(',').map { it.trim() }
            if (parts.any { !number.matches(it) }) return null
            return parts.map { it.toLong() }.toSet()
        }

        private fun isEmptyValue(value: String): Boolean = value == "[]" || value == "\"\"" || value == "null" || value == "false"
    }
}
