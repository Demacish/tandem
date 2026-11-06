package casa.moller.tandem.feature.widgets

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import casa.moller.tandem.core.data.di.ApplicationScope
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Fas 3 (widgets lane): redraws the widgets when what they show changes while the process lives
 * (a sync, a check in the app, another member's change, logout), and asks the quick settings
 * tile to read its count again. Started once by the
 * application, after Hilt. A redraw of a widget nobody placed is a no-op; a failure (the
 * launcher is gone) is ignored: the next change tries again.
 *
 * "I dag" is also redrawn at every process start (its first value is NOT dropped): "today" only
 * moves while something collects, so the first value of a new process can be a new day. A phone
 * where no process starts gets [MidnightRedraw] just after midnight. An update lands in a running
 * session through the widgets' own collection; here it only starts a session when none runs.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val entryPointProvider: WidgetEntryPointProvider,
) {
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        val data = WidgetData(entryPointProvider.get(), context.resources.configuration.locales[0])
        scope.launch {
            data.shopping.drop(1).debounce(DEBOUNCE_MILLIS).collect {
                runCatching { ShoppingListWidget().updateAll(context) }
                // Fas 3's leftovers: the tile "Handla" reads its count again too (plan §10 "Klart när").
                runCatching { TileService.requestListeningState(context, ComponentName(context, ShoppingTileService::class.java)) }
            }
        }
        scope.launch {
            data.tonight.debounce(DEBOUNCE_MILLIS).collect { runCatching { TonightWidget().updateAll(context) } }
        }
        // Plan 8.7: the shortcuts follow the cook log, and go when the account goes.
        scope.launch {
            data.recentRecipes.debounce(DEBOUNCE_MILLIS).collect { AppShortcuts.install(context, it) }
        }
        scope.launch { if (MidnightRedraw.wanted(context)) MidnightRedraw.schedule(context) }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
    }
}

/** Hands [WidgetUpdater] the entry point (a Hilt entry point cannot be injected directly). */
class WidgetEntryPointProvider @Inject constructor(@ApplicationContext private val context: Context) {
    fun get(): WidgetEntryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
}
