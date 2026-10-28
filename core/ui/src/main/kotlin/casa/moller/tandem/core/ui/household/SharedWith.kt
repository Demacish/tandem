package casa.moller.tandem.core.ui.household

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.R

/** Test tag of [SharedWithRow]. */
const val SHARED_WITH_TAG: String = "household:shared-with"

/**
 * 0.3.0 komplettering (plan 8.6 "Delad med: …"): one line at the top of Handla and Veckan with
 * the household's other members, who see the same list and meal plan ("Delad med: Anna och
 * Erik"; from three names "Anna, Erik och 2 till"). Nothing when [names] is empty. One node for
 * TalkBack (the icon is decorative).
 */
@Composable
fun SharedWithRow(names: List<String>, modifier: Modifier = Modifier) {
    if (names.isEmpty()) return
    val text = when (names.size) {
        1 -> stringResource(R.string.ui_shared_with_one, names[0])
        2 -> stringResource(R.string.ui_shared_with_two, names[0], names[1])
        else -> pluralStringResource(R.plurals.ui_shared_with_many, names.size - 2, names[0], names[1], names.size - 2)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(SHARED_WITH_TAG),
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TandemIcons.HomeFilled,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TandemTheme.sizes.icon),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
