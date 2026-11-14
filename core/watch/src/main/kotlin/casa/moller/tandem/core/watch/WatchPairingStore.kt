package casa.moller.tandem.core.watch

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The phone's memory of the paired watch (no token: only its id on the server, so the phone can
 * delete it). Room stays at schema 6: one small DataStore file, excluded from backup like every
 * other file of the app.
 *
 * @property revokeOnWatch the paired account left the phone (logout): the phone has no token to
 *   delete the watch token with, so the watch deletes its own the next time it is reachable.
 * @property revokedOnServer Fas 5 (integration-08): the phone deleted the watch token on the server
 *   just before the account left (`AccountRemovalHook`); the watch is then only wiped, it sends nothing.
 */
@Serializable
data class StoredWatchPairing(
    val nodeId: String,
    val watchName: String,
    val accountId: String,
    val accountLabel: String = "",
    val tokenId: Long,
    val expiresEpochMillis: Long,
    val pairedAtEpochMillis: Long,
    val revokeOnWatch: Boolean = false,
    val revokedOnServer: Boolean = false,
)

/** Reads and writes [StoredWatchPairing]. */
interface WatchPairingStore {
    val pairing: Flow<StoredWatchPairing?>

    suspend fun load(): StoredWatchPairing? = pairing.first()

    suspend fun save(pairing: StoredWatchPairing?)
}

/** [WatchPairingStore] in `files/datastore/watch_pairing.json`. */
@Singleton
internal class DataStoreWatchPairingStore @Inject constructor(@ApplicationContext context: Context) : WatchPairingStore {
    private val store: DataStore<Holder> = DataStoreFactory.create(
        serializer = HolderSerializer,
        // A broken file means "not paired" (the watch token then simply expires on the server).
        corruptionHandler = ReplaceFileCorruptionHandler { Holder() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.dataStoreFile(FILE_NAME) },
    )

    override val pairing: Flow<StoredWatchPairing?> = store.data
        .catch { if (it is IOException) emit(Holder()) else throw it }
        .map { it.pairing }

    override suspend fun save(pairing: StoredWatchPairing?) {
        store.updateData { Holder(pairing) }
    }

    @Serializable
    internal data class Holder(val pairing: StoredWatchPairing? = null)

    private object HolderSerializer : Serializer<Holder> {
        private val json = Json { ignoreUnknownKeys = true }

        override val defaultValue: Holder = Holder()

        override suspend fun readFrom(input: InputStream): Holder = try {
            json.decodeFromString(Holder.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("watch pairing", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("watch pairing", e)
        }

        override suspend fun writeTo(t: Holder, output: OutputStream) {
            output.write(json.encodeToString(Holder.serializer(), t).encodeToByteArray())
        }
    }

    companion object {
        const val FILE_NAME: String = "watch_pairing.json"
    }
}
