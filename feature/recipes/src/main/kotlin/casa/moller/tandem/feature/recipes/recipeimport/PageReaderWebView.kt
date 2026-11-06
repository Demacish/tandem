package casa.moller.tandem.feature.recipes.recipeimport

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
 * [SETTLE_MILLIS], so a script can finish), and so does every new [readRequest]. When it leaves the screen the WebView
 * is destroyed and its cookies, web storage and cache are deleted: nothing of the visit stays.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PageReaderWebView(url: String, readRequest: Int, onPage: (html: String, text: String) -> Unit, modifier: Modifier = Modifier) {
    val currentOnPage by rememberUpdatedState(onPage)
    var webView by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
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

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        val read = Runnable { if (view.isAttachedToWindow) view.readPage { html, text -> currentOnPage(html, text) } }
                        view.postDelayed(read, SETTLE_MILLIS)
                    }
                }
                loadUrl(url)
                webView = this
            }
        },
    )
    LaunchedEffect(readRequest) {
        if (readRequest > 0) webView?.readPage { html, text -> currentOnPage(html, text) }
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
