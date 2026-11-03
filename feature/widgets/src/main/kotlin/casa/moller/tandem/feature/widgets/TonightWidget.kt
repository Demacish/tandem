package casa.moller.tandem.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
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
 * Fas 3 (widgets lane): today's meals on the home screen ("I dag"): each meal with its meal type;
 * a tap opens the recipe, the header opens Veckan, and an empty day says so with "Planera".
 */
class TonightWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetData(context).tonight.first()
        provideContent { GlanceTheme { TonightWidgetContent(state) } }
    }
}

class TonightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TonightWidget()
}

@Composable
internal fun TonightWidgetContent(state: TonightWidgetState) {
    val context = LocalContext.current
    val openWeek = AppOpenIntents.open(context, AppOpenIntents.WEEK)
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
            modifier = GlanceModifier.fillMaxWidth().let { if (openWeek != null) it.clickable(actionStartActivity(openWeek)) else it },
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
        Spacer(GlanceModifier.height(GAP))
        when {
            !state.signedIn -> Hint(context.getString(R.string.widget_sign_in))

            state.meals.isEmpty() -> Hint(context.getString(R.string.widget_tonight_empty))

            else -> state.meals.forEach { meal ->
                val open = meal.recipeId?.let { AppOpenIntents.open(context, AppOpenIntents.recipe(it)) }
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .let { if (open != null) it.clickable(actionStartActivity(open)) else it },
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
