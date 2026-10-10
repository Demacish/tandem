package casa.moller.tandem.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons

/** App-wide states that get a status chip next to the screen title. */
enum class AppStatus {
    /** Running on demo data (never "Offline" in demo mode, see docs/design/README.md). */
    Demo,

    /** Server unreachable; showing cached data. */
    Offline,

    /** Unknown or incompatible server version; writing is turned off (plan 3.6). */
    SafeMode,
}

/** Status chip for an [AppStatus] with its icon, tone and TalkBack description. */
@Composable
fun AppStatusChip(status: AppStatus, modifier: Modifier = Modifier) {
    val spec = status.spec()
    StatusChip(
        label = stringResource(spec.label),
        contentDescription = stringResource(spec.description),
        icon = spec.icon,
        tone = spec.tone,
        modifier = modifier,
    )
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
