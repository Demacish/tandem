package casa.moller.tandem.core.designsystem.component

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import casa.moller.tandem.core.designsystem.R
import casa.moller.tandem.core.designsystem.icon.TandemIcons

/** Input kind of a [TandemTextField]; sets keyboard, autocorrect and visibility toggle. */
enum class TandemTextFieldType {
    /** Free text with autocorrect and sentence capitalisation. */
    Text,

    /** Single-word identifiers such as a user name: no autocorrect, no capitalisation. */
    Username,

    /** Server address: URI keyboard, no autocorrect, no capitalisation. */
    Url,

    /** Password or API token: hidden by default, with a show/hide button. Never autocorrected. */
    Password,
}

/**
 * Outlined text field in Tandem style (rounded, white/espresso fill, label above).
 *
 * @param label visible label; also the accessibility name of the field.
 * @param supportingText help or error text below the field. Shown in error colour when
 *   [isError] is true, so errors are never shown by colour alone.
 * @param leadingIcon decorative icon. Defaults to a server icon for [TandemTextFieldType.Url] and
 *   a lock for [TandemTextFieldType.Password]; pass e.g. [TandemIcons.Key] for an API token.
 */
@Composable
fun TandemTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    type: TandemTextFieldType = TandemTextFieldType.Text,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = type.defaultLeadingIcon(),
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val isPassword = type == TandemTextFieldType.Password
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null) } },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            TandemIcons.VisibilityOff
                        } else {
                            TandemIcons.Visibility
                        },
                        contentDescription = stringResource(
                            if (passwordVisible) {
                                R.string.tandem_password_hide
                            } else {
                                R.string.tandem_password_show
                            },
                        ),
                    )
                }
            }
        } else {
            null
        },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = type.keyboardOptions(imeAction),
        keyboardActions = keyboardActions,
        singleLine = type != TandemTextFieldType.Text,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun TandemTextFieldType.defaultLeadingIcon(): ImageVector? = when (this) {
    TandemTextFieldType.Url -> TandemIcons.Dns
    TandemTextFieldType.Password -> TandemIcons.Lock
    TandemTextFieldType.Text, TandemTextFieldType.Username -> null
}

private fun TandemTextFieldType.keyboardOptions(imeAction: ImeAction): KeyboardOptions = when (this) {
    TandemTextFieldType.Text -> KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        keyboardType = KeyboardType.Text,
        imeAction = imeAction,
    )

    TandemTextFieldType.Username -> KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
        imeAction = imeAction,
    )

    TandemTextFieldType.Url -> KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri,
        imeAction = imeAction,
    )

    TandemTextFieldType.Password -> KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
        imeAction = imeAction,
    )
}
