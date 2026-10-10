package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemBrand
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.R

/** Number of numbered intro steps ("Steg 1 av 3"); the connected step is not counted. */
internal const val INTRO_STEP_COUNT = 3

/**
 * Top row of a step: back button (when [onBack] is set), the step pill and an optional end slot
 * (the language picker on the welcome step). All touch targets are at least 48 dp.
 */
@Composable
internal fun StepHeader(step: Int, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, end: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(TandemTheme.sizes.minTouchTarget)) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = TandemIcons.ArrowBack,
                        contentDescription = stringResource(DesignR.string.tandem_action_back),
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            StepIndicator(step = step)
        }
        Box(modifier = Modifier.width(TandemTheme.sizes.minTouchTarget), contentAlignment = Alignment.CenterEnd) {
            end?.invoke()
        }
    }
}

/** "Step 2 of 3" pill with dots; read by TalkBack as one text. */
@Composable
internal fun StepIndicator(step: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.onboarding_step_of, step, INTRO_STEP_COUNT)
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = TandemTheme.spacing.md, vertical = TandemTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs),
        ) {
            Text(text = description, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(TandemTheme.spacing.xxs))
            repeat(INTRO_STEP_COUNT) { index ->
                val active = index + 1 == step
                Box(
                    modifier = Modifier
                        .size(width = if (active) 14.dp else 6.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index + 1 <= step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }
    }
}

/** Title of a step, marked as heading for TalkBack. */
@Composable
internal fun StepTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
internal fun StepBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The app icon (cream pot on tomato), decorative: the app name is always next to it. */
@Composable
internal fun BrandLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(TandemBrand.Tomato),
    ) {
        Image(
            painter = painterResource(R.drawable.onboarding_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Small language picker for the corner of the welcome step (plan 6.7): System / English /
 * Svenska. The button shows a globe; its TalkBack label names the current choice.
 */
@Composable
internal fun LanguagePicker(current: AppLanguage, onSelect: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = languageName(current)
    val buttonDescription = stringResource(R.string.onboarding_language_button, currentName)
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = buttonDescription },
        ) {
            Icon(imageVector = TandemIcons.Language, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { language ->
                val isCurrent = language == current
                DropdownMenuItem(
                    text = {
                        Text(
                            text = languageName(language),
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (!isCurrent) onSelect(language)
                    },
                    modifier = Modifier.semantics { selected = isCurrent },
                    trailingIcon = if (isCurrent) {
                        { Icon(imageVector = TandemIcons.CheckCircle, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
internal fun languageName(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.onboarding_language_system
        AppLanguage.ENGLISH -> R.string.onboarding_language_english
        AppLanguage.SWEDISH -> R.string.onboarding_language_swedish
    },
)
