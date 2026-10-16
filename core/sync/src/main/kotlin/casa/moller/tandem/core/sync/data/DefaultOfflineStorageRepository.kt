package casa.moller.tandem.core.sync.data

import android.content.Context
import casa.moller.tandem.core.data.sync.OfflineStorageRepository
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.sync.OfflineStorage
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.image.ImageCache
import casa.moller.tandem.core.sync.platform.SyncClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update

/**
 * [OfflineStorageRepository]: the active source's recipe counts from Room (live), and the sizes of
 * the database files and the image disk cache, measured again every [MEASURE_EVERY_MILLIS] while
 * collected (a running "download all" grows them) and right after "Clear image cache".
 *
 * The download estimate is rough on purpose: remaining recipes × (the average stored detail JSON
 * × [DETAIL_STORAGE_FACTOR] for its steps, ingredients and index rows + an average photo). The
 * average photo comes from the cache when it holds enough of them, else [DEFAULT_IMAGE_BYTES].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultOfflineStorageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val imageCache: ImageCache,
    private val clock: SyncClock,
) : OfflineStorageRepository {

    /** Bumped by [clearImageCache], so the sizes are measured again at once. */
    private val cleared = MutableStateFlow(0)

    override fun observeStorage(): Flow<OfflineStorage> {
        val counts = sources.source.flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> {
                    val recipes = databases.get(source.kind).recipeDao()
                    combine(recipes.observeRecipeCount(source.accountId), recipes.observeDownloadedCount(source.accountId)) { all, stored ->
                        Counts(source, all, stored)
                    }
                }

                ActiveSource.Loading, ActiveSource.None -> flowOf(Counts(source, 0, 0))
            }
        }
        val sizes = combine(clock.ticks(MEASURE_EVERY_MILLIS), cleared) { _, _ -> measure() }.flowOn(Dispatchers.IO)
        return combine(counts, sizes) { count, size -> storageOf(count, size) }
            .catch { emit(OfflineStorage.Empty) }
            .distinctUntilChanged()
    }

    override suspend fun clearImageCache(): Outcome<Unit> = try {
        imageCache.clear()
        cleared.update { it + 1 }
        Outcome.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failure(TandemError.Unknown(CLEAR_FAILED))
    }

    private data class Counts(val source: ActiveSource, val recipes: Int, val downloaded: Int)

    private data class Sizes(val databaseBytes: Long, val imageBytes: Long)

    private suspend fun measure(): Sizes = Sizes(databaseBytes = databaseBytes(), imageBytes = imageCache.sizeBytes())

    /** The real database with its journal files (the demo database is not the user's data). */
    private fun databaseBytes(): Long = try {
        val main = context.getDatabasePath(DatabaseKind.REAL.fileName)
        listOf(main, File(main.path + "-wal"), File(main.path + "-shm"), File(main.path + "-journal"))
            .sumOf { if (it.isFile) it.length() else 0L }
    } catch (e: SecurityException) {
        0L
    }

    private suspend fun storageOf(counts: Counts, sizes: Sizes): OfflineStorage {
        val remaining = (counts.recipes - counts.downloaded).coerceAtLeast(0)
        val estimate = if (remaining == 0 || counts.source !is ActiveSource.Remote) {
            0L
        } else {
            val detailBytes = try {
                databases.get(counts.source.kind).recipeDao().getAverageDetailBytes(counts.source.accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0.0
            }
            val perDetail = if (detailBytes > 0) detailBytes * DETAIL_STORAGE_FACTOR else DEFAULT_DETAIL_BYTES.toDouble()
            val perImage = if (counts.downloaded >= MIN_SAMPLE && sizes.imageBytes > 0) {
                (sizes.imageBytes.toDouble() / counts.downloaded).coerceIn(MIN_IMAGE_BYTES.toDouble(), MAX_IMAGE_BYTES.toDouble())
            } else {
                DEFAULT_IMAGE_BYTES.toDouble()
            }
            (remaining * (perDetail + perImage)).roundToLong()
        }
        return OfflineStorage(
            recipeCount = counts.recipes,
            downloadedCount = counts.downloaded.coerceAtMost(counts.recipes),
            databaseBytes = sizes.databaseBytes,
            imageCacheBytes = sizes.imageBytes,
            estimatedDownloadBytes = estimate,
        )
    }

    internal companion object {
        const val MEASURE_EVERY_MILLIS: Long = 5_000L

        /** A stored detail also fills step, ingredient, keyword and search rows: about twice its JSON. */
        const val DETAIL_STORAGE_FACTOR: Double = 2.0

        /** Without a stored detail to measure. */
        const val DEFAULT_DETAIL_BYTES: Long = 24L * 1024

        /** A typical recipe photo as Tandoor serves it, until the cache tells better. */
        const val DEFAULT_IMAGE_BYTES: Long = 150L * 1024
        const val MIN_IMAGE_BYTES: Long = 20L * 1024
        const val MAX_IMAGE_BYTES: Long = 2L * 1024 * 1024

        /** Downloaded recipes needed before the cache's average photo is trusted. */
        const val MIN_SAMPLE: Int = 10

        const val CLEAR_FAILED: String = "tandem.storage.clear-image-cache"
    }
}
