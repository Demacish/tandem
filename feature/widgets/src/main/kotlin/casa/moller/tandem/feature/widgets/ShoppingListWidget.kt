package casa.moller.tandem.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Fas 3 (widgets lane): the shopping list on the home screen (plan §8.9 "Widgets"): the open
 * entries in list order with "N kvar"; a tap on a line checks it (through the outbox, offline
 * too), the header opens Handla. The first value is read before drawing and the flow is followed
 * INSIDE the composition: `update`/`updateAll` do not run [provideGlance] again while a session
 * lives (about 45 s, longer with each update), so a value read outside would be drawn again and
 * again. [WidgetUpdater] and [CheckEntryAction] still call update: that starts a session when none
 * runs. For guests the rows are read-only.
 */
class ShoppingListWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val shopping = WidgetData(context).shopping
        val first = shopping.first()
        provideContent { GlanceTheme { ShoppingWidgetLive(shopping, first) } }
    }
}

/** [ShoppingWidgetContent] following [states], from [first]: every value reaches a running session. */
@Composable
internal fun ShoppingWidgetLive(states: Flow<ShoppingWidgetState>, first: ShoppingWidgetState) {
    val state by states.collectAsState(first)
    ShoppingWidgetContent(state)
}

class ShoppingListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingListWidget()
}

/** A tapped line: the entry is checked (the list's "Köpt"), and the widget redrawn. */
class CheckEntryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[KEY_ENTRY_ID] ?: return
        WidgetActions.check(WidgetData(context).entryPoint.shopping(), id)
        ShoppingListWidget().update(context, glanceId)
    }

    companion object {
        val KEY_ENTRY_ID: ActionParameters.Key<Long> = ActionParameters.Key("entryId")
    }
}

/**
 * What a tap in the shopping widget does (plan §10 Fas 3 "Klart när": the widget checks an item
 * off offline and it is synced later): the same `setChecked` as Handla, through the outbox, so
 * it waits for the network like any other check (core:sync `ShoppingSyncTest`).
 */
internal object WidgetActions {
    suspend fun check(shopping: ShoppingRepository, entryId: Long): Outcome<Unit> = shopping.setChecked(listOf(entryId), checked = true)
}

@Composable
internal fun ShoppingWidgetContent(state: ShoppingWidgetState) {
    val context = LocalContext.current
    val openList = AppOpenIntents.open(context, AppOpenIntents.SHOPPING)
    val rowsFit = LocalSize.current.height >= COMPACT_HEIGHT
    val opensList: GlanceModifier.() -> GlanceModifier = { if (openList != null) clickable(actionStartActivity(openList)) else this }
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(CORNER)
            .padding(horizontal = PADDING),
    ) {
        // A 48 dp target (plan 6.6): the vertical padding, because Glance has no heightIn and the text must be able to grow.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = HEADER_PADDING).opensList(),
        ) {
            Image(
                provider = ImageProvider(DesignR.drawable.tandem_ic_shopping_cart),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(ICON),
            )
            Spacer(GlanceModifier.width(GAP))
            Text(
                text = context.getString(R.string.widget_shopping_title),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = TITLE),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (state.signedIn && state.remaining > 0) {
                Text(
                    text = context.resources.getQuantityString(R.plurals.widget_shopping_remaining, state.remaining, state.remaining),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY),
                )
            }
        }
        when {
            !rowsFit -> Unit

            !state.signedIn -> Hint(context.getString(R.string.widget_sign_in), GlanceModifier.fillMaxWidth().opensList())

            state.rows.isEmpty() -> Hint(context.getString(R.string.widget_shopping_empty), GlanceModifier.fillMaxWidth().opensList())

            else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(state.rows, itemId = { it.id }) { row ->
                    if (state.readOnly) {
                        // Plan 8.6: no write buttons for guests. A tap opens the list, nothing is checked.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = ROW_PADDING).opensList(),
                        ) {
                            RowText(row.text)
                        }
                    } else {
                        CheckRow(row)
                    }
                }
                if (state.remaining > state.rows.size) {
                    item {
                        Text(
                            text = context.getString(R.string.widget_shopping_more, state.remaining - state.rows.size),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY),
                            modifier = GlanceModifier.padding(vertical = GAP),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One line a tap checks off. At least 48 dp (plan 6.6) through the padding, so the text can grow at
 * a large font size; TalkBack hears the row's own description once, not the icon and the text too.
 */
@Composable
private fun CheckRow(row: ShoppingWidgetState.Row) {
    val description = LocalContext.current.getString(R.string.widget_shopping_check, row.text)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = ROW_PADDING)
            .semantics { contentDescription = description }
            .clickable(actionRunCallback<CheckEntryAction>(actionParametersOf(CheckEntryAction.KEY_ENTRY_ID to row.id))),
    ) {
        Image(
            provider = ImageProvider(DesignR.drawable.tandem_ic_check_circle),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.outline),
            modifier = GlanceModifier.size(ICON_SMALL),
        )
        Spacer(GlanceModifier.width(GAP))
        RowText(row.text)
    }
}

@Composable
private fun RowText(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = BODY),
        maxLines = 1,
        modifier = GlanceModifier.defaultWeight(),
    )
}

@Composable
internal fun Hint(text: String, modifier: GlanceModifier = GlanceModifier) {
    Text(text = text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY), modifier = modifier)
}

internal val CORNER = 16.dp
internal val PADDING = 12.dp
internal val GAP = 8.dp
internal val ICON = 24.dp
internal val ICON_SMALL = 20.dp

/** 2 x 12 dp around the 24 dp header icon: a 48 dp target. */
internal val HEADER_PADDING = 12.dp

/** 2 x 14 dp around the 20 dp row icon: a 48 dp target (plan 6.6). */
internal val ROW_PADDING = 14.dp

/** Below this height only the header fits (the widgets can be resized down to one cell). */
internal val COMPACT_HEIGHT = 100.dp
internal val TITLE = 16.sp
internal val BODY = 14.sp
