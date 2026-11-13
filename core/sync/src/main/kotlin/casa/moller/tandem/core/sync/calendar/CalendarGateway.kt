package casa.moller.tandem.core.sync.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import casa.moller.tandem.core.domain.mealplan.MealCalendarChanges
import casa.moller.tandem.core.domain.mealplan.MealCalendarEvent
import casa.moller.tandem.core.domain.mealplan.StoredCalendarEvent
import casa.moller.tandem.core.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.TimeZone
import javax.inject.Inject

// Fas 5 (kalender lane, plan §7 #36): "Visa i telefonens kalender" writes into the phone's calendar
// storage (CalendarContract) as a sync adapter of a LOCAL account of its own, so no account has to
// be added to the phone and nothing is sent anywhere: the calendar exists only on this phone.

/**
 * The phone's calendar storage, as far as Tandem's own calendar "Tandem – matsedel" is concerned.
 * Every call may throw [SecurityException] (a permission was revoked) or another runtime exception
 * of the provider; the caller turns them into a problem. Blocking: call off the main thread.
 */
internal interface CalendarGateway {
    /** True when READ_CALENDAR and WRITE_CALENDAR are granted. */
    fun hasPermission(): Boolean

    /** The id of Tandem's calendar; created when missing and [create] (null when the provider refused or is missing). */
    fun calendarId(create: Boolean): Long?

    /** The events of [calendarId] (what Tandem wrote: `SYNC_DATA1` = key, `SYNC_DATA2` = fingerprint). */
    fun events(calendarId: Long): List<StoredCalendarEvent>

    /** Applies [changes] to [calendarId] in batches. */
    fun apply(calendarId: Long, changes: MealCalendarChanges)

    /** Removes Tandem's calendar and with it every event in it; true when one was there. */
    fun removeCalendar(): Boolean
}

/** [CalendarGateway] on the system's calendar provider ([CalendarContract]). */
internal class AndroidCalendarGateway @Inject constructor(@ApplicationContext private val context: Context) : CalendarGateway {
    private val resolver get() = context.contentResolver

    override fun hasPermission(): Boolean =
        PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    override fun calendarId(create: Boolean): Long? {
        val sort = "${Calendars._ID} ASC"
        resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), OWN_CALENDAR, OWN_CALENDAR_ARGS, sort)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        if (!create) return null
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, CALENDAR_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, context.getString(R.string.meal_calendar_name))
            put(Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            // Read only in calendar apps: a change made there would be overwritten by the next write.
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return resolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)?.let(ContentUris::parseId)
    }

    override fun events(calendarId: Long): List<StoredCalendarEvent> {
        val projection = arrayOf(Events._ID, Events.SYNC_DATA1, Events.SYNC_DATA2)
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0"
        val args = arrayOf(calendarId.toString())
        return resolver.query(asSyncAdapter(Events.CONTENT_URI), projection, selection, args, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(StoredCalendarEvent(rowId = cursor.getLong(0), key = cursor.getString(1), fingerprint = cursor.getString(2)))
                }
            }
        }.orEmpty()
    }

    override fun apply(calendarId: Long, changes: MealCalendarChanges) {
        val events = asSyncAdapter(Events.CONTENT_URI)
        val operations = buildList {
            changes.delete.forEach { add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(events, it)).build()) }
            changes.update.forEach { (rowId, event) ->
                add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(events, rowId)).withValues(valuesOf(event)).build())
            }
            changes.insert.forEach { event ->
                val values = valuesOf(event).apply { put(Events.CALENDAR_ID, calendarId) }
                add(ContentProviderOperation.newInsert(events).withValues(values).build())
            }
        }
        operations.chunked(BATCH).forEach { batch -> resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(batch)) }
    }

    override fun removeCalendar(): Boolean = resolver.delete(asSyncAdapter(Calendars.CONTENT_URI), OWN_CALENDAR, OWN_CALENDAR_ARGS) > 0

    private fun valuesOf(event: MealCalendarEvent): ContentValues = ContentValues().apply {
        put(Events.TITLE, event.title)
        put(Events.DESCRIPTION, event.description)
        put(Events.DTSTART, event.startMillis)
        put(Events.DTEND, event.endMillis)
        put(Events.ALL_DAY, if (event.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, event.timeZone)
        // A planned meal never makes the user look busy.
        put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        put(Events.SYNC_DATA1, event.key)
        put(Events.SYNC_DATA2, event.fingerprint)
    }

    companion object {
        val PERMISSIONS: List<String> = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        /** The local account the calendar belongs to (never an account in the phone's settings). */
        const val ACCOUNT_NAME: String = "Tandem"

        /** The calendar's internal name (the shown one is translated). */
        const val CALENDAR_NAME: String = "tandem_meal_plan"

        /** The brand's tomato (core:designsystem `TandemBrand.Tomato`), as an ARGB int. */
        const val CALENDAR_COLOR: Int = 0xFFC2410C.toInt()

        /** Operations per `applyBatch` (the provider holds one transaction per batch). */
        const val BATCH: Int = 100

        private val OWN_CALENDAR =
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.NAME} = ?"
        private val OWN_CALENDAR_ARGS = arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL, CALENDAR_NAME)

        /** [uri] as the local account's sync adapter: the only way to create a calendar and to write `SYNC_DATA*`. */
        fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
    }
}
