package casa.moller.tandem.wear.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.watchlink.MirroredTimer
import casa.moller.tandem.core.watchlink.MirroredTimerState
import casa.moller.tandem.wear.data.WearProblem
import casa.moller.tandem.wear.shopping.WearAisle
import casa.moller.tandem.wear.shopping.WearList
import casa.moller.tandem.wear.shopping.WearRow
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val milk = WearRow(listOf(1), "Milk", "3 l", 2, null, checked = false, pending = false)
private val bread = WearRow(listOf(3), "Rye bread", null, 1, "Friday tacos", checked = false, pending = false)
private val butter = WearRow(listOf(4), "Butter", "500 g", 1, null, checked = true, pending = true)

/** A list as the household has it on a Saturday: two aisles, one row checked offline. */
internal val SAMPLE_LIST = WearUiState(
    mode = WearMode.READY,
    list = WearList(
        aisles = listOf(WearAisle(1, "Dairy", listOf(milk)), WearAisle(2, "Bread", listOf(bread))),
        checked = listOf(butter),
    ),
    nowMillis = 1_000_000L,
    pending = 1,
    lastSyncMillis = 900_000L,
    firstSyncDone = true,
    storeName = "Ica",
    storeCount = 2,
    accountLabel = "Anna",
)

private val TIMERS = listOf(
    MirroredTimer(1, "Oven", "Lasagna", MirroredTimerState.RINGING, endsAtEpochMillis = 990_000L, durationMillis = 1),
    MirroredTimer(2, "Pasta", null, MirroredTimerState.RUNNING, endsAtEpochMillis = 1_245_000L, durationMillis = 1),
)

/**
 * Fas 5 (wear lane, plan §6.5–6.7 for the watch): the watch screen on a small round, a large round
 * and a square watch, dark (watches are always dark), the largest font size and Swedish; the
 * pairing states and the timers with notifications off; ATF on each (labels, 48 dp, contrast).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ShoppingScreenScreenshotTest(private val device: String) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun list() = shot("list", SAMPLE_LIST)

    @Test
    @Config(qualifiers = "+sv")
    fun listSwedish() = shot("list_sv", SAMPLE_LIST.copy(storeName = "Ica Kvantum"))

    @Test
    fun listLargestFont() = shot("list_font_scale", SAMPLE_LIST, fontScale = LARGEST_WEAR_FONT_SCALE)

    @Test
    fun timersAndOffline() = shot(
        "timers_offline",
        SAMPLE_LIST.copy(timers = TIMERS, problem = WearProblem.OFFLINE, askNotifications = true),
    )

    @Test
    @Config(qualifiers = "+sv")
    fun notPairedSwedish() = shot("not_paired_sv", WearUiState(mode = WearMode.NOT_PAIRED))

    @Test
    fun signedOut() = shot("signed_out", WearUiState(mode = WearMode.SIGNED_OUT, accountLabel = "Anna"))

    @Test
    fun empty() = shot("empty", SAMPLE_LIST.copy(list = WearList.EMPTY, pending = 0, storeCount = 1))

    private fun shot(name: String, state: WearUiState, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                WearTheme { ShoppingScreen(state = state, onAction = {}, onAllowNotifications = {}, showTime = false) }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/wear/${device}_$name.png")
    }

    private companion object {
        /** Wear OS's largest text size (Settings → Display → Text size). */
        const val LARGEST_WEAR_FONT_SCALE = 1.24f
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSSmallRound)
class ShoppingScreenSmallRoundScreenshotTest : ShoppingScreenScreenshotTest("small_round")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound)
class ShoppingScreenLargeRoundScreenshotTest : ShoppingScreenScreenshotTest("large_round")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSSquare)
class ShoppingScreenSquareScreenshotTest : ShoppingScreenScreenshotTest("square")

/** Fas 5 (wear lane): what a tap on the watch screen does. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound)
class ShoppingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aTapOnARowChecksAllItsEntries() {
        val actions = mutableListOf<WearAction>()
        composeRule.setContent {
            WearTheme { ShoppingScreen(SAMPLE_LIST, onAction = { actions += it }, onAllowNotifications = {}, showTime = false) }
        }

        composeRule.onNodeWithText("Milk").performClick()

        assertEquals(listOf<WearAction>(WearAction.Toggle(milk)), actions)
    }

    @Test
    fun stoppingTheAlarmIsOneTap() {
        val actions = mutableListOf<WearAction>()
        composeRule.setContent {
            WearTheme {
                ShoppingScreen(SAMPLE_LIST.copy(timers = TIMERS), onAction = { actions += it }, onAllowNotifications = {}, showTime = false)
            }
        }

        composeRule.onNodeWithText("Stop Oven").assertHasClickAction().performClick()

        assertEquals(listOf<WearAction>(WearAction.StopTimer(1)), actions)
    }
}
