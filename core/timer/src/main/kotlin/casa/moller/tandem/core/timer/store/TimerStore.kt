package casa.moller.tandem.core.timer.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerState
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
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
            durationMillis = timer.duration.inWholeMilliseconds,
            state = timer.state.name,
            endsAtMillis = timer.endsAt?.toEpochMilliseconds(),
            pausedRemainingMillis = timer.pausedRemaining?.inWholeMilliseconds,
            createdAtMillis = timer.createdAt.toEpochMilliseconds(),
        )
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
