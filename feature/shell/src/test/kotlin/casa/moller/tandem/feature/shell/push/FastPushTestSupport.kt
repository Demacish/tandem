package casa.moller.tandem.feature.shell.push

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.data.push.FastPushRepository
import casa.moller.tandem.core.model.push.FastPushAvailability
import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushResult
import casa.moller.tandem.core.model.push.FastPushServiceInfo
import casa.moller.tandem.core.model.push.FastPushState
import casa.moller.tandem.core.model.push.FastPushStatus
import casa.moller.tandem.core.model.push.PushDistributor
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/** A [FastPushRepository] whose answers the test sets; records what it was asked. */
internal class FakeFastPushRepository(
    initial: FastPushState = FastPushState(availability = FastPushAvailability.AVAILABLE),
    var installed: List<PushDistributor> = listOf(NTFY),
) : FastPushRepository {
    override val state = MutableStateFlow(initial)
    var enableResult: FastPushResult<Unit> = FastPushResult.Success(Unit)
    var testResult: FastPushResult<Unit> = FastPushResult.Success(Unit)
    val enabled = mutableListOf<Pair<String, String>>()
    var disabled = 0
    var tests = 0

    override fun distributors(): List<PushDistributor> = installed

    override suspend fun check(serviceUrl: String): FastPushResult<FastPushServiceInfo> =
        FastPushResult.Success(FastPushServiceInfo("1.0.0", testSupported = true, pollSeconds = 45))

    override suspend fun enable(serviceUrl: String, distributor: String): FastPushResult<Unit> {
        enabled += serviceUrl to distributor
        if (enableResult is FastPushResult.Success) {
            state.value = state.value.copy(serviceUrl = serviceUrl, distributor = distributor, status = FastPushStatus.REGISTERING)
        }
        return enableResult
    }

    override suspend fun disable(): FastPushResult<Unit> {
        disabled++
        state.value = state.value.copy(status = FastPushStatus.OFF, error = null)
        return FastPushResult.Success(Unit)
    }

    override suspend fun sendTest(): FastPushResult<Unit> {
        tests++
        return testResult
    }
}

internal val NTFY = PushDistributor("io.heckel.ntfy", "ntfy")
internal val NEXTPUSH = PushDistributor("org.unifiedpush.distributor.nextpush", "NextPush")
private const val SERVICE = "https://push.example.com"

/** Every state the screen can show, for the screenshots and the ATF checks. */
internal val FAST_PUSH_OFF = FastPushUiState(
    loading = false,
    availability = FastPushAvailability.AVAILABLE,
    distributors = listOf(NTFY, NEXTPUSH),
    selectedDistributor = NTFY.packageName,
    listNotificationsOn = true,
)
internal val FAST_PUSH_OFF_FILLED = FAST_PUSH_OFF.copy(url = SERVICE)
internal val FAST_PUSH_NO_DISTRIBUTOR = FAST_PUSH_OFF.copy(distributors = emptyList(), selectedDistributor = null)
internal val FAST_PUSH_INVALID_URL = FAST_PUSH_OFF.copy(url = "http://push.example.com", urlInvalid = true)
internal val FAST_PUSH_REGISTERING = FAST_PUSH_OFF_FILLED.copy(status = FastPushStatus.REGISTERING, busy = true)
internal val FAST_PUSH_ACTIVE = FAST_PUSH_OFF_FILLED.copy(
    status = FastPushStatus.ACTIVE,
    testSupported = true,
    pollSeconds = 45,
    lastWakeUp = Instant.parse("2026-11-05T09:41:00Z"),
)
internal val FAST_PUSH_ACTIVE_BLOCKED = FAST_PUSH_ACTIVE.copy(notificationsAllowed = false)
internal val FAST_PUSH_FAILED = FAST_PUSH_OFF_FILLED.copy(status = FastPushStatus.FAILED, error = FastPushError.OTHER_HOUSEHOLD)
internal val FAST_PUSH_DEMO = FastPushUiState(loading = false, availability = FastPushAvailability.DEMO)
internal val FAST_PUSH_GUEST = FastPushUiState(loading = false, availability = FastPushAvailability.GUEST)

/** The page with a fixed state (no Hilt); actions are recorded. */
internal class FixedFastPushPage(private val state: FastPushUiState = FAST_PUSH_ACTIVE) : FastPushPage {
    val actions = mutableListOf<FastPushAction>()

    @Composable
    override fun FastPush(onNavigateBack: () -> Unit, onOpenPermissions: () -> Unit) {
        FastPushScreen(state = state, onAction = { actions += it }, onNavigateBack = onNavigateBack, onOpenPermissions = onOpenPermissions)
    }
}
