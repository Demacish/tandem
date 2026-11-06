package casa.moller.tandem.feature.widgets

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.ui.navigation.AppOpenIntents

/**
 * Fas 3 (widgets lane): the launcher shortcuts (long press on the app icon, plan 8.7): Ny vara,
 * Veckan, Importera recept, the latest cooked recipes, Handla and Skanna streckkod, in that order
 * because a launcher shows only the first few. Set as dynamic shortcuts at every start and when the
 * cook log changes (so they follow the language and the flavour's package: a static `shortcuts.xml`
 * needs a fixed target package, and the dev flavour has another). Each opens the app through
 * [AppOpenIntents].
 */
object AppShortcuts {
    /** One shortcut: its id, labels, icon and destination. */
    internal data class Shortcut(val id: String, val shortLabel: Int, val longLabel: Int, val icon: Int, val destination: String)

    /** A place in the launcher's list: a fixed shortcut or a recipe the household cooked lately. */
    internal sealed interface Entry {
        data class Fixed(val shortcut: Shortcut) : Entry

        data class Cooked(val recipe: RecentRecipe) : Entry
    }

    /** Before the recipes. */
    internal val FIRST: List<Shortcut> = listOf(
        Shortcut(
            id = "add-item",
            shortLabel = R.string.shortcut_add_item,
            longLabel = R.string.shortcut_add_item_long,
            icon = DesignR.drawable.tandem_ic_shopping_cart,
            destination = AppOpenIntents.ADD_ITEM,
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
    )

    /** After the recipes. */
    internal val LAST: List<Shortcut> = listOf(
        Shortcut(
            id = "shopping",
            shortLabel = R.string.shortcut_shopping,
            longLabel = R.string.shortcut_shopping_long,
            icon = DesignR.drawable.tandem_ic_shopping_cart,
            destination = AppOpenIntents.SHOPPING,
        ),
        Shortcut(
            id = "scan",
            shortLabel = R.string.shortcut_scan,
            longLabel = R.string.shortcut_scan_long,
            icon = DesignR.drawable.tandem_ic_photo_camera,
            destination = AppOpenIntents.SCAN,
        ),
    )

    /**
     * The fixed shortcuts around [recent], at most [limit] (the launcher's maximum; the last fixed
     * ones go first, the recipes are kept). Pure, so the order and the limit are tested.
     */
    internal fun plan(recent: List<RecentRecipe>, limit: Int): List<Entry> =
        (FIRST.map(Entry::Fixed) + recent.map(Entry::Cooked) + LAST.map(Entry::Fixed)).take(limit.coerceAtLeast(0))

    /**
     * Installs (replaces) the shortcuts; a launcher that refuses (too many, no launcher) is ignored.
     * The application calls it without [recent] at start; [WidgetUpdater] follows with the cook log.
     */
    fun install(context: Context, recent: List<RecentRecipe> = emptyList()) {
        val limit = runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(FIRST.size)
        val shortcuts = plan(recent, limit).mapIndexedNotNull { rank, item ->
            when (item) {
                is Entry.Fixed -> fixed(context, item.shortcut, rank)
                is Entry.Cooked -> recipe(context, item.recipe, rank)
            }
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun fixed(context: Context, shortcut: Shortcut, rank: Int): ShortcutInfoCompat? {
        val intent = AppOpenIntents.open(context, shortcut.destination) ?: return null
        return ShortcutInfoCompat.Builder(context, shortcut.id)
            .setShortLabel(context.getString(shortcut.shortLabel))
            .setLongLabel(context.getString(shortcut.longLabel))
            .setIcon(IconCompat.createWithResource(context, shortcut.icon))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /** A recipe the household cooked lately; its own id, so a pinned copy keeps working. */
    private fun recipe(context: Context, recipe: RecentRecipe, rank: Int): ShortcutInfoCompat? {
        val intent = AppOpenIntents.open(context, AppOpenIntents.recipe(recipe.id)) ?: return null
        return ShortcutInfoCompat.Builder(context, "recipe-${recipe.id}")
            .setShortLabel(recipe.name.take(SHORT_LABEL_MAX))
            .setLongLabel(recipe.name)
            .setIcon(IconCompat.createWithResource(context, DesignR.drawable.tandem_ic_restaurant))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /** What launchers recommend for a short label. */
    private const val SHORT_LABEL_MAX = 25
}
