package casa.moller.tandem.core.database.query

import androidx.room3.RoomRawQuery
import casa.moller.tandem.core.database.fts.FtsQuery
import casa.moller.tandem.core.database.fts.SearchText
import casa.moller.tandem.core.model.recipe.RecipeQuery

/**
 * Builds the SQL for the offline recipe list (plan 6.2 "Receptlista"): every filter of a
 * [RecipeQuery] as a bound parameter. Sorting is done in Kotlin ([RecipeSorting]) so names are
 * ordered with a locale collator (SQLite only knows binary order).
 *
 * Filter semantics (as in the fake repository in core:testing, except that the fake matches
 * substrings in every field):
 * - text: every word must match, either as a word prefix anywhere in the FTS index (name,
 *   description, keywords, ingredients; [FtsQuery]) or as a substring of the name, so "pasta"
 *   also finds "Svamppasta" (Swedish compound words). Text without a searchable word filters nothing.
 * - keywordIds: the recipe has ALL of them.
 * - foodIds: the recipe has an ingredient of EACH of them (Tandoor `foods_and`). Only recipes with
 *   a stored detail have ingredient rows; the server search finds the others when online.
 * - maxTotalMinutes: working + waiting time is known (> 0) and at most the limit.
 * - minRating: rating (unrated = 0) is at least the limit.
 * - onlyOffline: the detail is stored.
 */
internal object RecipeListQuery {
    fun build(accountId: String, query: RecipeQuery): RoomRawQuery {
        val sql = StringBuilder(RecipeSql.SELECT_ITEMS).append("WHERE o.account_id = ?")
        val args = mutableListOf<Any>(accountId)
        FtsQuery.terms(query.text).forEach { term ->
            sql.append(" AND (o.local_id IN (SELECT rowid FROM recipe_fts WHERE recipe_fts MATCH ?) OR o.search_name LIKE ?)")
            args += FtsQuery.prefixTerm(term)
            args += "%" + SearchText.normalize(term) + "%"
        }
        query.keywordIds.sorted().forEach { keywordId ->
            sql.append(
                " AND EXISTS (SELECT 1 FROM recipe_keyword fk WHERE fk.account_id = o.account_id " +
                    "AND fk.recipe_id = o.server_id AND fk.keyword_id = ?)",
            )
            args += keywordId
        }
        query.foodIds.sorted().forEach { foodId ->
            // The ingredient primary key starts with (account_id, recipe_id): an index lookup per recipe.
            sql.append(
                " AND EXISTS (SELECT 1 FROM ingredient fi WHERE fi.account_id = o.account_id " +
                    "AND fi.recipe_id = o.server_id AND fi.food_id = ?)",
            )
            args += foodId
        }
        query.maxTotalMinutes?.let { max ->
            sql.append(" AND (o.working_minutes + o.waiting_minutes) BETWEEN 1 AND ?")
            args += max.toLong()
        }
        query.minRating?.let { min ->
            sql.append(" AND IFNULL(o.rating, 0) >= ?")
            args += min
        }
        if (query.onlyOffline) sql.append(" AND d.raw_json IS NOT NULL")
        return RoomRawQuery(sql.toString()) { statement ->
            args.forEachIndexed { index, arg ->
                when (arg) {
                    is String -> statement.bindText(index + 1, arg)
                    is Long -> statement.bindLong(index + 1, arg)
                    is Double -> statement.bindDouble(index + 1, arg)
                    else -> error("Unsupported argument type ${arg::class}")
                }
            }
        }
    }
}
