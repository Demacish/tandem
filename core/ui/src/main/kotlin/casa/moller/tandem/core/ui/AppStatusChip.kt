package casa.moller.tandem.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.containerColor
import casa.moller.tandem.core.designsystem.component.contentColor
import casa.moller.tandem.core.designsystem.component.iconColor
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme

/** App-wide states that get a status chip next to the screen title. */
enum class AppStatus {
    /** Running on demo data (never "Offline" in demo mode, see docs/design/README.md). */
    Demo,

    /** Server unreachable; showing cached data. */
    Offline,

    /** Unknown or incompatible server version; writing is turned off (plan 3.6). */
    SafeMode,
}

/**
 * Status chip for an [AppStatus] with its icon, tone and TalkBack description.
 *
 * @param compact only the icon in its round pill (TalkBack still reads the description): beside
 *   a screen title at the largest text, where the word would cut the title ("Rece…", B22).
 */
@Composable
fun AppStatusChip(status: AppStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val spec = status.spec()
    if (compact) {
        StatusIcon(spec = spec, modifier = modifier)
        return
    }
    StatusChip(
        label = stringResource(spec.label),
        contentDescription = stringResource(spec.description),
        icon = spec.icon,
        tone = spec.tone,
        modifier = modifier,
    )
}

@Composable
private fun StatusIcon(spec: StatusSpec, modifier: Modifier = Modifier) {
    val description = stringResource(spec.description)
    Surface(
        modifier = modifier
            .size(32.dp)
            .clearAndSetSemantics { contentDescription = description },
        shape = CircleShape,
        color = spec.tone.containerColor(),
        contentColor = spec.tone.contentColor(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.tone.iconColor(),
                modifier = Modifier.size(TandemTheme.sizes.iconSmall),
            )
        }
    }
}

private class StatusSpec(@StringRes val label: Int, @StringRes val description: Int, val icon: ImageVector, val tone: TandemTone)

@Composable
private fun AppStatus.spec(): StatusSpec = when (this) {
    AppStatus.Demo -> StatusSpec(
        label = R.string.ui_status_demo,
        description = R.string.ui_status_demo_description,
        icon = TandemIcons.Science,
        tone = TandemTone.Warning,
    )

    AppStatus.Offline -> StatusSpec(
        label = R.string.ui_status_offline,
        description = R.string.ui_status_offline_description,
        icon = TandemIcons.CloudOff,
        tone = TandemTone.Neutral,
    )

    AppStatus.SafeMode -> StatusSpec(
        label = R.string.ui_status_safe_mode,
        description = R.string.ui_status_safe_mode_description,
        icon = TandemIcons.Shield,
        tone = TandemTone.Warning,
    )
}
