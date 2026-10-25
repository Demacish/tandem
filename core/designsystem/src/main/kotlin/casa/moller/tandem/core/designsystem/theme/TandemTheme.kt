package casa.moller.tandem.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Tandem's app theme: Material 3 Expressive with the warm kitchen palette, Bricolage Grotesque /
 * DM Sans, rounded shapes and the expressive (spring-based) motion scheme.
 *
 * The app applies the user's choices once at the top (Settings → Appearance, plan 6.4):
 * `MainActivity` passes the theme (system/light/dark) as [darkTheme] and "Dynamisk färg" as
 * [dynamicColor]. A theme nested inside (cook mode's "always dark", [TandemDarkTheme]) inherits
 * the dynamic colour choice unless it passes its own.
 *
 * @param darkTheme dark ("kvällsmatlagning") colours. Defaults to the system setting.
 * @param dynamicColor use Material You wallpaper colours instead of the brand palette. Defaults to
 *   the surrounding theme's choice (off at the top: brand first). minSdk 34 always supports it.
 */
@Composable
fun TandemTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = LocalTandemThemeChoice.current.dynamicColor,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> TandemDarkColorScheme

        else -> TandemLightColorScheme
    }
    CompositionLocalProvider(
        LocalTandemExtendedColors provides
            if (darkTheme) TandemDarkExtendedColors else TandemLightExtendedColors,
        LocalTandemExtendedTypography provides TandemExtendedTypographyDefault,
        LocalTandemSpacing provides TandemSpacing(),
        LocalTandemElevation provides TandemElevation(),
        LocalTandemSizes provides TandemSizes(),
        LocalTandemThemeChoice provides TandemThemeChoice(darkTheme = darkTheme, dynamicColor = dynamicColor),
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = TandemShapes,
            typography = TandemTypography,
            content = content,
        )
    }
}

/**
 * Cook mode's "Kokläge alltid mörkt" (plan 6.4 "Kokläget kan tvingas mörkt", Settings →
 * Appearance): [content] in the dark theme when [enabled], else in the surrounding theme's
 * choice. Keeps the surrounding dynamic colour choice.
 *
 * [content] has ONE call site whatever [enabled] is: switching it (or the setting arriving after
 * the first frame) recolours the content instead of throwing its state away (pager position,
 * effects, saveable state; detekt's ContentSlotReused).
 */
@Composable
fun TandemDarkTheme(enabled: Boolean, content: @Composable () -> Unit) {
    val outer = LocalTandemThemeChoice.current
    TandemTheme(darkTheme = enabled || outer.darkTheme, dynamicColor = outer.dynamicColor, content = content)
}

/** What the closest [TandemTheme] was asked for: dark or light, brand or dynamic colours. */
@Immutable
data class TandemThemeChoice(val darkTheme: Boolean = false, val dynamicColor: Boolean = false)

/**
 * Tandem tokens that Material 3 has no slot for. Use [MaterialTheme] for colours, typography,
 * shapes and motion, and `TandemTheme.*` for the extras.
 */
object TandemTheme {
    val extendedColors: TandemExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalTandemExtendedColors.current

    val extendedTypography: TandemExtendedTypography
        @Composable @ReadOnlyComposable
        get() = LocalTandemExtendedTypography.current

    val spacing: TandemSpacing
        @Composable @ReadOnlyComposable
        get() = LocalTandemSpacing.current

    val elevation: TandemElevation
        @Composable @ReadOnlyComposable
        get() = LocalTandemElevation.current

    val sizes: TandemSizes
        @Composable @ReadOnlyComposable
        get() = LocalTandemSizes.current

    /** The closest theme's dark/light and dynamic colour choice. */
    val choice: TandemThemeChoice
        @Composable @ReadOnlyComposable
        get() = LocalTandemThemeChoice.current
}

internal val LocalTandemExtendedColors = staticCompositionLocalOf { TandemLightExtendedColors }
internal val LocalTandemExtendedTypography =
    staticCompositionLocalOf { TandemExtendedTypographyDefault }
internal val LocalTandemSpacing = staticCompositionLocalOf { TandemSpacing() }
internal val LocalTandemElevation = staticCompositionLocalOf { TandemElevation() }
internal val LocalTandemSizes = staticCompositionLocalOf { TandemSizes() }
internal val LocalTandemThemeChoice = staticCompositionLocalOf { TandemThemeChoice() }
