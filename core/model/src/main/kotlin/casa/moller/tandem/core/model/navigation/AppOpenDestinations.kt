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

    /** Handla with the add panel open (the shortcut "Ny vara"). */
    const val ADD_ITEM: String = "add-item"

    /** The Veckan tab (the meal plan). */
    const val WEEK: String = "week"

    /** The Recept tab. */
    const val RECIPES: String = "recipes"

    /** "Importera recept". */
    const val IMPORT: String = "import"

    /** Fas 4, komplettering 3: the notification "klart att granska": the import as it was left, else the kept answer. */
    const val IMPORT_RESULT: String = "import-result"

    /** The barcode scanner. */
    const val SCAN: String = "scan"

    /** Fas 4 (skafferinotis lane): "Kan laga nu" with the recipes that use up soon-expiring foods first. */
    const val USE_UP: String = "use-up"

    /** A recipe: [RECIPE_PREFIX] followed by its id. */
    const val RECIPE_PREFIX: String = "recipe:"

    /** Cook mode for a recipe: [COOK_PREFIX] followed by its id. */
    const val COOK_PREFIX: String = "cook:"

    /** Fas 5 (planera lane): Veckan at a day: [WEEK_DAY_PREFIX] followed by the date (`yyyy-MM-dd`), the evening reminder's tap. */
    const val WEEK_DAY_PREFIX: String = "week:"
}
