package casa.moller.tandem.core.ui.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.preview.ThemePreviews
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.ui.recipe.IngredientHeader
import casa.moller.tandem.core.ui.recipe.IngredientRow
import casa.moller.tandem.core.ui.recipe.KeywordPill
import casa.moller.tandem.core.ui.recipe.MarkdownText
import casa.moller.tandem.core.ui.recipe.NotDownloadedBadge
import casa.moller.tandem.core.ui.recipe.OfflineBadge
import casa.moller.tandem.core.ui.recipe.RatingStars
import casa.moller.tandem.core.ui.recipe.RecipeCard
import casa.moller.tandem.core.ui.recipe.RecipeListItem
import casa.moller.tandem.core.ui.recipe.RecipeRatingLabel
import casa.moller.tandem.core.ui.recipe.RecipeTimeLabel
import casa.moller.tandem.core.ui.recipe.ServingsStepper
import casa.moller.tandem.core.ui.recipe.StepCard

/*
 * Samples of the recipe components for @Preview and the screenshot tests. Debug source set only;
 * the recipe content (names, amounts) stands for what a Tandoor server sends.
 */

private val pasta = RecipeOverview(
    id = 1,
    name = "Krämig svamppasta med timjan och parmesan",
    workingMinutes = 20,
    waitingMinutes = 5,
    rating = 4.5,
    keywords = listOf(Keyword(1, "Vegetariskt"), Keyword(2, "Vardag")),
    availableOffline = true,
)

private val soup = RecipeOverview(
    id = 2,
    name = "Ärtsoppa",
    workingMinutes = 30,
    waitingMinutes = 120,
    rating = 4.0,
    keywords = listOf(Keyword(3, "Soppa")),
)

private val untimed = soup.copy(id = 3, name = "Ölbröd", rating = null, workingMinutes = 0, waitingMinutes = 0, keywords = emptyList())

/** Grid cards: downloaded, plain, dimmed (offline, not downloaded) and selected. */
@Composable
fun RecipeCardsSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RecipeCard(recipe = pasta, onClick = {}, modifier = Modifier.width(170.dp))
        RecipeCard(recipe = soup, onClick = {}, dimmed = true, modifier = Modifier.width(170.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RecipeCard(recipe = soup, onClick = {}, selected = true, modifier = Modifier.width(170.dp))
        RecipeCard(recipe = untimed, onClick = {}, modifier = Modifier.width(170.dp))
    }
}

/** List rows: downloaded, dimmed and selected. */
@Composable
fun RecipeListItemsSample() {
    RecipeListItem(recipe = pasta, onClick = {})
    RecipeListItem(recipe = soup, onClick = {}, dimmed = true)
    RecipeListItem(recipe = soup, onClick = {}, selected = true)
}

/** Ratings, times, badges and keyword pills. */
@Composable
fun RecipeMetaSample() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RatingStars(rating = 3.5)
        RatingStars(rating = null)
        RecipeRatingLabel(rating = 4.5)
        RecipeTimeLabel(totalMinutes = 25)
        RecipeTimeLabel(totalMinutes = 150)
        OfflineBadge()
        NotDownloadedBadge()
        KeywordPill("Vegetariskt")
    }
}

/** Servings stepper and ingredient rows (struck, with a unit switch, header, no amount). */
@Composable
fun IngredientsSample() {
    var struck by remember { mutableStateOf(setOf(2)) }
    ServingsStepper(
        label = "PORTIONER",
        valueText = "6 portioner",
        amountText = "6",
        onDecrease = {},
        onIncrease = {},
        onReset = {},
        resetText = "Återställ till 4",
    )
    Column {
        IngredientHeader("Svampen")
        IngredientRow(amountText = "600 g", name = "champinjoner", struck = 1 in struck, onToggle = { struck = struck xor 1 })
        IngredientRow(amountText = "2 ¼ msk", name = "smör", struck = 2 in struck, onToggle = { struck = struck xor 2 })
        IngredientRow(amountText = "3 st", name = "vitlöksklyftor", note = "pressade", struck = false, onToggle = {}, onAmountClick = {})
        IngredientRow(amountText = null, name = "salt", struck = false, onToggle = {})
    }
}

/** A step with a title, time, ingredients and Markdown text. */
@Composable
fun StepCardSample() {
    StepCard(
        number = 2,
        title = "Pastan",
        minutes = 10,
        ingredients = { IngredientRow(amountText = "400 g", name = "pasta", struck = false, onToggle = {}) },
    ) {
        MarkdownText("Koka pastan enligt förpackningen, **8–10 minuter**.\nSpara *½ dl* pastavatten.\n\n- Häll av\n- Vänd ner i såsen")
    }
    StepCard(number = 3, title = null) {
        Text("Servera genast.")
    }
}

private infix fun Set<Int>.xor(value: Int): Set<Int> = if (value in this) this - value else this + value

@ThemePreviews
@Composable
private fun RecipeCardsPreview() = UiSampleSurface { RecipeCardsSample() }

@ThemePreviews
@Composable
private fun IngredientsPreview() = UiSampleSurface { IngredientsSample() }

@ThemePreviews
@Composable
private fun StepCardPreview() = UiSampleSurface { StepCardSample() }
