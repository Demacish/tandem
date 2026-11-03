package casa.moller.tandem.core.ui.navigation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

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
    const val ACTION: String = "casa.moller.tandem.action.OPEN"
    const val EXTRA_DESTINATION: String = "casa.moller.tandem.extra.DESTINATION"

    /** The Handla tab (the shopping list). */
    const val SHOPPING: String = "shopping"

    /** The Veckan tab (the meal plan). */
    const val WEEK: String = "week"

    /** The Recept tab. */
    const val RECIPES: String = "recipes"

    /** "Importera recept" (Fas 3 import lane). */
    const val IMPORT: String = "import"

    /** The barcode scanner (Fas S). */
    const val SCAN: String = "scan"

    /** A recipe: [RECIPE_PREFIX] followed by its id. */
    const val RECIPE_PREFIX: String = "recipe:"

    private const val SCHEME = "tandem-open"

    fun recipe(id: Long): String = "$RECIPE_PREFIX$id"

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
