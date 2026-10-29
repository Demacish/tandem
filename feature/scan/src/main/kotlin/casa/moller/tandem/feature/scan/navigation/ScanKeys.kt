package casa.moller.tandem.feature.scan.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import kotlinx.serialization.Serializable

// Fas S (grund lane): the scanning destinations (plan §8.8 "Skanning"). The camera screens and the
// review are full screen on every width. [locationId] preselects where the items go (opened from a
// location in "Hemma"); null = the review's default location.

/** Every destination of feature:scan (the shell shows them through one route). */
sealed interface ScanDestination : NavKey

/** Barcode (#27): a known code goes straight to its food, an unknown one to Open Food Facts. */
@Serializable
data class BarcodeScanKey(val locationId: Long? = null) :
    ScanDestination,
    FullScreenDestination

/** Receipt (#44): text recognition on the device, lines matched to foods. */
@Serializable
data class ReceiptScanKey(val locationId: Long? = null) :
    ScanDestination,
    FullScreenDestination

/** Fridge photo (#45, EXPERIMENTAL, B22): the user's own AI key; hidden without one. */
@Serializable
data class FridgeScanKey(val locationId: Long? = null) :
    ScanDestination,
    FullScreenDestination

/**
 * The shared review list. [sessionId] names the in-memory review the scan screen filled (held by
 * this module); after process death it is gone and the screen offers to scan again.
 */
@Serializable
data class ScanReviewKey(val sessionId: String) :
    ScanDestination,
    FullScreenDestination

/**
 * Settings → AI (B22): provider (Claude, Gemini, OpenAI), model, optional own base URL, the key
 * (a password field, write-only) and the experimental warning. Opened from Mer (a page beside the
 * More list on wide windows) and from the fridge photo when no key is stored.
 */
@Serializable
data object AiSettingsKey : ScanDestination
