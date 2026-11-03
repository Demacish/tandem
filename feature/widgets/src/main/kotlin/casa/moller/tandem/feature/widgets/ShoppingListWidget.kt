package casa.moller.tandem.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import kotlinx.coroutines.flow.first

/**
 * Fas 3 (widgets lane): the shopping list on the home screen (plan §8.9 "Widgets"): the open
 * entries in list order with "N kvar"; a tap on a line checks it (through the outbox, offline
 * too), the header opens Handla. Reads core:data once per update; [WidgetUpdater] updates it
 * whenever the list changes while the process lives.
 */
class ShoppingListWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData(context).shopping.first()
        provideContent { GlanceTheme { ShoppingWidgetContent(state) } }
    }
}

class ShoppingListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingListWidget()
}

/** A tapped line: the entry is checked (the list's "Köpt"), and the widget redrawn. */
class CheckEntryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[KEY_ENTRY_ID] ?: return
        WidgetData(context).entryPoint.shopping().setChecked(listOf(id), checked = true)
        ShoppingListWidget().update(context, glanceId)
    }

    companion object {
        val KEY_ENTRY_ID: ActionParameters.Key<Long> = ActionParameters.Key("entryId")
    }
}

@Composable
internal fun ShoppingWidgetContent(state: ShoppingWidgetState) {
    val context = LocalContext.current
    val openList = AppOpenIntents.open(context, AppOpenIntents.SHOPPING)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(CORNER)
            .padding(PADDING),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth().let { if (openList != null) it.clickable(actionStartActivity(openList)) else it },
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
        Spacer(GlanceModifier.height(GAP))
        when {
            !state.signedIn -> Hint(context.getString(R.string.widget_sign_in))

            state.rows.isEmpty() -> Hint(context.getString(R.string.widget_shopping_empty))

            else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(state.rows, itemId = { it.id }) { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .height(ROW)
                            .clickable(actionRunCallback<CheckEntryAction>(actionParametersOf(CheckEntryAction.KEY_ENTRY_ID to row.id))),
                    ) {
                        Image(
                            provider = ImageProvider(DesignR.drawable.tandem_ic_check_circle),
                            contentDescription = context.getString(R.string.widget_shopping_check, row.text),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.outline),
                            modifier = GlanceModifier.size(ICON_SMALL),
                        )
                        Spacer(GlanceModifier.width(GAP))
                        Text(text = row.text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = BODY), maxLines = 1)
                    }
                }
                if (state.remaining > state.rows.size) {
                    item {
                        Text(
                            text = context.getString(R.string.widget_shopping_more, state.remaining - state.rows.size),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY),
                            modifier = GlanceModifier.padding(top = GAP),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text = text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY))
}

internal val CORNER = 16.dp
internal val PADDING = 12.dp
internal val GAP = 8.dp
internal val ICON = 24.dp
internal val ICON_SMALL = 20.dp
internal val ROW = 32.dp
internal val TITLE = 16.sp
internal val BODY = 14.sp
