package casa.moller.tandem.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Tandem's app theme: Material 3 Expressive with the warm kitchen palette, Bricolage Grotesque /
 * DM Sans, rounded shapes and the expressive (spring-based) motion scheme.
 *
 * @param darkTheme dark ("kvällsmatlagning") colours. Defaults to the system setting; the
 *   settings screen can force light or dark later (plan 6.4).
 * @param dynamicColor use Material You wallpaper colours instead of the brand palette. Off by
 *   default (brand first); kept as an option for a later setting. minSdk 34 always supports it.
 */
@Composable
fun TandemTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, content: @Composable () -> Unit) {
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
}

internal val LocalTandemExtendedColors = staticCompositionLocalOf { TandemLightExtendedColors }
internal val LocalTandemExtendedTypography =
    staticCompositionLocalOf { TandemExtendedTypographyDefault }
internal val LocalTandemSpacing = staticCompositionLocalOf { TandemSpacing() }
internal val LocalTandemElevation = staticCompositionLocalOf { TandemElevation() }
internal val LocalTandemSizes = staticCompositionLocalOf { TandemSizes() }
