package casa.moller.tandem.core.designsystem.component

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.R
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TandemTextFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun passwordToggleSwitchesBetweenShowAndHide() {
        composeRule.setContent {
            TandemTheme {
                var value by remember { mutableStateOf("secret-value") }
                TandemTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = "label",
                    type = TandemTextFieldType.Password,
                )
            }
        }
        // The masking itself is visual only (semantics keep the raw value), so this checks the
        // toggle contract that TalkBack users rely on: "Show password" <-> "Hide password".
        val show = context.getString(R.string.tandem_password_show)
        val hide = context.getString(R.string.tandem_password_hide)
        composeRule.onNodeWithContentDescription(hide).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(show).performClick()
        composeRule.onNodeWithContentDescription(hide).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(show).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(hide).performClick()
        composeRule.onNodeWithContentDescription(show).assertIsDisplayed()
    }

    @Test
    fun plainTextFieldHasNoVisibilityToggle() {
        composeRule.setContent {
            TandemTheme {
                TandemTextField(value = "", onValueChange = {}, label = "label", type = TandemTextFieldType.Url)
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.tandem_password_show))
            .assertDoesNotExist()
    }
}
