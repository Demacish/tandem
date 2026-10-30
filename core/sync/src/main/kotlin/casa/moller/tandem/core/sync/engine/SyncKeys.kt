package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.entity.SyncEntities

/**
 * The `sync_state` rows (plan 5.7) the engine keeps per account. `last_full_relist` holds when
 * the step last finished; the recipe row's `last_date` is the `updatedon_gte` date (plan 3.5).
 */
internal object SyncKeys {
    /** Keywords, foods, units, meal types, user preferences and cook statistics. */
    const val REFERENCE_DATA: String = "reference_data"

    /** `recipe/flat` and the changed overviews. */
    const val RECIPE_INDEX: String = SyncEntities.RECIPE_INDEX

    /** `last_date`: the day the last complete overview fetch started. */
    const val RECIPES: String = SyncEntities.RECIPE

    /** `hash` of the last stored keyword list (the FTS index is only rebuilt when it changed). */
    const val KEYWORDS: String = SyncEntities.KEYWORD

    /** The meal plan window (`window_from`/`window_to`). */
    const val MEAL_PLAN: String = SyncEntities.MEAL_PLAN

    /** Today's carousels. */
    const val HOME: String = SyncEntities.HOME_SECTIONS

    /** When a whole sync run last finished ("Last synced"). */
    const val LAST_SUCCESS: String = "last_sync"

    /** Fas S (rester lane): when old `id_mapping` rows were last pruned. */
    const val ID_MAPPING_PRUNE: String = "id_mapping_prune"
}
