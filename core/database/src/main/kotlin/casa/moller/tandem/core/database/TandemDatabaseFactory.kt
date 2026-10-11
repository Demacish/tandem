package casa.moller.tandem.core.database

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens the real and the demo database (one instance per [DatabaseKind] for the whole process)
 * and clears them. Provided as a singleton by `DatabaseModule`; the unqualified `TandemDatabase`
 * and DAO bindings are the [DatabaseKind.REAL] one, `@DemoDatabase TandemDatabase` is the demo one.
 *
 * Instances are never closed while the process lives (DAOs injected elsewhere keep working);
 * data is removed with [clear] / [clearAccount] instead of deleting the files.
 *
 * @param open builds the database for a kind (a file database in the app, in-memory in tests).
 */
class TandemDatabaseFactory(private val open: (DatabaseKind) -> TandemDatabase) {
    private val instances = ConcurrentHashMap<DatabaseKind, TandemDatabase>()

    /** The database for [kind], opened on first use. */
    fun get(kind: DatabaseKind): TandemDatabase = instances.computeIfAbsent(kind) { open(it) }

    /** Deletes every row of every table in [kind] (e.g. leaving demo mode, removing the last account). */
    suspend fun clear(kind: DatabaseKind) {
        get(kind).clearAllTables()
    }

    /** Deletes everything [accountId] stored in [kind] (logout, account removal); other accounts stay. */
    suspend fun clearAccount(kind: DatabaseKind, accountId: String) {
        get(kind).accountDao().deleteAccountData(accountId)
    }

    companion object {
        /** The app's factory: [DatabaseKind.fileName] files in the app's database folder. */
        fun forFiles(context: Context): TandemDatabaseFactory {
            val appContext = context.applicationContext
            return TandemDatabaseFactory { kind -> TandemDatabase.build(appContext, kind.fileName) }
        }

        /** Separate in-memory databases per kind, for tests. */
        fun inMemory(context: Context): TandemDatabaseFactory {
            val appContext = context.applicationContext
            return TandemDatabaseFactory { TandemDatabase.buildInMemory(appContext) }
        }
    }
}
