package casa.moller.tandem.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Tandem's colours on the watch: the phone's dark scheme "kvällsmatlagning" (light tomato primary,
 * saffron, herb green, warm off-white text; docs/design) on a black background, which watches
 * want (OLED, always dark). Never the watch face's dynamic colours, so the app looks like Tandem.
 */
@Composable
fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TandemWearColors, content = content)
}

private val Espresso = Color(0xFF1C1714)
private val WarmWhite = Color(0xFFF3EBE3)

internal val TandemWearColors: ColorScheme = ColorScheme(
    primary = Color(0xFFFB8A5C),
    primaryDim = Color(0xFFE07A50),
    primaryContainer = Color(0xFF8C2F08),
    onPrimary = Espresso,
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFF5B044),
    secondaryDim = Color(0xFFDB9C3B),
    secondaryContainer = Color(0xFF6B4100),
    onSecondary = Espresso,
    onSecondaryContainer = Color(0xFFFEDEA8),
    tertiary = Color(0xFF9BC46A),
    tertiaryDim = Color(0xFF89AF5D),
    tertiaryContainer = Color(0xFF35530C),
    onTertiary = Espresso,
    onTertiaryContainer = Color(0xFFD3F89A),
    surfaceContainerLow = Color(0xFF231D1A),
    surfaceContainer = Color(0xFF2A2320),
    surfaceContainerHigh = Color(0xFF352D29),
    onSurface = WarmWhite,
    onSurfaceVariant = Color(0xFFBFB3AA),
    outline = Color(0xFF8F837A),
    outlineVariant = Color(0xFF4E433E),
    background = Color.Black,
    onBackground = WarmWhite,
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFFE59F97),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)
