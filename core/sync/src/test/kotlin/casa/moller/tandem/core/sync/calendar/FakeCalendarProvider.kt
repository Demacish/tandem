package casa.moller.tandem.core.sync.calendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.util.concurrent.CopyOnWriteArrayList
import org.robolectric.Robolectric

/**
 * Fas 5 (kalender lane): a precise stand-in for the phone's calendar provider (Robolectric has
 * none), on an in-memory SQLite database with the real column names, so the gateway's own
 * selections, projections and values run as they would on a phone. It keeps the provider's rules
 * that matter here: only a sync adapter (`caller_is_syncadapter=true` with the account in the URI)
 * may create a calendar or write `sync_data*` columns; an event needs `calendar_id`, `dtstart`,
 * `dtend` and `eventTimezone`; a sync adapter's delete removes the row, anyone else's only marks it
 * `deleted`; deleting a calendar deletes its events. Unknown columns fail like on a phone.
 */
internal class FakeCalendarProvider : ContentProvider() {
    private lateinit var db: SQLiteDatabase

    /** Every write as "insert calendars", "update events 3", … (to see that a second write does nothing). */
    val writes: MutableList<String> = CopyOnWriteArrayList()

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE Calendars (_id INTEGER PRIMARY KEY AUTOINCREMENT, account_name TEXT, account_type TEXT, name TEXT, " +
                "calendar_displayName TEXT, calendar_color INTEGER, calendar_access_level INTEGER, ownerAccount TEXT, visible INTEGER, " +
                "sync_events INTEGER, calendar_timezone TEXT)",
        )
        db.execSQL(
            "CREATE TABLE Events (_id INTEGER PRIMARY KEY AUTOINCREMENT, calendar_id INTEGER NOT NULL, title TEXT, description TEXT, " +
                "dtstart INTEGER NOT NULL, dtend INTEGER, allDay INTEGER NOT NULL DEFAULT 0, eventTimezone TEXT NOT NULL, " +
                "availability INTEGER, sync_data1 TEXT, sync_data2 TEXT, deleted INTEGER NOT NULL DEFAULT 0)",
        )
        return true
    }

    /** The events as the phone's calendar app would read them (not deleted). */
    fun events(): List<Map<String, Any?>> = rows("SELECT * FROM Events WHERE deleted = 0 ORDER BY dtstart, _id")

    fun calendars(): List<Map<String, Any?>> = rows("SELECT * FROM Calendars ORDER BY _id")

    /** Adds an event like a user or another app would (no sync adapter). */
    fun addForeignEvent(calendarId: Long, title: String) {
        db.insertOrThrow(
            "Events",
            null,
            ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DTSTART, 0L)
                put(Events.DTEND, 1L)
                put(Events.EVENT_TIMEZONE, "UTC")
            },
        )
    }

    private fun rows(sql: String): List<Map<String, Any?>> = db.rawQuery(sql, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    cursor.columnNames.associateWith { column ->
                        val index = cursor.getColumnIndexOrThrow(column)
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            else -> cursor.getString(index)
                        }
                    },
                )
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val (table, id) = target(uri)
        val where = listOfNotNull(selection, id?.let { "_id = $it" }).joinToString(" AND ").ifEmpty { null }
        return db.query(table, projection, where, args, null, null, sort)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val (table, _) = target(uri)
        val row = requireNotNull(values)
        checkWrite(uri, table, row)
        if (table == "Calendars") require(uri.isSyncAdapter()) { "only a sync adapter may create a calendar" }
        if (table == "Events") {
            for (column in listOf(Events.CALENDAR_ID, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE)) {
                require(row.containsKey(column)) { "$column is required" }
            }
        }
        val id = db.insertOrThrow(table, null, row)
        writes += "insert ${table.lowercase()}"
        return ContentUris.withAppendedId(uri.buildUpon().clearQuery().build(), id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val (table, id) = target(uri)
        val row = requireNotNull(values)
        checkWrite(uri, table, row)
        val where = listOfNotNull(selection, id?.let { "_id = $it" }).joinToString(" AND ").ifEmpty { null }
        val count = db.update(table, row, where, args)
        writes += "update ${table.lowercase()} ${id ?: selection}"
        return count
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        val (table, id) = target(uri)
        val where = listOfNotNull(selection, id?.let { "_id = $it" }).joinToString(" AND ").ifEmpty { null }
        writes += "delete ${table.lowercase()} ${id ?: selection}"
        if (table == "Calendars") {
            val ids = db.query(table, arrayOf("_id"), where, args, null, null, null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            ids.forEach { db.delete("Events", "calendar_id = ?", arrayOf(it.toString())) }
            return db.delete(table, where, args)
        }
        if (!uri.isSyncAdapter()) return db.update(table, ContentValues().apply { put(Events.DELETED, 1) }, where, args)
        return db.delete(table, where, args)
    }

    override fun getType(uri: Uri): String? = null

    private fun target(uri: Uri): Pair<String, Long?> {
        val segments = uri.pathSegments
        val table = when (segments.firstOrNull()) {
            "calendars" -> "Calendars"
            "events" -> "Events"
            else -> throw IllegalArgumentException("Unknown URL $uri")
        }
        return table to segments.getOrNull(1)?.toLong()
    }

    private fun checkWrite(uri: Uri, table: String, values: ContentValues) {
        if (uri.isSyncAdapter()) {
            require(uri.getQueryParameter(Calendars.ACCOUNT_NAME) != null && uri.getQueryParameter(Calendars.ACCOUNT_TYPE) != null) {
                "a sync adapter must name its account"
            }
        } else {
            val sync = values.keySet().filter { it.startsWith("sync_data") || it == Calendars.ACCOUNT_NAME }
            require(table == "Events" && sync.isEmpty()) { "only a sync adapter may write $sync to $table" }
        }
    }

    private fun Uri.isSyncAdapter(): Boolean = getQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER) == "true"

    companion object {
        /** Registers a fresh provider under the calendar authority for the current Robolectric test. */
        fun install(): FakeCalendarProvider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
    }
}
