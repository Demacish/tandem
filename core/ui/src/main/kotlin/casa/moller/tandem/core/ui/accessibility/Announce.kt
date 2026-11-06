package casa.moller.tandem.core.ui.accessibility

import android.view.View

/**
 * TalkBack says [text]. For a result with no node of its own to report it (announceForAccessibility
 * is deprecated from API 36, there is no polite alternative without a visible node); a node that is
 * created together with the change is not a reliable live region, it has no change to report.
 */
@Suppress("DEPRECATION")
fun View.announce(text: String) {
    announceForAccessibility(text)
}
