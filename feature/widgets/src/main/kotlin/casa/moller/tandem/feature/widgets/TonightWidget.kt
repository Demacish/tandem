package casa.moller.tandem.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.ui.navigation.AppOpenIntents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Fas 3 (widgets lane): today's meals on the home screen ("I dag"): each meal with its meal type;
 * a tap starts cook mode for the recipe (plan 8.7), the header opens Veckan, and an empty day says
 * so and opens Veckan on a tap. The flow is followed inside the composition, as in [ShoppingListWidget].
 */
class TonightWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tonight = WidgetData(context).tonight
        val first = tonight.first()
        provideContent { GlanceTheme { TonightWidgetLive(tonight, first) } }
    }
}

/** [TonightWidgetContent] following [states], from [first]. */
@Composable
internal fun TonightWidgetLive(states: Flow<TonightWidgetState>, first: TonightWidgetState) {
    val state by states.collectAsState(first)
    TonightWidgetContent(state)
}

class TonightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TonightWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        MidnightRedraw.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MidnightRedraw.cancel(context)
    }
}

@Composable
internal fun TonightWidgetContent(state: TonightWidgetState) {
    val context = LocalContext.current
    val openWeek = AppOpenIntents.open(context, AppOpenIntents.WEEK)
    val opensWeek: GlanceModifier.() -> GlanceModifier = { if (openWeek != null) clickable(actionStartActivity(openWeek)) else this }
    // One cell high: the meals get the room, Veckan is still reached through a meal-less state.
    val showHeader = LocalSize.current.height >= COMPACT_HEIGHT
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(CORNER)
            .padding(horizontal = PADDING),
    ) {
        if (showHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = HEADER_PADDING).opensWeek(),
            ) {
                Image(
                    provider = ImageProvider(DesignR.drawable.tandem_ic_restaurant),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(ICON),
                )
                Spacer(GlanceModifier.width(GAP))
                Text(
                    text = context.getString(R.string.widget_tonight_title),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = TITLE),
                )
            }
        }
        when {
            !state.signedIn -> Hint(context.getString(R.string.widget_sign_in), GlanceModifier.fillMaxSize().opensWeek())

            state.meals.isEmpty() -> Hint(context.getString(R.string.widget_tonight_empty), GlanceModifier.fillMaxSize().opensWeek())

            // A list, so that lunch and dinner, or a large font, can be scrolled to in a small widget.
            else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(state.meals, itemId = { it.id }) { meal ->
                    val open = meal.recipeId?.let { AppOpenIntents.open(context, AppOpenIntents.cook(it)) }
                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .let { if (open != null) it.clickable(actionStartActivity(open)) else it.opensWeek() },
                    ) {
                        Text(
                            text = meal.mealType,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = BODY),
                            maxLines = 1,
                        )
                        Text(
                            text = meal.title,
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = TITLE),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
