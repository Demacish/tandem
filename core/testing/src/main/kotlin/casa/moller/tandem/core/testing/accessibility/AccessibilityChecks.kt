package casa.moller.tandem.core.testing.accessibility

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset

/**
 * Accessibility Test Framework (ATF) checks in the Robolectric Compose UI tests (plan 5.1 and 5.6,
 * Fas 1 "Klart när": the accessibility checks in the UI tests are green).
 *
 * Compose's own `enableAccessibilityChecks()` (ui-test-junit4-accessibility) does nothing under
 * Robolectric (b/332778271), so the checks run through Roborazzi's ATF integration. It checks the
 * whole window of the composition: touch targets of at least 48 dp, a label for everything that can
 * be tapped or focused, text and icon contrast measured on a real screenshot (the tests run with
 * Robolectric's NATIVE graphics, set by `tandem.android.roborazzi`), duplicate or overlapping
 * clickables and more ([AccessibilityCheckPreset.LATEST]).
 *
 * Call [assertAccessible] after `setContent`, once for every state the test wants covered (after
 * opening a sheet, after scrolling, ...). It fails on ATF errors AND warnings. A finding that is
 * intended has to be suppressed in [TandemAccessibility] with a comment that says why.
 */
fun SemanticsNodeInteractionsProvider.assertAccessible() {
    // Every window: an open bottom sheet, dialog or menu is a root of its own.
    val roots = onAllNodes(isRoot())
    val count = roots.fetchSemanticsNodes().size
    check(count > 0) { "Nothing to check: set the content first" }
    repeat(count) { index -> roots[index].assertAccessible() }
}

/** The ATF checks of the window that holds this node (see the other [assertAccessible]). */
@OptIn(ExperimentalRoborazziApi::class)
fun SemanticsNodeInteraction.assertAccessible() {
    checkRoboAccessibility(roborazziATFAccessibilityCheckOptions = TandemAccessibility.options)
}

/** The one ATF configuration of every module's UI tests, so a suppression is decided once. */
@OptIn(ExperimentalRoborazziApi::class)
object TandemAccessibility {
    val options: RoborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
        checker = RoborazziATFAccessibilityChecker(preset = AccessibilityCheckPreset.LATEST),
        failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Warning,
    )
}
