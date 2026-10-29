package casa.moller.tandem.core.sync.scan

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.scan.BarcodeLookup
import casa.moller.tandem.core.data.scan.FoodAliasRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.BarcodeProductEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.scan.BarcodeLookupResult
import casa.moller.tandem.core.model.scan.BarcodeProduct
import casa.moller.tandem.core.network.openfoodfacts.OpenFoodFactsClient
import casa.moller.tandem.core.network.openfoodfacts.OpenFoodFactsProductDto
import casa.moller.tandem.core.network.openfoodfacts.OpenFoodFactsResult
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * [BarcodeLookup] (plan §8.8 "Streckkod", #27): a scanned code, in this order:
 *
 * 1. a **learned alias** (`FOOD_ALIAS`, shared by the household's phones): the food's name, no request
 *    (online, the space's aliases are read again first when the last read is a minute old, so a
 *    code another phone just learned is known at once);
 * 2. the **cache** (`barcode_product`, per account): a product for [FOUND_MAX_AGE], "not found" for
 *    [NOT_FOUND_MAX_AGE];
 * 3. **Open Food Facts** (`GET /api/v2/product/{ean}`, [OpenFoodFactsClient]: only the code, Tandem's
 *    own User-Agent, its own OkHttp client); the answer, also "not found", is cached.
 *
 * Only a valid EAN-8, UPC-A or EAN-13 (digits, GS1 check digit) is looked up; anything else fails
 * without a request. Demo mode never calls Open Food Facts (an unknown code is "not found").
 * Offline, a stale cached answer is used; without one the lookup fails with `NetworkUnavailable`.
 */
@Singleton
internal class DefaultBarcodeLookup @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val aliases: FoodAliasRepository,
    private val aliasReader: FoodAliasReader,
    private val openFoodFacts: OpenFoodFactsClient,
    private val network: NetworkStatus,
    private val clock: SyncClock,
) : BarcodeLookup {
    override suspend fun lookup(ean: String): Outcome<BarcodeLookupResult> = catchingUnexpected {
        val code = ean.trim()
        if (!Gtin.isValid(code)) {
            return@catchingUnexpected Outcome.Failure(TandemError.BadRequest(mapOf(FIELD_EAN to emptyList())))
        }
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val online = source is ActiveSource.Remote && network.online.value
        learned(code)?.let { return@catchingUnexpected Outcome.Success(it) }
        // Learned on another phone a moment ago (Fas S "Klart när": known at once on phone B): the
        // space's aliases are read again first (at most once a minute).
        if (online && source is ActiveSource.Remote && aliasReader.syncIfStale(source) is Outcome.Success) {
            learned(code)?.let { return@catchingUnexpected Outcome.Success(it) }
        }
        val cached = databases.get(source.kind).barcodeProductDao().get(source.accountId, code)
        when {
            cached != null && isFresh(cached, clock.nowMillis()) -> Outcome.Success(cached.toResult())

            // Demo mode never asks the network; offline, a stale answer is better than none.
            source !is ActiveSource.Remote -> Outcome.Success(cached?.toResult() ?: BarcodeLookupResult.NotFound)

            !online -> cached?.let { Outcome.Success(it.toResult()) } ?: Outcome.Failure(TandemError.NetworkUnavailable)

            else -> askOpenFoodFacts(source, code, cached)
        }
    }

    /** Open Food Facts for [code]; its answer (also "not found") is cached. A failure falls back to [cached]. */
    private suspend fun askOpenFoodFacts(
        remote: ActiveSource.Remote,
        code: String,
        cached: BarcodeProductEntity?,
    ): Outcome<BarcodeLookupResult> {
        val now = clock.nowMillis()
        return when (val answer = openFoodFacts.product(code)) {
            is Outcome.Failure -> cached?.let { Outcome.Success(it.toResult()) } ?: Outcome.Failure(answer.error)

            is Outcome.Success -> {
                val row = when (val result = answer.value) {
                    OpenFoodFactsResult.NotFound -> BarcodeProductEntity(remote.accountId, code, found = false, fetchedAtEpochMillis = now)
                    is OpenFoodFactsResult.Found -> result.product.toEntity(remote.accountId, code, now)
                }
                databases.get(remote.kind).barcodeProductDao().upsert(row)
                Outcome.Success(row.toResult())
            }
        }
    }

    /** The learned alias of [code] as a product named after its food, null when none. */
    private suspend fun learned(code: String): BarcodeLookupResult? =
        aliases.find(code)?.let { alias -> BarcodeLookupResult.Found(BarcodeProduct(ean = code, name = alias.foodName)) }

    private fun isFresh(row: BarcodeProductEntity, now: Long): Boolean {
        val age = now - row.fetchedAtEpochMillis
        val maxAge: Duration = if (row.found) FOUND_MAX_AGE else NOT_FOUND_MAX_AGE
        return age in 0..maxAge.inWholeMilliseconds
    }

    private fun BarcodeProductEntity.toResult(): BarcodeLookupResult = if (found) {
        BarcodeLookupResult.Found(BarcodeProduct(ean = ean, name = productName, brand = brand, quantity = quantity))
    } else {
        BarcodeLookupResult.NotFound
    }

    companion object {
        const val FIELD_EAN: String = "ean"
        private const val SWEDISH = "sv"
        val FOUND_MAX_AGE: Duration = 30.days
        val NOT_FOUND_MAX_AGE: Duration = 7.days

        /** The product's name in the device's language when Open Food Facts has one, else its main name. */
        fun OpenFoodFactsProductDto.toEntity(
            accountId: String,
            code: String,
            now: Long,
            language: String = Locale.getDefault().language,
        ): BarcodeProductEntity = BarcodeProductEntity(
            accountId = accountId,
            ean = code,
            found = true,
            productName = nameFor(language),
            brand = brands?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            quantity = quantity?.trim()?.takeIf { it.isNotEmpty() },
            fetchedAtEpochMillis = now,
        )

        fun OpenFoodFactsProductDto.nameFor(language: String): String? {
            val ordered = if (language == SWEDISH) {
                listOf(productNameSv, productName, productNameEn)
            } else {
                listOf(productNameEn, productName, productNameSv)
            }
            return ordered.firstNotNullOfOrNull { it?.trim()?.takeIf { name -> name.isNotEmpty() } }
        }
    }
}

/** GS1 codes the scanner reads on food: EAN-8, UPC-A (12) and EAN-13, with a valid check digit. */
internal object Gtin {
    private val LENGTHS = setOf(8, 12, 13)

    fun isValid(code: String): Boolean {
        if (code.length !in LENGTHS || code.any { it !in '0'..'9' }) return false
        val digits = code.map { it - '0' }
        val body = digits.dropLast(1)
        // From the right, the digits next to the check digit weigh 3, then 1, alternating.
        val sum = body.reversed().withIndex().sumOf { (index, digit) -> if (index % 2 == 0) digit * 3 else digit }
        return (10 - sum % 10) % 10 == digits.last()
    }
}
