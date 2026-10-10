package casa.moller.tandem.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Brand colours from the Stitch design system "Recept – varm kök" (docs/design/stitch-export/
 * recept_varm_k_k/DESIGN.md). Use them for decoration, illustrations and the launcher icon only:
 * text and icons on surfaces must use the [ColorScheme] roles, which are tuned for contrast.
 */
object TandemBrand {
    /** Tomato / terracotta: primary actions ("Laga", "Lägg på listan"). */
    val Tomato = Color(0xFFC2410C)

    /** Saffron: timers and highlights. Too light for text on cream (2.99:1), decoration only. */
    val Saffron = Color(0xFFD97706)

    /** Herb green: "har hemma", checked off, done. */
    val Herb = Color(0xFF4D7C0F)

    /** Warm cream background. */
    val Cream = Color(0xFFFBF7F2)

    /** Dark mode: warm, almost black brown background (never cold blue-grey). */
    val Espresso = Color(0xFF1C1714)

    /** Dark mode: light tomato primary. */
    val TomatoLight = Color(0xFFFB8A5C)
}

/*
 * Light scheme. Deviations from the Stitch tokens, all for WCAG AA (>= 4.5:1, checked in
 * TandemColorContrastTest):
 * - secondary #9C4F00 instead of saffron #D97706 (white on saffron is only 3.19:1).
 * - tertiary #456F0C instead of #4D7C0F (herb green text on cream containers was < 4.5:1).
 * - surface containers are a little lighter than in Stitch so primary-coloured text (links,
 *   text buttons) stays >= 4.5:1 on surface, surfaceContainerLowest, Low and the default
 *   surfaceContainer. Use onSurface / onSurfaceVariant for text on High / Highest / Dim.
 */
private val LightPrimary = TandemBrand.Tomato
private val LightOnPrimaryFixedVariant = Color(0xFF8C2F08)

internal val TandemLightColorScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF5A1A02),
    inversePrimary = Color(0xFFFFB599),
    secondary = Color(0xFF9C4F00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFEDEA8),
    onSecondaryContainer = Color(0xFF3D2200),
    tertiary = Color(0xFF456F0C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3F89A),
    onTertiaryContainer = Color(0xFF1D3300),
    background = TandemBrand.Cream,
    onBackground = Color(0xFF2A2421),
    surface = TandemBrand.Cream,
    onSurface = Color(0xFF2A2421),
    surfaceVariant = Color(0xFFEEE6DD),
    onSurfaceVariant = Color(0xFF665A50),
    surfaceTint = LightPrimary,
    inverseSurface = Color(0xFF3A322E),
    inverseOnSurface = Color(0xFFFAF0E8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF690005),
    outline = Color(0xFF857870),
    outlineVariant = Color(0xFFD6CABE),
    scrim = Color(0xFF000000),
    surfaceBright = TandemBrand.Cream,
    surfaceContainer = Color(0xFFF6F0E9),
    surfaceContainerHigh = Color(0xFFEEE6DD),
    surfaceContainerHighest = Color(0xFFE8DFD5),
    surfaceContainerLow = Color(0xFFF9F4EE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3DAD0),
    primaryFixed = Color(0xFFFFDBCF),
    primaryFixedDim = Color(0xFFFFB599),
    onPrimaryFixed = Color(0xFF3C0C00),
    onPrimaryFixedVariant = LightOnPrimaryFixedVariant,
    secondaryFixed = Color(0xFFFEDEA8),
    secondaryFixedDim = Color(0xFFF5B044),
    onSecondaryFixed = Color(0xFF2D1600),
    onSecondaryFixedVariant = Color(0xFF6B4100),
    tertiaryFixed = Color(0xFFD3F89A),
    tertiaryFixedDim = Color(0xFF9BC46A),
    onTertiaryFixed = Color(0xFF142700),
    onTertiaryFixedVariant = Color(0xFF35530C),
)

/*
 * Dark scheme: "kvällsmatlagning". Warm espresso surfaces (#1C1714 / #2A2320), light tomato
 * primary with dark text on buttons, saffron #F5B044, herb #9BC46A, warm off-white text #F3EBE3
 * (docs/design/stitch-export/recept_varm_k_k_m_rk/DESIGN.md). inversePrimary is #B53D0B instead
 * of #C2410C so it reaches 4.5:1 on the light inverse surface.
 */
internal val TandemDarkColorScheme: ColorScheme = darkColorScheme(
    primary = TandemBrand.TomatoLight,
    onPrimary = TandemBrand.Espresso,
    primaryContainer = Color(0xFF8C2F08),
    onPrimaryContainer = Color(0xFFFFDBCF),
    inversePrimary = Color(0xFFB53D0B),
    secondary = Color(0xFFF5B044),
    onSecondary = TandemBrand.Espresso,
    secondaryContainer = Color(0xFF6B4100),
    onSecondaryContainer = Color(0xFFFEDEA8),
    tertiary = Color(0xFF9BC46A),
    onTertiary = TandemBrand.Espresso,
    tertiaryContainer = Color(0xFF35530C),
    onTertiaryContainer = Color(0xFFD3F89A),
    background = TandemBrand.Espresso,
    onBackground = Color(0xFFF3EBE3),
    surface = TandemBrand.Espresso,
    onSurface = Color(0xFFF3EBE3),
    surfaceVariant = Color(0xFF3A312C),
    onSurfaceVariant = Color(0xFFBFB3AA),
    surfaceTint = TandemBrand.TomatoLight,
    inverseSurface = Color(0xFFF3EBE3),
    inverseOnSurface = Color(0xFF362F2B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F837A),
    outlineVariant = Color(0xFF4E433E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF433A35),
    surfaceContainer = Color(0xFF2A2320),
    surfaceContainerHigh = Color(0xFF352D29),
    surfaceContainerHighest = Color(0xFF40372F),
    surfaceContainerLow = Color(0xFF231D1A),
    surfaceContainerLowest = Color(0xFF140F0D),
    surfaceDim = TandemBrand.Espresso,
    primaryFixed = Color(0xFFFFDBCF),
    primaryFixedDim = Color(0xFFFFB599),
    onPrimaryFixed = Color(0xFF3C0C00),
    onPrimaryFixedVariant = LightOnPrimaryFixedVariant,
    secondaryFixed = Color(0xFFFEDEA8),
    secondaryFixedDim = Color(0xFFF5B044),
    onSecondaryFixed = Color(0xFF2D1600),
    onSecondaryFixedVariant = Color(0xFF6B4100),
    tertiaryFixed = Color(0xFFD3F89A),
    tertiaryFixedDim = Color(0xFF9BC46A),
    onTertiaryFixed = Color(0xFF142700),
    onTertiaryFixedVariant = Color(0xFF35530C),
)

/**
 * Colours that Material 3 has no role for. Read them with `TandemTheme.extendedColors`.
 *
 * - `warning*`: safe mode / compatibility warnings and the demo banner (saffron family).
 * - `success*`: "done", "har hemma", checked off (herb family; same hue as tertiary but kept
 *   separate so tertiary can change without changing meaning).
 */
@Immutable
data class TandemExtendedColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

internal val TandemLightExtendedColors = TandemExtendedColors(
    warning = Color(0xFF8A4A00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEDEA8),
    onWarningContainer = Color(0xFF3D2200),
    success = Color(0xFF456F0C),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD3F89A),
    onSuccessContainer = Color(0xFF1D3300),
)

internal val TandemDarkExtendedColors = TandemExtendedColors(
    warning = Color(0xFFF5B044),
    onWarning = TandemBrand.Espresso,
    warningContainer = Color(0xFF5A3600),
    onWarningContainer = Color(0xFFFEDEA8),
    success = Color(0xFF9BC46A),
    onSuccess = TandemBrand.Espresso,
    successContainer = Color(0xFF35530C),
    onSuccessContainer = Color(0xFFD3F89A),
)
