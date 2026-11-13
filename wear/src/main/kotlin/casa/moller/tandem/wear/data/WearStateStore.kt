package casa.moller.tandem.wear.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [WearState] in one JSON file ([file], in `noBackupFilesDir`). Every change goes through [update]
 * under one lock and is written before it is published, so the outbox survives the process being
 * killed right after a tap. A file that cannot be read starts empty (the next sync fills the list
 * again; only unsent checks of that moment are lost).
 */
class WearStateStore(private val file: File, private val io: CoroutineDispatcher) {
    private val lock = Mutex()
    private val current = MutableStateFlow<WearState?>(null)

    /** The state; emits once it is read from the file. */
    val state: Flow<WearState> = current.filterNotNull()

    /** The state now (reads the file the first time). */
    suspend fun load(): WearState = current.value ?: lock.withLock { loadLocked() }

    /** Changes the state with [transform], writes it and publishes it; returns the new state. */
    suspend fun update(transform: (WearState) -> WearState): WearState = lock.withLock {
        val before = loadLocked()
        val after = transform(before)
        if (after != before) {
            write(after)
            current.value = after
        }
        after
    }

    private suspend fun loadLocked(): WearState {
        current.value?.let { return it }
        val read = withContext(io) { read() }
        current.value = read
        return read
    }

    private fun read(): WearState {
        if (!file.isFile) return WearState()
        return try {
            json.decodeFromString(WearState.serializer(), file.readText())
        } catch (e: IOException) {
            WearState()
        } catch (e: SerializationException) {
            WearState()
        } catch (e: IllegalArgumentException) {
            WearState()
        }
    }

    /** Writes [state]; a full or broken disk keeps the change in memory for this process only. */
    private suspend fun write(state: WearState) = withContext(io) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(WearState.serializer(), state))
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        } catch (e: IOException) {
            // Nothing else to do on a watch; the next change tries again.
        }
    }

    companion object {
        /** The file in the app's no-backup folder. */
        const val FILE_NAME: String = "tandem_wear_state.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            allowStructuredMapKeys = true
        }
    }
}
