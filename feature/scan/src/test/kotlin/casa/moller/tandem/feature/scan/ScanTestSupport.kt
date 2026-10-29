package casa.moller.tandem.feature.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.scan.FoodMatchVia
import casa.moller.tandem.core.model.scan.MatchedFood
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.testing.data.SamplePantry
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** Device configurations of the screenshot and accessibility tests (plan 6.5). */
internal object Devices {
    const val PHONE = "w412dp-h915dp-port-xhdpi"
    const val PHONE_TALL = "w412dp-h1800dp-port-xhdpi"
    const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
    const val TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"
    const val TABLET = "w1280dp-h800dp-land-mdpi"
}

/**
 * The scanners' test data: the space's foods (SYNTHETIC; the learned code and receipt name are
 * [SamplePantry.aliases]), a known code, an Open Food Facts product ([casa.moller.tandem.core.testing.data.FakeBarcodeLookup])
 * and an unknown code; every code is invented (the 200 prefix is GS1's in-store range).
 */
internal object ScanSamples {
    val today: LocalDate = SamplePantry.TODAY

    /** 2026-10-29 12:00 UTC (the same day in every time zone the tests run in). */
    val now: Instant = Instant.parse("2026-10-29T12:00:00Z")

    val cream = Food(101, "grädde")
    val whippingCream = Food(102, "vispgrädde")
    val milk = Food(103, "mjölk")
    val pasta = Food(104, "pasta")
    val chickpeas = Food(110, "kikärtor")
    val banana = Food(111, "banan", pluralName = "bananer")
    val cucumber = Food(112, "gurka")
    val coffee = Food(113, "kaffe")
    val foods = listOf(cream, whippingCream, milk, pasta, chickpeas, banana, cucumber, coffee)

    /** Learned as a FOOD_ALIAS (kikärtor). */
    const val KNOWN_CODE = "2000000000015"

    /** Open Food Facts knows it: "Vispgrädde 36%". */
    const val PRODUCT_CODE = "2000000000022"

    /** Nobody knows it. */
    const val UNKNOWN_CODE = "2000000000039"

    fun receiptRows(): List<ScanReviewItem> = listOf(
        ScanReviewItem(
            key = "r1",
            text = "GRÄDDE 36% 5DL",
            source = ScanSource.RECEIPT,
            food = MatchedFood(101, "grädde", FoodMatchVia.ALIAS),
            amount = 5.0,
            unit = "dl",
            locationId = 1,
            expires = LocalDate(2026, 11, 5),
        ),
        ScanReviewItem(
            key = "r2",
            text = "Bananer Eko",
            source = ScanSource.RECEIPT,
            food = MatchedFood(111, "banan", FoodMatchVia.SEARCH),
            amount = 1.234,
            unit = "kg",
            learn = true,
        ),
        ScanReviewItem(key = "r3", text = "TVÄTTMEDEL COLOR 1,5L", source = ScanSource.RECEIPT, amount = 1.5, unit = "l", learn = true),
        ScanReviewItem(
            key = "r4",
            text = "Kaffe Mellanrost 450g",
            source = ScanSource.RECEIPT,
            food = MatchedFood(113, "kaffe", FoodMatchVia.MANUAL),
            amount = 900.0,
            unit = "g",
            locationId = 3,
            learn = true,
        ),
        ScanReviewItem(key = "r5", text = "PAPPERSPÅSE", source = ScanSource.RECEIPT, include = false),
    )

    fun fridgeRows(): List<ScanReviewItem> = listOf(
        ScanReviewItem(
            key = "f1",
            text = "mjölk",
            source = ScanSource.FRIDGE,
            food = MatchedFood(103, "mjölk", FoodMatchVia.SEARCH),
            confidence = 0.9,
        ),
        ScanReviewItem(key = "f2", text = "något i en burk", source = ScanSource.FRIDGE, confidence = 0.3),
    )
}

/** Theme, app chrome and font scale around a screen under test. */
@Composable
internal fun ScanTestTheme(
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    chrome: AppChrome = AppChrome(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
        TandemTheme(darkTheme = darkTheme) {
            CompositionLocalProvider(LocalAppChrome provides chrome, content = content)
        }
    }
}
