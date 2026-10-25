package casa.moller.tandem.feature.mealplan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.navigation.AppChromeDemoBanner
import casa.moller.tandem.core.ui.navigation.AppChromeStatusChip

/**
 * Phase 2 (grund lane): what a route shows until its lane builds the real screen: the top bar
 * with the app's status chip, the demo banner and an empty state. Removed by the lane.
 */
@Composable
internal fun MealPlanPlaceholderScreen(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TandemTopAppBar(title = title, onNavigateBack = onNavigateBack, actions = { AppChromeStatusChip() }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AppChromeDemoBanner()
            EmptyState(icon = icon, title = title, body = body, modifier = Modifier.weight(1f))
        }
    }
}
