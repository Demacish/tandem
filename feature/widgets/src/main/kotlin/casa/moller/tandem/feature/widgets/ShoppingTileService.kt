package casa.moller.tandem.feature.widgets

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fas 3 (widgets lane): the quick settings tile "Handla": its subtitle says how many entries are
 * open, a tap opens the shopping list (the app, Handla tab). Nothing is changed from the tile.
 */
class ShoppingTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { this.scope = it }
        scope.launch {
            val state = runCatching { WidgetData(this@ShoppingTileService).shopping.first() }.getOrDefault(ShoppingWidgetState())
            qsTile?.apply {
                label = getString(R.string.widget_shopping_title)
                subtitle = when {
                    !state.signedIn -> getString(R.string.widget_sign_in)
                    state.remaining == 0 -> getString(R.string.widget_shopping_empty_short)
                    else -> resources.getQuantityString(R.plurals.widget_shopping_remaining, state.remaining, state.remaining)
                }
                this.state = Tile.STATE_ACTIVE
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val pending = AppOpenIntents.openPendingIntent(this, AppOpenIntents.SHOPPING) ?: return
        startActivityAndCollapse(pending)
    }
}
