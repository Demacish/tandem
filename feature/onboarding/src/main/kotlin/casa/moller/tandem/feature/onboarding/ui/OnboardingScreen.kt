package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.AdaptiveContentCard
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.OnboardingActions
import casa.moller.tandem.feature.onboarding.OnboardingStep
import casa.moller.tandem.feature.onboarding.OnboardingUiState
import casa.moller.tandem.feature.onboarding.R

/** Material 3 expanded width class (plan 6.5): from here the brand panel sits beside the card. */
private val ExpandedWidth = 840.dp

/**
 * Stateless intro screen. Layout follows plan 6.5: one column on phones (compact), a centred
 * card of at most 480 dp from medium width ([AdaptiveContentCard]), and from expanded width a
 * brand panel beside it. Edge to edge: content stays inside the safe drawing insets (status and
 * navigation bars, cutouts, keyboard).
 */
@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    crashNotice: @Composable () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.safeDrawingPadding()) {
            val expanded = maxWidth >= ExpandedWidth
            Row(modifier = Modifier.fillMaxSize()) {
                if (expanded) {
                    BrandPanel(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(TandemTheme.spacing.xl),
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = state.step,
                        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
                        label = "onboarding-step",
                    ) { step ->
                        AdaptiveContentCard {
                            when (step) {
                                OnboardingStep.WELCOME -> WelcomeStep(
                                    language = language,
                                    onLanguageSelected = onLanguageSelected,
                                    onGetStarted = actions::onGetStarted,
                                    onStartDemo = actions::onStartDemo,
                                    // The brand panel already shows the logo on wide screens.
                                    showLogo = !expanded,
                                    crashNotice = crashNotice,
                                )

                                OnboardingStep.SERVER -> ServerStep(state, actions)

                                OnboardingStep.LOGIN -> LoginStep(state, actions)

                                OnboardingStep.CONNECTED -> state.connected?.let { ConnectedStep(it, onFinished) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Logo, name and pitch beside the card on expanded widths (tablet landscape, plan 6.2). */
@Composable
private fun BrandPanel(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TandemTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xl, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(size = 160.dp)
            Text(
                text = stringResource(R.string.onboarding_brand_name),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_body),
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxContentWidth)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
