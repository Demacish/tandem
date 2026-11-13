package casa.moller.tandem.wear.tile

import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.wear.data.WearState
import casa.moller.tandem.wear.shopping.WearShoppingList
import java.util.Locale

/** What the tile says (plan §7 #33 "tile"): pure, so its rules are tested without a tile renderer. */
data class TileContent(
    val mode: Mode,
    val toBuy: Int = 0,
    val firstItems: List<String> = emptyList(),
    val timer: TileTimer? = null,
) {
    enum class Mode {
        NOT_PAIRED,
        SIGNED_OUT,
        LIST,
    }
}

/** The timer the tile shows: a ringing one first, else the one that rings next. */
data class TileTimer(val label: String, val ringing: Boolean, val endsAtMillis: Long?)

object ShoppingTile {
    /** How many rows the tile names. */
    const val ITEMS: Int = 3

    fun content(state: WearState, nowMillis: Long, locale: Locale): TileContent {
        val timer = timer(state.timers)
        return when {
            state.account == null -> TileContent(TileContent.Mode.NOT_PAIRED, timer = timer)
            state.signedOut -> TileContent(TileContent.Mode.SIGNED_OUT, timer = timer)
            else -> {
                val list = WearShoppingList.of(state, nowMillis, locale)
                TileContent(
                    mode = TileContent.Mode.LIST,
                    toBuy = list.toBuy,
                    firstItems = list.firstRows(ITEMS).map { it.food },
                    timer = timer,
                )
            }
        }
    }

    private fun timer(timers: List<MirroredTimer>): TileTimer? {
        val ringing = timers.firstOrNull { it.state == MirroredTimerState.RINGING }
        if (ringing != null) return TileTimer(ringing.label, ringing = true, endsAtMillis = null)
        return timers.filter { it.state == MirroredTimerState.RUNNING && it.endsAtEpochMillis != null }
            .minByOrNull { it.endsAtEpochMillis ?: Long.MAX_VALUE }
            ?.let { TileTimer(it.label, ringing = false, endsAtMillis = it.endsAtEpochMillis) }
    }
}
