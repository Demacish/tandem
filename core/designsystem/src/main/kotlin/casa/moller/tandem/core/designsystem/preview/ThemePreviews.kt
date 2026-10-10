package casa.moller.tandem.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Renders a preview in light and dark mode at phone width. TandemTheme follows
 * isSystemInDarkTheme(), which reads the preview's uiMode.
 */
@Preview(name = "Light", widthDp = 411)
@Preview(
    name = "Dark",
    widthDp = 411,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
annotation class ThemePreviews
