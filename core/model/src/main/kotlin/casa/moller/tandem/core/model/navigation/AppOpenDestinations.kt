package casa.moller.tandem.core.model.navigation

/**
 * The "open the app at …" intent that everything outside the shell sends (Fas 3: widgets, tile,
 * shortcuts; Fas 4: the shopping notification). The strings are here so that modules without
 * Compose (core:sync) can build the intent; `AppOpenIntents` in core:ui builds it with them. A
 * destination is only a name: an unknown one just opens the app.
 */
object AppOpenDestinations {
    const val ACTION: String = "casa.moller.tandem.action.OPEN"
    const val EXTRA_DESTINATION: String = "casa.moller.tandem.extra.DESTINATION"

    /** The scheme of the intent's data (`tandem-open://<destination>`), which keeps PendingIntents apart. */
    const val SCHEME: String = "tandem-open"

    /** The Handla tab (the shopping list). */
    const val SHOPPING: String = "shopping"

    /** The Veckan tab (the meal plan). */
    const val WEEK: String = "week"

    /** The Recept tab. */
    const val RECIPES: String = "recipes"

    /** "Importera recept". */
    const val IMPORT: String = "import"

    /** The barcode scanner. */
    const val SCAN: String = "scan"

    /** A recipe: [RECIPE_PREFIX] followed by its id. */
    const val RECIPE_PREFIX: String = "recipe:"
}
