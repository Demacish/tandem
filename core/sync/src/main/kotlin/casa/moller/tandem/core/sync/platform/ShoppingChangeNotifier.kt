package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.model.shopping.ShoppingChange

// Fas 4 "Notiser när någon i hushållet ändrar listan": the background sync (WorkManager, every
// sync interval) reads the list; what other members changed since last time becomes one
// notification while the app is not on screen. No extra service: the phone asks, nothing pushes.

/** Posts "the shopping list changed" in the channel "shopping". */
internal interface ShoppingChangeNotifier {
    /** Posts one notification for [summary] if allowed; true when it was posted. */
    fun post(summary: ShoppingChangeSummary): Boolean
}

/** Whether the app is on screen (any activity started). */
internal fun interface AppVisibility {
    fun isVisible(): Boolean
}

/**
 * What one notification says: others' changes merged per food, newest last, at most [MAX_LINES]
 * foods per kind ([more] counts the rest).
 *
 * @property added foods added, with who added them when the server says.
 * @property checked foods checked off.
 * @property removed foods removed.
 */
internal data class ShoppingChangeSummary(
    val added: List<ShoppingChange.Added> = emptyList(),
    val checked: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val more: Int = 0,
) {
    val count: Int
        get() = added.size + checked.size + removed.size + more

    val isEmpty: Boolean
        get() = count == 0

    companion object {
        const val MAX_LINES: Int = 5
    }
}

/** The pure decisions behind the shopping notification (unit tested). */
internal object ShoppingNotificationPolicy {

    /**
     * Only when the user switched it on, the app is not on screen (the list shows others' changes
     * itself, `ShoppingRepository.observeChanges`), and notifications are allowed.
     */
    fun shouldPost(
        enabled: Boolean,
        appVisible: Boolean,
        permissionGranted: Boolean = true,
        notificationsEnabled: Boolean = true,
        channelEnabled: Boolean = true,
    ): Boolean = enabled && !appVisible && permissionGranted && notificationsEnabled && channelEnabled

    /**
     * One summary of [changes]: a food named twice counts once (its last change), blank names are
     * dropped, and each kind keeps its first [ShoppingChangeSummary.MAX_LINES] foods.
     */
    fun summarize(changes: List<ShoppingChange>): ShoppingChangeSummary {
        val latest = changes.filter { it.foodName.isNotBlank() }.associateBy { it.foodName.trim().lowercase() }.values
        val added = latest.filterIsInstance<ShoppingChange.Added>()
        val checked = latest.filterIsInstance<ShoppingChange.Checked>().map { it.foodName }
        val removed = latest.filterIsInstance<ShoppingChange.Removed>().map { it.foodName }
        val max = ShoppingChangeSummary.MAX_LINES
        val more = (added.size - max).coerceAtLeast(0) + (checked.size - max).coerceAtLeast(0) + (removed.size - max).coerceAtLeast(0)
        return ShoppingChangeSummary(added.take(max), checked.take(max), removed.take(max), more)
    }
}
