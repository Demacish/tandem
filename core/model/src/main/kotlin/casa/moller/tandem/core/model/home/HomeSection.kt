package casa.moller.tandem.core.model.home

import casa.moller.tandem.core.model.recipe.RecipeOverview

/**
 * The recipe carousels of the Today tab (plan 3.3 "Hemsektioner", 6.2 "Idag"). Each is filled from
 * a server query when online and from the last stored result offline.
 */
enum class HomeSectionType {
    /** Recipes the user pinned for offline use. */
    PINNED,

    /** `sort_order=-times_cooked`. */
    OFTEN_COOKED,

    /** `new=true` (count set by the space's `num_recent`). */
    NEW,

    /** `viewedon_gte=<today - 14 d>` + `sort_order=-lastviewed`. */
    RECENTLY_VIEWED,

    /** `sort_order=lastcooked` + `cookedon_lte=<today - 60 d>`. */
    NOT_COOKED_LATELY,
}

/**
 * One carousel on Today. Sections without recipes are not emitted.
 *
 * @property type which section (the UI picks the localized title).
 * @property recipes the recipes, in the section's order.
 */
data class HomeSection(val type: HomeSectionType, val recipes: List<RecipeOverview>)
