package casa.moller.tandem.feature.pantry.reminder

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.NotificationsActive
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.feature.pantry.R

/** The card's test tag. */
const val PANTRY_EXPIRY_REMINDER_TAG: String = "pantry:expiry-reminder"

/**
 * Fas 4 (skafferinotis lane): Hemma's offer "Påminn mig när något går ut?" with "Slå på" and "Nej
 * tack" (plan §8.8 "Använd upp", §8.7: the permission is asked in context, here). When the setting
 * is on but notifications are not allowed, the same card says so and asks again, or points to the
 * system's settings once the system no longer shows a dialog. Its heading is a TalkBack heading
 * (CalloutCard); the buttons are 48 dp targets and wrap at large font sizes.
 */
@Composable
fun ExpiryReminderCard(offer: ExpiryReminderOffer, onAction: (ExpiryReminderAction) -> Unit, modifier: Modifier = Modifier) {
    val blocked = offer.blocked
    CalloutCard(
        title = stringResource(if (blocked) R.string.pantry_expiry_reminder_blocked_title else R.string.pantry_expiry_reminder_title),
        body = stringResource(
            when {
                offer.openSettings -> R.string.pantry_expiry_reminder_settings_body
                blocked -> R.string.pantry_expiry_reminder_blocked_body
                else -> R.string.pantry_expiry_reminder_body
            },
        ),
        icon = TandemIcons.NotificationsActive,
        tone = if (blocked) TandemTone.Warning else TandemTone.Neutral,
        modifier = modifier.testTag(PANTRY_EXPIRY_REMINDER_TAG),
        actions = {
            TandemPrimaryButton(
                text = stringResource(
                    when {
                        offer.openSettings -> R.string.pantry_expiry_reminder_open_settings
                        blocked -> R.string.pantry_expiry_reminder_ask_again
                        else -> R.string.pantry_expiry_reminder_enable
                    },
                ),
                onClick = { onAction(if (offer.openSettings) ExpiryReminderAction.OpenSettings else ExpiryReminderAction.Enable) },
            )
            TandemTextButton(
                text = stringResource(R.string.pantry_expiry_reminder_not_now),
                onClick = { onAction(ExpiryReminderAction.Dismiss) },
            )
        },
    )
}
