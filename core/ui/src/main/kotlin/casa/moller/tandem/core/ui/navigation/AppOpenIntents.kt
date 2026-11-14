package casa.moller.tandem.core.ui.navigation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import casa.moller.tandem.core.model.navigation.AppOpenDestinations

/**
 * Fas 3 (widgets lane): "open the app at …" for everything outside the shell (the widgets, the
 * quick settings tile, the launcher shortcuts). The intent carries a destination name; the app
 * (`MainActivity` → `OpenLaunch`) turns it into the shell's open request. The names are plain
 * strings so that feature modules never depend on the shell's keys.
 *
 * `MainActivity` is exported, so any app may send [ACTION]: the destination is only a name, an
 * unknown one just opens the app, and a recipe id is checked where it is used.
 */
object AppOpenIntents {
    // The names live in core:model (Fas 4: core:sync builds the same intent for the shopping notification).
    const val ACTION: String = AppOpenDestinations.ACTION
    const val EXTRA_DESTINATION: String = AppOpenDestinations.EXTRA_DESTINATION

    /** The Handla tab (the shopping list). */
    const val SHOPPING: String = AppOpenDestinations.SHOPPING

    /** Handla with the add panel open (the shortcut "Ny vara"). */
    const val ADD_ITEM: String = AppOpenDestinations.ADD_ITEM

    /** The Veckan tab (the meal plan). */
    const val WEEK: String = AppOpenDestinations.WEEK

    /** The Recept tab. */
    const val RECIPES: String = AppOpenDestinations.RECIPES

    /** "Importera recept" (Fas 3 import lane). */
    const val IMPORT: String = AppOpenDestinations.IMPORT

    /** Fas 4, komplettering 3: the AI import's result, from its notification. */
    const val IMPORT_RESULT: String = AppOpenDestinations.IMPORT_RESULT

    /** The barcode scanner (Fas S). */
    const val SCAN: String = AppOpenDestinations.SCAN

    /** Fas 4 (skafferinotis lane): "Kan laga nu" with the recipes that use up soon-expiring foods first. */
    const val USE_UP: String = AppOpenDestinations.USE_UP

    /** A recipe: [RECIPE_PREFIX] followed by its id. */
    const val RECIPE_PREFIX: String = AppOpenDestinations.RECIPE_PREFIX

    /** Cook mode for a recipe: [COOK_PREFIX] followed by its id (the "I dag" widget, plan 8.7). */
    const val COOK_PREFIX: String = AppOpenDestinations.COOK_PREFIX

    /** Fas 5 (planera lane): Veckan at a day: [WEEK_DAY_PREFIX] followed by `yyyy-MM-dd` (the evening reminder). */
    const val WEEK_DAY_PREFIX: String = AppOpenDestinations.WEEK_DAY_PREFIX

    /** Fas 5 (butik lane): store mode for a store: [STORE_MODE_PREFIX] followed by its id (the reminder near the store). */
    const val STORE_MODE_PREFIX: String = AppOpenDestinations.STORE_MODE_PREFIX

    private const val SCHEME = AppOpenDestinations.SCHEME

    fun recipe(id: Long): String = "$RECIPE_PREFIX$id"

    fun cook(id: Long): String = "$COOK_PREFIX$id"

    fun weekDay(date: String): String = "$WEEK_DAY_PREFIX$date"

    fun storeMode(storeId: Long): String = "$STORE_MODE_PREFIX$storeId"

    /** The intent that opens the app at [destination], or null without a launcher activity. */
    fun open(context: Context, destination: String): Intent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return Intent(ACTION).apply {
            component = launch.component
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // The data makes the PendingIntent unique per destination (extras do not count).
            data = "$SCHEME://$destination".toUri()
            putExtra(EXTRA_DESTINATION, destination)
        }
    }

    fun openPendingIntent(context: Context, destination: String): PendingIntent? {
        val intent = open(context, destination) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The destination [intent] names, or null when it is not an [ACTION] intent or has none. */
    fun destinationOf(intent: Intent?): String? {
        if (intent?.action != ACTION) return null
        return runCatching { intent.getStringExtra(EXTRA_DESTINATION) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: intent.data?.takeIf { it.scheme == SCHEME }?.host
    }
}
