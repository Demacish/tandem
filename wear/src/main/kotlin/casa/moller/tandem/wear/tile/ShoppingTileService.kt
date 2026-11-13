package casa.moller.tandem.wear.tile

import android.content.Context
import android.text.format.DateFormat
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import casa.moller.tandem.wear.R
import casa.moller.tandem.wear.TandemWearApplication
import casa.moller.tandem.wear.sync.WearGuard
import casa.moller.tandem.wear.ui.WearMainActivity
import com.google.common.util.concurrent.ListenableFuture
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/**
 * The shopping-list tile (plan §7 #33): how many rows are left, the first ones in walking order,
 * and the phone's next timer; a tap opens the list. It reads the watch's own store, so it works
 * without the phone and offline; when the list is older than [WearGuard.MIN_READ_INTERVAL] it asks
 * for a sync (WorkManager, network constraint), and every change of the list or the timers asks
 * the system to draw the tile again.
 */
class ShoppingTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Default) {
            val graph = (application as TandemWearApplication).graph
            val state = graph.store.load()
            val now = graph.clock()
            val stale = state.lastSyncMillis?.let { now - it > WearGuard.MIN_READ_INTERVAL.inWholeMilliseconds } ?: true
            if (state.account != null && !state.signedOut && stale) graph.scheduler.syncNow()
            val content = ShoppingTile.content(state, now, Locale.getDefault())
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(TileLayout.layout(this@ShoppingTileService, content)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture { ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build() }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MILLIS = 30L * 60L * 1000L
    }
}

/** The tile's layout from [TileContent] (ProtoLayout, no images, so no resources). */
internal object TileLayout {
    private const val PRIMARY = 0xFFFB8A5C.toInt()
    private const val ON_SURFACE = 0xFFF3EBE3.toInt()
    private const val ON_SURFACE_VARIANT = 0xFFBFB3AA.toInt()
    private const val SECONDARY = 0xFFF5B044.toInt()
    private const val PADDING_DP = 16f

    fun layout(context: Context, content: TileContent): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(context.getString(R.string.tile_label), 14f, PRIMARY, bold = true))
            .addContent(spacer(4f))
        when (content.mode) {
            TileContent.Mode.NOT_PAIRED -> column.addContent(text(context.getString(R.string.tile_not_paired), 14f, ON_SURFACE, lines = 3))

            TileContent.Mode.SIGNED_OUT -> column.addContent(text(context.getString(R.string.tile_signed_out), 14f, ON_SURFACE, lines = 3))

            TileContent.Mode.LIST ->
                if (content.toBuy == 0) {
                    column.addContent(text(context.getString(R.string.tile_empty), 16f, ON_SURFACE))
                } else {
                    val count = context.resources.getQuantityString(R.plurals.tile_count, content.toBuy, content.toBuy)
                    column.addContent(text(count, 20f, ON_SURFACE, bold = true))
                    content.firstItems.forEach { column.addContent(text(it, 14f, ON_SURFACE_VARIANT)) }
                }
        }
        content.timer?.let { timer ->
            val line = if (timer.ringing || timer.endsAtMillis == null) {
                context.getString(R.string.tile_timer_ringing, timer.label)
            } else {
                val endsAt = DateFormat.getTimeFormat(context).format(Date(timer.endsAtMillis))
                context.getString(R.string.tile_timer_running, timer.label, endsAt)
            }
            column.addContent(spacer(6f)).addContent(text(line, 13f, SECONDARY, lines = 2))
        }
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(WearMainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open)
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder().setContentDescription(context.getString(R.string.tile_open)).build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(PADDING_DP)).build())
                    .build(),
            )
            .addContent(column.build())
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1): LayoutElementBuilders.Text {
        val style = LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(size))
            .setColor(argb(color))
            .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
            .build()
        return LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setFontStyle(style)
            .setMaxLines(lines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .build()
    }

    private fun spacer(height: Float): LayoutElementBuilders.Spacer = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()
}
