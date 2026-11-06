package casa.moller.tandem.feature.recipes.recipeimport

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.AndroidRuntimeException
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONTokener

/**
 * #38 (plan §8.4 step 2, Fas 3's leftovers): the shared page, loaded in the app because the
 * server could not fetch it (a site that blocks servers). JavaScript is on for this WebView only
 * (many recipe pages build their JSON-LD or pass a bot check with it); files, content uris, pop-up
 * windows, geolocation and third-party cookies are off, and links that are not http(s) are not
 * followed. Each finished load hands the page's HTML and visible text to [onPage] (after
 * [SETTLE_MILLIS], so a script can finish), and so does every new [readRequest]. Cookies, web
 * storage and the cache are deleted before the page loads (what an interrupted visit left behind)
 * and when it leaves the screen, so a visit leaves nothing for the next one.
 *
 * A page that never loaded (no network, a TLS error; only the main frame counts) is not sent as if
 * it were the recipe page: [onFailed] says so. A 403 or 503 page is not a failure here: it may be
 * the bot check the user has to pass. [onFailed] also covers a renderer that died (a hostile or
 * huge page, or the system's memory pressure: without [WebViewClient.onRenderProcessGone] that
 * takes the whole app down) and a device whose WebView package is missing or updating.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PageReaderWebView(
    url: String,
    readRequest: Int,
    onPage: (html: String, text: String) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnPage by rememberUpdatedState(onPage)
    val currentOnFailed by rememberUpdatedState(onFailed)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val created = try {
                WebView(context)
            } catch (e: AndroidRuntimeException) {
                // No WebView package on the device right now (being updated, or removed).
                return@AndroidView View(context).apply { post { currentOnFailed() } }
            }
            created.apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val scheme = request.url.scheme?.lowercase()
                        return scheme != "https" && scheme != "http"
                    }

                    override fun onPageStarted(view: WebView, startedUrl: String?, favicon: Bitmap?) {
                        loadFailed = false
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            loadFailed = true
                            currentOnFailed()
                        }
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        val read = Runnable {
                            if (view.isAttachedToWindow && !loadFailed) view.readPage { html, text -> currentOnPage(html, text) }
                        }
                        view.postDelayed(read, SETTLE_MILLIS)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        // The dead view must not be touched again: the effects below go through webView.
                        if (webView === view) webView = null
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                        currentOnFailed()
                        return true
                    }
                }
                webView = this
                // What an interrupted visit left (a crash, a force stop) goes first. Removing
                // cookies is asynchronous, so the page loads in its callback.
                clearCache(true)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies { if (webView === this) loadUrl(url) }
            }
        },
    )
    LaunchedEffect(readRequest) {
        if (readRequest > 0 && !loadFailed) webView?.readPage { html, text -> currentOnPage(html, text) }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                view.clearHistory()
                view.clearCache(true)
                view.destroy()
            }
            webView = null
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}

/** The page's markup and visible text as the browser holds them now (after scripts ran); nothing when it cannot be read. */
private fun WebView.readPage(onPage: (html: String, text: String) -> Unit) {
    evaluateJavascript(OUTER_HTML) { result ->
        val html = javaScriptString(result)
        if (!html.isNullOrBlank()) evaluateJavascript(BODY_TEXT) { text -> onPage(html, javaScriptString(text).orEmpty()) }
    }
}

/** A result of `evaluateJavascript` is a JSON value: a quoted string, or null. */
private fun javaScriptString(result: String?): String? = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()

private const val OUTER_HTML = "(function() { return document.documentElement.outerHTML; })()"
private const val BODY_TEXT = "(function() { return document.body ? document.body.innerText : ''; })()"

/** Time for a page's scripts after it finished loading, before its HTML is read. */
private const val SETTLE_MILLIS = 1_500L
