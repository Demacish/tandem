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

    /**
     * Text contrast INSIDE a text field (its value or floating label). ATF estimates the two
     * colours from the element's box, which also holds the field's outline (`outline`, 4.3:1 on
     * the white field, above the 3:1 WCAG 1.4.11 asks of a component's border) and the notch of
     * the floating label; with a short value ("anna") or a 12 sp label at mdpi it takes the
     * outline or antialiasing for the text colour. The real colours are fixed once in
     * core:designsystem `TandemTextField`: value `onSurface` (13:1) and label `onSurfaceVariant`
     * (6.6:1) on `surfaceContainerLowest`, light and dark. Found by the 0.2.0 onboarding checks.
     */
    private val textFieldContrast: Matcher<CheckResult> = result("text contrast measured inside a text field") {
        sourceCheckClass == TextContrastCheck::class.java && element?.selfOrAncestorIsTextField() == true
    }

    /**
     * Text contrast of a SLIVER of text that a scrolling list cuts at its edge (0.3.0 komplettering:
     * the tablet's "Handla för veckan" showed a line of 6 px at the bottom of its list). ATF marks
     * such a result itself ("may be only partially visible within a scrollable container",
     * `KEY_IS_AGAINST_SCROLLABLE_EDGE`) and estimates both colours from a few rows of antialiased
     * pixels. Only slivers lower than [SLIVER_MAX_PX] are left out; the same text is measured in
     * full where it is not cut (other states, sizes and the ATF checks of every screen).
     */
    private val slicedTextContrast: Matcher<CheckResult> = result("text contrast of a sliver cut by a scrolling edge") {
        sourceCheckClass == TextContrastCheck::class.java &&
            metadata?.getBoolean(KEY_AGAINST_SCROLLABLE_EDGE, false) == true &&
            (element?.boundsInScreen?.height ?: Int.MAX_VALUE) < SLIVER_MAX_PX
    }

    val options: RoborazziATFAccessibilityCheckOptions = RoborazziATFAccessibilityCheckOptions(
        checker = RoborazziATFAccessibilityChecker(
            preset = AccessibilityCheckPreset.LATEST,
            suppressions = anyOf(disabledControlContrast, repeatedIngredientCheckbox, textFieldContrast, slicedTextContrast),
        ),
        failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Warning,
    )

    private fun ViewHierarchyElement.selfOrAncestorDisabled(): Boolean = generateSequence(this) { it.parentView }.any { !it.isEnabled }

    private fun ViewHierarchyElement.selfOrAncestorIsTextField(): Boolean =
        generateSequence(this) { it.parentView }.any { it.className?.toString() == EDIT_TEXT_CLASS }

    private fun result(description: String, predicate: CheckResult.() -> Boolean): Matcher<CheckResult> =
        object : TypeSafeMatcher<CheckResult>() {
            override fun matchesSafely(item: CheckResult): Boolean = item.predicate()

            override fun describeTo(out: Description) {
                out.appendText(description)
            }
        }
}

private const val EDIT_TEXT_CLASS = "android.widget.EditText"

/** ATF's TextContrastCheck result metadata: the element touches the edge of a scrolling container. */
private const val KEY_AGAINST_SCROLLABLE_EDGE = "KEY_IS_AGAINST_SCROLLABLE_EDGE"

/** Less than one line of the smallest text the app uses (12 sp) at mdpi. */
private const val SLIVER_MAX_PX = 12
