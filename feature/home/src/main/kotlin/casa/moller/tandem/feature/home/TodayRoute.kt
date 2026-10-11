package casa.moller.tandem.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip

/**
 * The Today tab (plan 6.2 "Idag"): tonight and tomorrow from the meal plan, the carousels, and
 * the empty state "Nothing planned today. Plan the week →".
 *
 * Navigation contract (docs/BYGGE.md "Navigering"):
 * @param onOpenRecipe opens a recipe's detail (full screen on phones, beside the rail on tablets).
 * @param onOpenRecipes switches to the Recipes tab (e.g. "All recipes" after a carousel).
 * @param onOpenWeek switches to the Week tab ("Plan the week", Phase 2 content).
 *
 * PLACEHOLDER (grund lane): the UI lane replaces the body (ViewModel on core:data's
 * HomeRepository) and keeps this signature. Phase 1b adds `onStartCooking`.
 */
@Composable
fun TodayRoute(onOpenRecipe: (recipeId: Long) -> Unit, onOpenRecipes: () -> Unit, onOpenWeek: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.home_title),
                actions = { AppChromeStatusChip(Modifier.padding(end = spacing.md)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppChromeDemoBanner(
                Modifier
                    .padding(top = spacing.sm)
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth),
            )
            EmptyState(
                icon = TandemIcons.Today,
                title = stringResource(R.string.home_placeholder_title),
                body = stringResource(R.string.home_placeholder_body),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
