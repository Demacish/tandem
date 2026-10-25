package casa.moller.tandem.core.designsystem.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Button heights. [Medium] (56 dp) is the default: large touch targets for the kitchen
 * (plan 6.6). [Small] (40 dp visual) still gets a 48 dp touch target from Material. [Kitchen]
 * (64 dp, `TandemSizes.kitchenTouchTarget`) is for every button in cook mode (plan 6.2/6.6: wet
 * hands), also in its warnings and dialogs.
 */
enum class TandemButtonSize {
    Small,
    Medium,
    Kitchen,
    ;

    internal val height: Dp
        get() = when (this) {
            Small -> ButtonDefaults.MinHeight
            Medium -> ButtonDefaults.MediumContainerHeight
            Kitchen -> KitchenButtonHeight
        }
}

/** Same as `TandemSizes.kitchenTouchTarget` (plan 6.6). */
private val KitchenButtonHeight: Dp = 64.dp

/**
 * Main call to action: tomato pill ("Laga mat", "Anslut", "Logga in"). Use at most one per
 * screen section. Shape morphs when pressed (M3 Expressive).
 */
@Composable
fun TandemPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: TandemButtonSize = TandemButtonSize.Medium,
) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(size.height),
        modifier = modifier.heightIn(min = size.height),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(),
        contentPadding = ButtonDefaults.contentPaddingFor(
            buttonHeight = size.height,
            hasStartIcon = leadingIcon != null,
            hasEndIcon = trailingIcon != null,
        ),
    ) {
        ButtonContent(text, leadingIcon, trailingIcon, size)
    }
}

/**
 * Secondary action next to or below a primary one: neutral sand pill ("Visa varor",
 * "Testa med demoläget i stället").
 */
@Composable
fun TandemSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: TandemButtonSize = TandemButtonSize.Medium,
) {
    TonalButtonImpl(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        size = size,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * Tonal action with a saffron tint: secondary emphasis that should still stand out, e.g. on a
 * neutral card.
 */
@Composable
fun TandemTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: TandemButtonSize = TandemButtonSize.Medium,
) {
    TonalButtonImpl(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        size = size,
        colors = ButtonDefaults.filledTonalButtonColors(),
    )
}

/** Low-emphasis action: links such as "Läs anslutningsguiden" or "Fortsätt testa". */
@Composable
fun TandemTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    size: TandemButtonSize = TandemButtonSize.Small,
) {
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(size.height),
        modifier = modifier.heightIn(min = size.height),
        enabled = enabled,
        contentPadding = ButtonDefaults.contentPaddingFor(
            buttonHeight = size.height,
            hasStartIcon = leadingIcon != null,
            hasEndIcon = trailingIcon != null,
        ),
    ) {
        ButtonContent(text, leadingIcon, trailingIcon, size)
    }
}

@Composable
private fun TonalButtonImpl(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    leadingIcon: ImageVector?,
    trailingIcon: ImageVector?,
    size: TandemButtonSize,
    colors: ButtonColors,
) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(size.height),
        modifier = modifier.heightIn(min = size.height),
        enabled = enabled,
        colors = colors,
        contentPadding = ButtonDefaults.contentPaddingFor(
            buttonHeight = size.height,
            hasStartIcon = leadingIcon != null,
            hasEndIcon = trailingIcon != null,
        ),
    ) {
        ButtonContent(text, leadingIcon, trailingIcon, size)
    }
}

@Composable
private fun ButtonContent(text: String, leadingIcon: ImageVector?, trailingIcon: ImageVector?, size: TandemButtonSize) {
    val iconSize = ButtonDefaults.iconSizeFor(size.height)
    val spacing = ButtonDefaults.iconSpacingFor(size.height)
    if (leadingIcon != null) {
        // Decorative: the button text already says what it does.
        Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(spacing))
    }
    Text(
        text = text,
        style = ButtonDefaults.textStyleFor(size.height),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (trailingIcon != null) {
        Spacer(Modifier.width(spacing))
        Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(iconSize))
    }
}
