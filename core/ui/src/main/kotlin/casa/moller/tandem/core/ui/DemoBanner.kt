package casa.moller.tandem.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons

/**
 * Banner shown while the app runs on demo data ("Demoläge – Anslut din Tandoor", plan 4.5 and
 * 6.2). Saffron, with a science icon and a clear way out of demo mode.
 *
 * @param onConnect starts the server step of the intro.
 * @param onDismiss hides the banner for now; the Demo status chip stays visible.
 */
@Composable
fun DemoBanner(onConnect: () -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    CalloutCard(
        title = stringResource(R.string.ui_demo_banner_title),
        body = stringResource(R.string.ui_demo_banner_body),
        modifier = modifier,
        icon = TandemIcons.Science,
        tone = TandemTone.Warning,
        onDismiss = onDismiss,
        actions = {
            TandemPrimaryButton(
                text = stringResource(R.string.ui_demo_banner_connect),
                onClick = onConnect,
                trailingIcon = TandemIcons.ArrowForward,
                size = TandemButtonSize.Small,
            )
        },
    )
}

/**
 * "Har du ingen Tandoor?" callout for the server step of the intro (design
 * tandem_anslut_till_din_tandoor): points people without a server to demo mode or to Tandoor's
 * own documentation.
 *
 * @param onLearnMore opens Tandoor's website in the browser (the caller owns the URL).
 */
@Composable
fun NoTandoorCallout(onTryDemo: () -> Unit, onLearnMore: () -> Unit, modifier: Modifier = Modifier) {
    CalloutCard(
        title = stringResource(R.string.ui_no_tandoor_title),
        body = stringResource(R.string.ui_no_tandoor_body),
        modifier = modifier,
        icon = TandemIcons.Dns,
        tone = TandemTone.Neutral,
        actions = {
            TandemPrimaryButton(
                text = stringResource(R.string.ui_no_tandoor_try_demo),
                onClick = onTryDemo,
                leadingIcon = TandemIcons.Science,
                size = TandemButtonSize.Small,
            )
            TandemTextButton(
                text = stringResource(R.string.ui_no_tandoor_learn_more),
                onClick = onLearnMore,
                trailingIcon = TandemIcons.OpenInNew,
            )
        },
    )
}
