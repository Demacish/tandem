package casa.moller.tandem.feature.widgets

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.ui.navigation.AppOpenIntents

/**
 * Fas 3 (widgets lane): the launcher shortcuts (long press on the app icon): Handla, Veckan,
 * Importera recept and Skanna streckkod. Set as dynamic shortcuts at every start (so they follow
 * the language and the flavour's package: a static `shortcuts.xml` needs a fixed target package,
 * and the dev flavour has another). Each opens the app through [AppOpenIntents].
 */
object AppShortcuts {
    /** One shortcut: its id, labels, icon and destination. */
    internal data class Shortcut(val id: String, val shortLabel: Int, val longLabel: Int, val icon: Int, val destination: String)

    internal val ALL: List<Shortcut> = listOf(
        Shortcut(
            id = "shopping",
            shortLabel = R.string.shortcut_shopping,
            longLabel = R.string.shortcut_shopping_long,
            icon = DesignR.drawable.tandem_ic_shopping_cart,
            destination = AppOpenIntents.SHOPPING,
        ),
        Shortcut(
            id = "week",
            shortLabel = R.string.shortcut_week,
            longLabel = R.string.shortcut_week_long,
            icon = DesignR.drawable.tandem_ic_calendar_month,
            destination = AppOpenIntents.WEEK,
        ),
        Shortcut(
            id = "import",
            shortLabel = R.string.shortcut_import,
            longLabel = R.string.shortcut_import_long,
            icon = DesignR.drawable.tandem_ic_link,
            destination = AppOpenIntents.IMPORT,
        ),
        Shortcut(
            id = "scan",
            shortLabel = R.string.shortcut_scan,
            longLabel = R.string.shortcut_scan_long,
            icon = DesignR.drawable.tandem_ic_photo_camera,
            destination = AppOpenIntents.SCAN,
        ),
    )

    /** Installs (replaces) the shortcuts; a launcher that refuses (too many, no launcher) is ignored. */
    fun install(context: Context) {
        val shortcuts = ALL.mapNotNull { shortcut ->
            val intent = AppOpenIntents.open(context, shortcut.destination) ?: return@mapNotNull null
            ShortcutInfoCompat.Builder(context, shortcut.id)
                .setShortLabel(context.getString(shortcut.shortLabel))
                .setLongLabel(context.getString(shortcut.longLabel))
                .setIcon(IconCompat.createWithResource(context, shortcut.icon))
                .setIntent(intent)
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
