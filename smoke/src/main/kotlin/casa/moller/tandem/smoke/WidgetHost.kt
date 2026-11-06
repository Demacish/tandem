package casa.moller.tandem.smoke

import android.app.Instrumentation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.Closeable

/**
 * Fas 4 review (fix-bygg): a very small launcher, so that a home-screen widget of the R8-minified
 * app runs for real. The test APK may bind widgets after `appwidget grantbind`, like Android's own
 * CTS tests; the app's receiver, `provideGlance` and Hilt entry point then run as on a phone, and
 * what the app sends comes back as RemoteViews in a view that is measured here, never shown. A tap
 * is the view's own click: it fires the PendingIntent a launcher would fire.
 */
class WidgetHost(private val app: TandemApp, private val instrumentation: Instrumentation) : Closeable {
    private val context = instrumentation.context
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    private var view: AppWidgetHostView? = null

    /** Binds the app's widget [receiver] (a full class name), at a phone home screen's size. */
    fun bind(receiver: String) {
        val user = Process.myUserHandle().hashCode()
        app.shell("appwidget grantbind --package ${context.packageName} --user $user")
        host.startListening()
        val id = host.allocateAppWidgetId()
        val provider = ComponentName(TandemApp.PACKAGE, receiver)
        check(manager.bindAppWidgetIdIfAllowed(id, provider)) { "The widget $receiver could not be bound" }
        val info = checkNotNull(manager.getAppWidgetInfo(id)) { "No provider info for $receiver" }
        instrumentation.runOnMainSync { view = host.createView(context, id, info) }
        manager.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, WIDTH_DP)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, WIDTH_DP)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, HEIGHT_DP)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, HEIGHT_DP)
            },
        )
    }

    /** Every text in the widget as it is now. */
    fun texts(): List<String> = onMain { views().mapNotNull { (it as? TextView)?.text?.toString() } }

    /** The content descriptions in the widget as they are now. */
    fun descriptions(): List<String> = onMain { views().mapNotNull { it.contentDescription?.toString() } }

    /** Waits until [predicate] holds for [texts] (the app draws in its own time). */
    fun waitForTexts(what: String, predicate: (List<String>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!predicate(texts())) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("The widget never showed $what: ${texts()}")
            SystemClock.sleep(POLL_MS)
        }
    }

    /**
     * Clicks the view described as [description] and tells whether it was there and took the click:
     * Glance puts the click on a frame around the row, the description on the row.
     */
    fun click(description: String): Boolean = onMain {
        val target = views().firstOrNull { it.contentDescription?.toString() == description }
        generateSequence(target) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() }?.performClick() == true
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching { block() } }
        return checkNotNull(result).getOrThrow()
    }

    /** The view tree, laid out first: a list only makes its rows in a layout pass. */
    private fun views(): List<View> {
        val root = view ?: return emptyList()
        val density = context.resources.displayMetrics.density
        val width = (WIDTH_DP * density).toInt()
        val height = (HEIGHT_DP * density).toInt()
        val exactly = View.MeasureSpec.EXACTLY
        root.measure(View.MeasureSpec.makeMeasureSpec(width, exactly), View.MeasureSpec.makeMeasureSpec(height, exactly))
        root.layout(0, 0, width, height)
        val found = mutableListOf<View>()
        val queue = ArrayDeque(listOf<View>(root))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            found += next
            if (next is ViewGroup) for (index in 0 until next.childCount) queue.addLast(next.getChildAt(index))
        }
        return found
    }

    override fun close() {
        runCatching {
            host.stopListening()
            host.deleteHost()
        }
    }

    private companion object {
        const val HOST_ID = 4_404
        const val WIDTH_DP = 320
        const val HEIGHT_DP = 320
        const val TIMEOUT_MS = 45_000L
        const val POLL_MS = 500L
    }
}
