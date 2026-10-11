package casa.moller.tandem.core.database.query

/**
 * SQL shared by several DAOs (annotation values must be compile-time constants, so the pieces
 * are `const val`s and combined with `+`).
 */
internal object RecipeSql {
    /** Columns of a `RecipeListItem`; needs `recipe_overview o` and [DETAIL_JOIN]. */
    const val ITEM_COLUMNS: String =
        "o.*, IFNULL(d.pinned, 0) AS pinned, (d.raw_json IS NOT NULL) AS available_offline, " +
            "d.last_viewed_local AS last_viewed_local, " +
            "(SELECT group_concat(k.id || char(31) || k.name, char(30)) FROM recipe_keyword rk " +
            "JOIN keyword k ON k.account_id = rk.account_id AND k.id = rk.keyword_id " +
            "WHERE rk.account_id = o.account_id AND rk.recipe_id = o.server_id) AS keyword_list"

    /** Joins the local per-recipe state to `recipe_overview o`. */
    const val DETAIL_JOIN: String =
        "LEFT JOIN recipe_detail d ON d.account_id = o.account_id AND d.recipe_id = o.server_id"

    /** `SELECT <list item> FROM recipe_overview o LEFT JOIN recipe_detail d ... ` (append `WHERE ...`). */
    const val SELECT_ITEMS: String = "SELECT " + ITEM_COLUMNS + " FROM recipe_overview o " + DETAIL_JOIN + " "

    /** FTS row content for the recipes `o` (append the `WHERE` on `o`). */
    private const val FTS_SELECT: String =
        "SELECT o.local_id, o.name, IFNULL(o.description, ''), " +
            "IFNULL((SELECT group_concat(k.name, ' ') FROM recipe_keyword rk " +
            "JOIN keyword k ON k.account_id = rk.account_id AND k.id = rk.keyword_id " +
            "WHERE rk.account_id = o.account_id AND rk.recipe_id = o.server_id), ''), " +
            "IFNULL((SELECT group_concat(IFNULL(i.food_name, '') || ' ' || IFNULL(i.food_plural_name, ''), ' ') " +
            "FROM ingredient i WHERE i.account_id = o.account_id AND i.recipe_id = o.server_id AND i.is_header = 0), '') " +
            "FROM recipe_overview o "

    private const val FTS_INSERT: String = "INSERT INTO recipe_fts(rowid, name, description, keywords, ingredients) "

    /** Re-creates the FTS rows of `:serverIds` of `:accountId` (run [FTS_DELETE_RECIPES] first). */
    const val FTS_INSERT_RECIPES: String =
        FTS_INSERT + FTS_SELECT + "WHERE o.account_id = :accountId AND o.server_id IN (:serverIds)"

    /** Removes the FTS rows of `:serverIds` of `:accountId` (while their overview rows still exist). */
    const val FTS_DELETE_RECIPES: String =
        "DELETE FROM recipe_fts WHERE rowid IN " +
            "(SELECT local_id FROM recipe_overview WHERE account_id = :accountId AND server_id IN (:serverIds))"

    /** Re-creates every FTS row of `:accountId` (run [FTS_DELETE_ACCOUNT] first). */
    const val FTS_INSERT_ACCOUNT: String = FTS_INSERT + FTS_SELECT + "WHERE o.account_id = :accountId"

    /** Removes every FTS row of `:accountId` (while its overview rows still exist). */
    const val FTS_DELETE_ACCOUNT: String =
        "DELETE FROM recipe_fts WHERE rowid IN (SELECT local_id FROM recipe_overview WHERE account_id = :accountId)"

    /** Max ids per `IN (...)` list, well below SQLite's bound-variable limit. */
    const val CHUNK: Int = 500
}
