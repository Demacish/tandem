package casa.moller.tandem.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast checks for the brand colour schemes (plan 6.6: "Bra kontrast (WCAG AA)").
 * Text roles need >= 4.5:1 against every background they are meant for.
 */
class TandemColorContrastTest {

    @Test
    fun lightSchemeTextRolesMeetWcagAa() = assertTextContrast("light", TandemLightColorScheme, TandemLightExtendedColors)

    @Test
    fun darkSchemeTextRolesMeetWcagAa() = assertTextContrast("dark", TandemDarkColorScheme, TandemDarkExtendedColors)

    /**
     * Pairs the components actually draw, beyond the role pairs above (review of 0.1.0): the
     * tappable amount pill of an ingredient (core:ui IngredientRow), amounts on the ingredient
     * cards, and the texts of a recipe card that is open beside the list (primaryContainer).
     */
    @Test
    fun componentUsagePairsMeetWcagAa() {
        for ((name, c) in listOf("light" to TandemLightColorScheme, "dark" to TandemDarkColorScheme)) {
            val pairs = listOf(
                Triple("amount pill", c.onPrimaryContainer, c.primaryContainer),
                Triple("amount pill, ticked off", c.onSurfaceVariant, c.surfaceContainerHighest),
                Triple("amount on the ingredient card", c.primary, c.surfaceContainerLow),
                Triple("amount in a step's ingredients", c.primary, c.surfaceContainer),
                Triple("selected recipe card text", c.onPrimaryContainer, c.primaryContainer),
                Triple("recipe card time", c.onSurfaceVariant, c.surfaceContainerLow),
            )
            val failures = pairs.mapNotNull { (usage, fg, bg) ->
                val ratio = contrast(fg, bg)
                if (ratio < AA_TEXT) "$usage = ${"%.2f".format(ratio)}" else null
            }
            assertTrue("$name scheme usage pairs below ${AA_TEXT}:1 -> $failures", failures.isEmpty())
        }
    }

    @Test
    fun outlineIsVisibleAgainstSurface() {
        // Non-text UI boundaries (text field borders, dividers): >= 3:1 (WCAG 1.4.11).
        for ((name, scheme) in listOf("light" to TandemLightColorScheme, "dark" to TandemDarkColorScheme)) {
            val ratio = contrast(scheme.outline, scheme.surface)
            assertTrue("$name outline/surface = ${"%.2f".format(ratio)}", ratio >= 3.0)
        }
    }

    private fun assertTextContrast(name: String, c: ColorScheme, e: TandemExtendedColors) {
        val surfaces = listOf(
            "background" to c.background,
            "surface" to c.surface,
            "surfaceVariant" to c.surfaceVariant,
            "surfaceBright" to c.surfaceBright,
            "surfaceDim" to c.surfaceDim,
            "surfaceContainerLowest" to c.surfaceContainerLowest,
            "surfaceContainerLow" to c.surfaceContainerLow,
            "surfaceContainer" to c.surfaceContainer,
            "surfaceContainerHigh" to c.surfaceContainerHigh,
            "surfaceContainerHighest" to c.surfaceContainerHighest,
        )
        // Accent-coloured text (links, text buttons, overlines) is used on the lighter surfaces
        // only; on High/Highest/Dim use onSurface or onSurfaceVariant.
        val accentSurfaces = surfaces.filter { (n, _) ->
            n in setOf("background", "surface", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer")
        }
        val pairs = buildList {
            add(Triple("onPrimary", c.onPrimary, "primary" to c.primary))
            add(Triple("onPrimaryContainer", c.onPrimaryContainer, "primaryContainer" to c.primaryContainer))
            add(Triple("onSecondary", c.onSecondary, "secondary" to c.secondary))
            add(Triple("onSecondaryContainer", c.onSecondaryContainer, "secondaryContainer" to c.secondaryContainer))
            add(Triple("onTertiary", c.onTertiary, "tertiary" to c.tertiary))
            add(Triple("onTertiaryContainer", c.onTertiaryContainer, "tertiaryContainer" to c.tertiaryContainer))
            add(Triple("onError", c.onError, "error" to c.error))
            add(Triple("onErrorContainer", c.onErrorContainer, "errorContainer" to c.errorContainer))
            add(Triple("inverseOnSurface", c.inverseOnSurface, "inverseSurface" to c.inverseSurface))
            add(Triple("inversePrimary", c.inversePrimary, "inverseSurface" to c.inverseSurface))
            add(Triple("onPrimaryFixed", c.onPrimaryFixed, "primaryFixed" to c.primaryFixed))
            add(Triple("onPrimaryFixed", c.onPrimaryFixed, "primaryFixedDim" to c.primaryFixedDim))
            add(Triple("onPrimaryFixedVariant", c.onPrimaryFixedVariant, "primaryFixed" to c.primaryFixed))
            add(Triple("onSecondaryFixed", c.onSecondaryFixed, "secondaryFixed" to c.secondaryFixed))
            add(Triple("onSecondaryFixed", c.onSecondaryFixed, "secondaryFixedDim" to c.secondaryFixedDim))
            add(Triple("onSecondaryFixedVariant", c.onSecondaryFixedVariant, "secondaryFixed" to c.secondaryFixed))
            add(Triple("onTertiaryFixed", c.onTertiaryFixed, "tertiaryFixed" to c.tertiaryFixed))
            add(Triple("onTertiaryFixed", c.onTertiaryFixed, "tertiaryFixedDim" to c.tertiaryFixedDim))
            add(Triple("onTertiaryFixedVariant", c.onTertiaryFixedVariant, "tertiaryFixed" to c.tertiaryFixed))
            add(Triple("onWarning", e.onWarning, "warning" to e.warning))
            add(Triple("onWarningContainer", e.onWarningContainer, "warningContainer" to e.warningContainer))
            add(Triple("onSuccess", e.onSuccess, "success" to e.success))
            add(Triple("onSuccessContainer", e.onSuccessContainer, "successContainer" to e.successContainer))
            for (surface in surfaces) {
                add(Triple("onSurface", c.onSurface, surface))
                add(Triple("onSurfaceVariant", c.onSurfaceVariant, surface))
            }
            add(Triple("onBackground", c.onBackground, "background" to c.background))
            for (surface in accentSurfaces) {
                add(Triple("primary", c.primary, surface))
                add(Triple("secondary", c.secondary, surface))
                add(Triple("tertiary", c.tertiary, surface))
                add(Triple("error", c.error, surface))
                add(Triple("warning", e.warning, surface))
                add(Triple("success", e.success, surface))
            }
        }
        val failures = pairs.mapNotNull { (fgName, fg, bg) ->
            val ratio = contrast(fg, bg.second)
            if (ratio < AA_TEXT) "$fgName on ${bg.first} = ${"%.2f".format(ratio)}" else null
        }
        assertTrue("$name scheme below ${AA_TEXT}:1 -> $failures", failures.isEmpty())
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val (hi, lo) = if (la >= lb) la to lb else lb to la
        return (hi + 0.05) / (lo + 0.05)
    }

    private companion object {
        const val AA_TEXT = 4.5
    }
}
