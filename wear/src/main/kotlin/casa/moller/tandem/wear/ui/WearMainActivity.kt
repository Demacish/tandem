package casa.moller.tandem.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import casa.moller.tandem.wear.TandemWearApplication

/** The watch app's one activity: [ShoppingScreen] with its [WearViewModel] from the [casa.moller.tandem.wear.WearGraph]. */
class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as TandemWearApplication).graph
        val factory = viewModelFactory {
            initializer {
                WearViewModel(
                    store = graph.store,
                    sync = graph.sync,
                    scheduler = graph.scheduler,
                    timers = graph.timers,
                    clock = graph.clock,
                    notificationsAllowed = ::notificationsAllowed,
                )
            }
        }
        setContent {
            val viewModel: WearViewModel = viewModel(factory = factory)
            val state by viewModel.state.collectAsStateWithLifecycle()
            // POST_NOTIFICATIONS in context: only from the button next to the phone's timers.
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                viewModel.onAction(WearAction.NotificationsChanged)
            }
            LifecycleStartEffect(viewModel) {
                viewModel.onStart()
                onStopOrDispose { }
            }
            WearTheme {
                ShoppingScreen(
                    state = state,
                    onAction = viewModel::onAction,
                    onAllowNotifications = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
        }
    }

    private fun notificationsAllowed(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(this).areNotificationsEnabled()
}
