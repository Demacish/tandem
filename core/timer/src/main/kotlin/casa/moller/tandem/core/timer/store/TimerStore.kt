package casa.moller.tandem.core.timer.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Where the timers live between processes (they must survive process death and reboot).
 *
 * **Decision (timer lane): DataStore, not Room.** At most [CookTimer.MAX_PARALLEL_TIMERS] small
 * records that are always read and written as a whole, and that a broadcast receiver must read
 * quickly after boot. A typed DataStore file gives atomic, transactional writes of that whole
 * document without a database schema: no Room migration in `core:database` (whose version other
 * lanes bump at the same time), no DAO, and nothing for `AccountDao.deleteAccountData` to clean,
 * because timers belong to the device, not to an account. The file is
 * `files/datastore/cook_timers.json` (excluded from backup like everything else).
 */
internal interface TimerStore {
    /** The stored document; an unreadable file reads as empty (it is replaced, never a crash). */
    suspend fun read(): StoredTimers

    /** Replaces the stored document. Throws `IOException` when the file cannot be written. */
    suspend fun write(timers: StoredTimers)
}

/** The timers DataStore: JSON, and an unreadable file is replaced by an empty document instead of failing every read. */
internal fun timerDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile: () -> File,
): DataStore<StoredTimers> = DataStoreFactory.create(
    serializer = StoredTimersSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { StoredTimers() },
    scope = scope,
    produceFile = produceFile,
)

internal class DataStoreTimerStore(private val dataStore: DataStore<StoredTimers>) : TimerStore {
    override suspend fun read(): StoredTimers = dataStore.data.first()

    override suspend fun write(timers: StoredTimers) {
        dataStore.updateData { timers }
    }

    companion object {
        /** File name under `files/datastore/`. */
        const val FILE_NAME = "cook_timers.json"
    }
}

/** The persisted form: plain numbers (epoch and duration milliseconds), stable `@SerialName`s. */
@Serializable
internal data class StoredTimers(
    @SerialName("version") val version: Int = CURRENT_VERSION,
    @SerialName("next_id") val nextId: Long = 1L,
    @SerialName("timers") val timers: List<StoredTimer> = emptyList(),
) {
    /** The timers that are valid [CookTimer]s; a damaged record is dropped instead of crashing the app. */
    fun toTimers(): List<CookTimer> = timers.mapNotNull { it.toTimer() }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    companion object {
        const val CURRENT_VERSION = 1

        fun of(nextId: Long, timers: List<CookTimer>): StoredTimers = StoredTimers(nextId = nextId, timers = timers.map(StoredTimer::of))
    }
}

@Serializable
internal data class StoredTimer(
    @SerialName("id") val id: Long,
    @SerialName("label") val label: String,
    @SerialName("recipe_id") val recipeId: Long? = null,
    @SerialName("step_index") val stepIndex: Int? = null,
    // Added in the fix lane of 0.2.0; files without it read as null (TimerSource.matches accepts null).
    @SerialName("source") val sourceKey: String? = null,
    @SerialName("duration_ms") val durationMillis: Long,
    @SerialName("state") val state: String,
    @SerialName("ends_at_ms") val endsAtMillis: Long? = null,
    @SerialName("paused_remaining_ms") val pausedRemainingMillis: Long? = null,
    @SerialName("created_at_ms") val createdAtMillis: Long,
) {
    fun toTimer(): CookTimer? {
        val timerState = TimerState.entries.firstOrNull { it.name == state } ?: return null
        return try {
            CookTimer(
                id = id,
                label = label,
                recipeId = recipeId,
                stepIndex = stepIndex,
                sourceKey = sourceKey,
                duration = durationMillis.milliseconds,
                state = timerState,
                endsAt = endsAtMillis?.let(Instant::fromEpochMilliseconds),
                pausedRemaining = pausedRemainingMillis?.milliseconds,
                createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        fun of(timer: CookTimer): StoredTimer = StoredTimer(
            id = timer.id,
            label = timer.label,
            recipeId = timer.recipeId,
            stepIndex = timer.stepIndex,
            sourceKey = timer.sourceKey,
            durationMillis = timer.duration.inWholeMilliseconds,
            state = timer.state.name,
            endsAtMillis = timer.endsAt?.toEpochMilliseconds(),
            pausedRemainingMillis = timer.pausedRemaining?.inWholeMilliseconds,
            createdAtMillis = timer.createdAt.toEpochMilliseconds(),
        )
    }
}

/**
 * One timer as the extra of its ring alarm (fix-data, 0.2.0 review): if the timer never reached
 * the file (a failed write) or the file was lost, the alarm still knows what to ring
 * ([casa.moller.tandem.core.timer.engine.DefaultTimerController.onAlarm]).
 */
internal object TimerSnapshot {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(timer: CookTimer): String = json.encodeToString(StoredTimer.serializer(), StoredTimer.of(timer))

    /** The timer, or null for a missing or unreadable snapshot. */
    fun decode(text: String?): CookTimer? {
        if (text.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(StoredTimer.serializer(), text).toTimer()
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** JSON on disk; an unreadable file becomes a [CorruptionException], which the store replaces with an empty document. */
internal object StoredTimersSerializer : Serializer<StoredTimers> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: StoredTimers = StoredTimers()

    override suspend fun readFrom(input: InputStream): StoredTimers = try {
        json.decodeFromString(StoredTimers.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable timers file", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable timers file", e)
    }

    override suspend fun writeTo(t: StoredTimers, output: OutputStream) {
        output.write(json.encodeToString(StoredTimers.serializer(), t).encodeToByteArray())
    }
}
