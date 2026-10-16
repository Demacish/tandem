package casa.moller.tandem.core.testing.accessibility

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.checkRoboAccessibility
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult as CheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.DuplicateSpeakableTextCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher

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

/**
 * The one ATF configuration of every module's UI tests, so a suppression is decided once. Every
 * suppression is narrow (one check, one kind of element) and says why the finding is intended.
 */
@OptIn(ExperimentalRoborazziApi::class)
object TandemAccessibility {

    /**
     * Contrast of a DISABLED control (and the text inside it), e.g. the recovery screen's "Share as
     * text" while nothing can be exported. WCAG 1.4.3 exempts inactive user interface components;
     * Material draws them at 38 % on purpose, so they read as unavailable.
     */
    private val disabledControlContrast: Matcher<CheckResult> = result("contrast of a disabled control") {
        (sourceCheckClass == TextContrastCheck::class.java || sourceCheckClass == ImageContrastCheck::class.java) &&
            element?.selfOrAncestorDisabled() == true
    }

    /**
     * The same CHECKBOX twice: Tandoor shows a step's ingredients again under the step
     * (`show_ingredients_table`, web parity, plan 6.2), so the recipe detail has two ingredient
     * rows with the same text. Both toggle the same checked state, and each is read inside its own
     * section (Ingredients, or the step), so they are not two different controls with one name.
     */
    private val repeatedIngredientCheckbox: Matcher<CheckResult> = result("a repeated ingredient checkbox") {
        sourceCheckClass == DuplicateSpeakableTextCheck::class.java && element?.isCheckable() == true
    }

    val options: RoborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
        checker = RoborazziATFAccessibilityChecker(
            preset = AccessibilityCheckPreset.LATEST,
            suppressions = anyOf(disabledControlContrast, repeatedIngredientCheckbox),
        ),
        failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Warning,
    )

    private fun ViewHierarchyElement.selfOrAncestorDisabled(): Boolean = generateSequence(this) { it.parentView }.any { !it.isEnabled }

    private fun result(description: String, predicate: CheckResult.() -> Boolean): Matcher<CheckResult> =
        object : TypeSafeMatcher<CheckResult>() {
            override fun matchesSafely(item: CheckResult): Boolean = item.predicate()

            override fun describeTo(out: Description) {
                out.appendText(description)
            }
        }
}
