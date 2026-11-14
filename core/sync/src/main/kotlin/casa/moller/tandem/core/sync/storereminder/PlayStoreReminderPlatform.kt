package casa.moller.tandem.core.sync.storereminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import casa.moller.tandem.core.domain.storereminder.StoreFence
import casa.moller.tandem.core.domain.storereminder.StoreFences
import casa.moller.tandem.core.domain.storereminder.StoreTransition
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// Fas 5 (butik lane, plan §7 #34, research 04 §15): the Play services side of the reminder near
// the store. Geofences only (ENTER/EXIT with a relaxed responsiveness): no continuous location, no
// polling, no foreground service. Without Play services the page hides the option
// (GooglePlayServicesCheck) and nothing here is called.

/** [PlayServicesCheck] with GoogleApiAvailability (an outdated Play services counts as missing). */
internal class GooglePlayServicesCheck @Inject constructor(@ApplicationContext private val context: Context) : PlayServicesCheck {
    // Play services' own code can throw anything on an odd phone; any failure means "not available".
    @Suppress("TooGenericExceptionCaught")
    override fun isAvailable(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: RuntimeException) {
        // Play services' own classes misbehaving on an odd phone: the same as missing.
        false
    }
}

/** [GeofenceGateway] on Play services' GeofencingClient, every geofence delivered to [StoreGeofenceReceiver]. */
internal class PlayGeofenceGateway @Inject constructor(@ApplicationContext private val context: Context) : GeofenceGateway {

    // Permissions: checked just before (precise and background), and a SecurityException is caught.
    // The initial trigger 0 (none) is a valid value of the flag set that lint does not list.
    @SuppressLint("MissingPermission", "WrongConstant")
    override suspend fun replaceAll(fences: List<StoreFence>): GeofenceResult {
        if (fences.isEmpty()) return removeAll()
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) || !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            return GeofenceResult.PERMISSION
        }
        val client = LocationServices.getGeofencingClient(context)
        val request = GeofencingRequest.Builder()
            // No trigger when added: turning the reminder on in the store does not remind at once.
            .setInitialTrigger(0)
            .addGeofences(fences.map(::geofence))
            .build()
        return call {
            client.removeGeofences(pendingIntent()).awaitTask()
            client.addGeofences(request, pendingIntent()).awaitTask()
        }
    }

    override suspend fun removeAll(): GeofenceResult = call {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent()).awaitTask()
    }

    // Any failure of Play services is one more FAILED registration (tried again later), never a crash.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun call(block: suspend () -> Unit): GeofenceResult = try {
        withTimeoutOrNull(CALL_TIMEOUT_MILLIS) { block() }?.let { GeofenceResult.OK } ?: GeofenceResult.FAILED
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        when (e.statusCode) {
            GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> GeofenceResult.LOCATION_OFF
            GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION -> GeofenceResult.PERMISSION
            else -> GeofenceResult.FAILED
        }
    } catch (e: SecurityException) {
        GeofenceResult.PERMISSION
    } catch (e: RuntimeException) {
        GeofenceResult.FAILED
    }

    private fun geofence(fence: StoreFence): Geofence = Geofence.Builder()
        .setRequestId(fence.requestId)
        .setCircularRegion(fence.point.latitude, fence.point.longitude, fence.radiusMeters.toFloat())
        .setExpirationDuration(Geofence.NEVER_EXPIRE)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .setNotificationResponsiveness(RESPONSIVENESS_MILLIS)
        .build()

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, StoreGeofenceReceiver::class.java),
        // Mutable: Play services adds the event to this explicit intent.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * How late an ENTER may come (plan §10 Fas 5: battery under 1 % a day). Two minutes lets Play
         * services batch location from other apps and the network instead of waking the GPS, and
         * still reminds while walking the last 200 m to the door.
         */
        const val RESPONSIVENESS_MILLIS: Int = 120_000

        private const val REQUEST_CODE = 3401
        private const val CALL_TIMEOUT_MILLIS = 30_000L
    }
}

/** [CurrentLocationGateway] with one high-accuracy fix from the fused location provider. */
internal class PlayCurrentLocationGateway @Inject constructor(@ApplicationContext private val context: Context) : CurrentLocationGateway {

    // Checked just before, and a SecurityException is caught; any other failure is "no position".
    @SuppressLint("MissingPermission")
    @Suppress("TooGenericExceptionCaught")
    override suspend fun current(): Pair<GeoPoint, Int?>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(FIX_TIMEOUT_MILLIS)
            .setMaxUpdateAgeMillis(MAX_AGE_MILLIS)
            .build()
        val cancel = CancellationTokenSource()
        return try {
            val location = withTimeoutOrNull(FIX_TIMEOUT_MILLIS + GRACE_MILLIS) {
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancel.token).awaitTask()
            } ?: return null
            val point = runCatching { GeoPoint(location.latitude, location.longitude) }.getOrNull() ?: return null
            point to location.takeIf { it.hasAccuracy() }?.accuracy?.roundToInt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            null
        } catch (e: ApiException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            cancel.cancel()
        }
    }

    private companion object {
        const val FIX_TIMEOUT_MILLIS = 30_000L
        const val GRACE_MILLIS = 5_000L

        /** A position from the last minute is good enough (the user is standing in the store). */
        const val MAX_AGE_MILLIS = 60_000L
    }
}

/** [StoreReminderPermissionReader] on the phone's permission and location settings. */
internal class AndroidStoreReminderPermissions @Inject constructor(@ApplicationContext private val context: Context) :
    StoreReminderPermissionReader {
    override fun read(): StoreReminderPermissions {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = NotificationManagerCompat.from(context)
        val channelOn = manager.getNotificationChannelCompat(TandemNotificationChannel.SHOPPING.id)
            ?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE } ?: true
        val locationManager = context.getSystemService(LocationManager::class.java)
        return StoreReminderPermissions(
            precise = fine,
            approximateOnly = coarse && !fine,
            background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS) && manager.areNotificationsEnabled() && channelOn,
            locationOn = locationManager?.let(LocationManagerCompat::isLocationEnabled) ?: false,
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** [DeviceRegistrationKey] from the system settings and the package. */
internal class AndroidDeviceRegistrationKey @Inject constructor(@ApplicationContext private val context: Context) : DeviceRegistrationKey {
    override fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    override fun versionCode(): Long = try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        -1L
    }
}

/** [SystemEventSwitch] on [StoreReminderSystemReceiver]'s component state (off in the manifest). */
internal class ComponentSystemEventSwitch @Inject constructor(@ApplicationContext private val context: Context) : SystemEventSwitch {
    override fun setEnabled(enabled: Boolean) {
        val component = ComponentName(context, StoreReminderSystemReceiver::class.java)
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        try {
            if (context.packageManager.getComponentEnabledSetting(component) != state) {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        } catch (e: IllegalArgumentException) {
            // Never expected for our own component; the next process start puts the geofences back anyway.
        } catch (e: SecurityException) {
            // The same.
        }
    }
}

/** [GeofenceEventReader] on Play services' GeofencingEvent; only our own geofences count. */
internal object PlayGeofenceEventReader : GeofenceEventReader {
    // An intent Play services cannot read is ignored, never a crash in the receiver.
    @Suppress("TooGenericExceptionCaught")
    override fun read(intent: Intent): GeofenceEvent {
        val event = try {
            GeofencingEvent.fromIntent(intent)
        } catch (e: RuntimeException) {
            null
        } ?: return GeofenceEvent.Ignored
        if (event.hasError()) {
            return if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) GeofenceEvent.NotAvailable else GeofenceEvent.Ignored
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> StoreTransition.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> StoreTransition.EXIT
            else -> return GeofenceEvent.Ignored
        }
        val stores = event.triggeringGeofences.orEmpty().mapNotNull { StoreFences.storeIdOf(it.requestId) }.distinct()
        return if (stores.isEmpty()) GeofenceEvent.Ignored else GeofenceEvent.Transition(stores, transition)
    }
}

/** A Play services task as a suspending call (no extra library needed for these two calls). */
internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
