package casa.moller.tandem.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import casa.moller.tandem.core.designsystem.theme.TandemTheme

/**
 * Semantic colour of cards, chips and banners. Colour is never the only signal (plan 6.6):
 * components that take a tone also show an icon and text.
 */
enum class TandemTone {
    /** Sand surface, regular text. */
    Neutral,

    /** Tomato container: highlights and calls to action. */
    Accent,

    /** Saffron container: demo mode, safe mode, compatibility warnings. */
    Warning,

    /** Herb container: done, connected, "har hemma". */
    Success,

    /** Error container: failures. */
    Error,
}

/** Background colour of a container with this tone. */
@Composable
@ReadOnlyComposable
fun TandemTone.containerColor(): Color = when (this) {
    TandemTone.Neutral -> MaterialTheme.colorScheme.surfaceContainer
    TandemTone.Accent -> MaterialTheme.colorScheme.primaryContainer
    TandemTone.Warning -> TandemTheme.extendedColors.warningContainer
    TandemTone.Success -> TandemTheme.extendedColors.successContainer
    TandemTone.Error -> MaterialTheme.colorScheme.errorContainer
}

/** Text colour on [containerColor] (>= 4.5:1, see TandemColorContrastTest). */
@Composable
@ReadOnlyComposable
fun TandemTone.contentColor(): Color = when (this) {
    TandemTone.Neutral -> MaterialTheme.colorScheme.onSurface
    TandemTone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer
    TandemTone.Warning -> TandemTheme.extendedColors.onWarningContainer
    TandemTone.Success -> TandemTheme.extendedColors.onSuccessContainer
    TandemTone.Error -> MaterialTheme.colorScheme.onErrorContainer
}

/** Icon colour on [containerColor]: the strong colour of the tone. */
@Composable
@ReadOnlyComposable
fun TandemTone.iconColor(): Color = when (this) {
    TandemTone.Neutral -> MaterialTheme.colorScheme.primary
    TandemTone.Accent -> MaterialTheme.colorScheme.primary
    TandemTone.Warning -> TandemTheme.extendedColors.warning
    TandemTone.Success -> TandemTheme.extendedColors.success
    TandemTone.Error -> MaterialTheme.colorScheme.error
}
