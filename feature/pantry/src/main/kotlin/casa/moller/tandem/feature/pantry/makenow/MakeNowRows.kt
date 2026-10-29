package casa.moller.tandem.feature.pantry.makenow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.Cooking
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.pantry.ExpiryLabel
import casa.moller.tandem.core.ui.pantry.ExpiryUrgency
import casa.moller.tandem.core.ui.pantry.expiryText
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.recipe.IconLabel
import casa.moller.tandem.core.ui.recipe.RecipeImage
import casa.moller.tandem.core.ui.recipe.RecipeTimeLabel
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.pantry.R
import kotlinx.datetime.LocalDate

/** Test tag of a recipe's row (`make-now:row:7`). */
fun makeNowRowTag(recipeId: Long): String = "make-now:row:$recipeId"

/** Test tag of a row's "Lägg det som saknas på listan". */
fun makeNowAddMissingTag(recipeId: Long): String = "make-now:add-missing:$recipeId"

private val Thumbnail = 64.dp

/**
 * One recipe of "Kan laga nu": picture, name and time, what is missing ("Saknas: tahini, citron")
 * or "Har allt hemma", the soon-expiring foods it uses up, and the actions "Laga" and (members)
 * "Lägg det som saknas på listan". The recipe part is one TalkBack item that opens the recipe. The
 * missing foods are asked for when the row is first shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MakeNowRecipeRow(
    row: MakeNowRow,
    today: LocalDate?,
    canAddToList: Boolean,
    onAction: (MakeNowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val recipe = row.recipe
    val currentOnAction by rememberUpdatedState(onAction)
    val unknown = row.missing == null
    LaunchedEffect(recipe.id, unknown) {
        if (unknown) currentOnAction(MakeNowAction.RowShown(recipe.id))
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().testTag(makeNowRowTag(recipe.id)),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.xs)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = { onAction(MakeNowAction.OpenRecipe(recipe.id)) })
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                RecipeImage(
                    imageUrl = recipe.imageUrl,
                    placeholderIconSize = TandemTheme.sizes.icon,
                    modifier = Modifier
                        .size(Thumbnail)
                        .clip(MaterialTheme.shapes.medium),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                    Text(text = recipe.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    RecipeTimeLabel(recipe.totalMinutes)
                    MissingLine(missing = row.missing)
                    if (today != null && row.expiring.isNotEmpty()) {
                        val locale = currentLocale()
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                        ) {
                            row.expiring.take(MAX_EXPIRING).forEach { food ->
                                ExpiryLabel(
                                    text = stringResource(
                                        R.string.pantry_make_now_uses_up,
                                        foodTitle(food.name, locale),
                                        expiryText(food.expires, today),
                                    ),
                                    urgency = ExpiryUrgency.of(food.expires, today),
                                )
                            }
                        }
                    }
                }
            }
            FlowRow(modifier = Modifier.padding(horizontal = spacing.xs), horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TandemTextButton(
                    text = stringResource(R.string.pantry_make_now_cook),
                    onClick = { onAction(MakeNowAction.StartCooking(recipe.id)) },
                    leadingIcon = TandemIcons.Cooking,
                    modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
                )
                if (canAddToList && !row.missing.isNullOrEmpty()) {
                    TandemTextButton(
                        text = stringResource(R.string.pantry_make_now_add_missing),
                        onClick = { onAction(MakeNowAction.AddMissingToList(recipe.id)) },
                        leadingIcon = TandemIcons.ShoppingCart,
                        modifier = Modifier
                            .heightIn(min = TandemTheme.sizes.minTouchTarget)
                            .testTag(makeNowAddMissingTag(recipe.id)),
                    )
                }
            }
        }
    }
}

private const val MAX_EXPIRING = 2

/** "Har allt hemma", "Saknas: tahini, citron" or, while it is worked out, "Kollar vad som saknas …". */
@Composable
private fun MissingLine(missing: List<casa.moller.tandem.core.model.makenow.MissingFood>?) {
    when {
        missing == null -> Text(
            text = stringResource(R.string.pantry_make_now_checking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        missing.isEmpty() -> IconLabel(
            icon = TandemIcons.CheckCircle,
            text = stringResource(R.string.pantry_make_now_has_everything),
            iconTint = TandemTheme.extendedColors.success,
        )

        else -> {
            val locale = currentLocale()
            Text(
                text = stringResource(R.string.pantry_make_now_missing, missing.joinToString(", ") { it.name.lowercase(locale) }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** The keyword filter: every stored keyword with a checkbox, "Klar" below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeywordSheet(state: MakeNowUiState, onAction: (MakeNowAction) -> Unit) {
    val spacing = TandemTheme.spacing
    ModalBottomSheet(onDismissRequest = { onAction(MakeNowAction.CloseKeywords) }) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal).padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.pantry_make_now_keywords),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(state.keywords, key = { it.id }) { keyword ->
                    val chosen = keyword.id in state.filter.keywordIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TandemTheme.sizes.minTouchTarget)
                            .toggleable(
                                value = chosen,
                                role = Role.Checkbox,
                                onValueChange = { onAction(MakeNowAction.ToggleKeyword(keyword.id)) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        Checkbox(checked = chosen, onCheckedChange = null)
                        Text(text = keyword.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            TandemPrimaryButton(
                text = stringResource(R.string.pantry_make_now_keywords_done),
                onClick = { onAction(MakeNowAction.CloseKeywords) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
