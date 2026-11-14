package casa.moller.tandem.core.domain.storereminder

import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.StoreSpot
import kotlin.math.roundToInt
import kotlin.time.Instant

/** Fas 5 (butik lane, plan §10 Fas 5 "inom 200 m"): how close counts as "near the store". */
object StoreReminderRadius {
    const val MIN_METERS: Int = 100
    const val MAX_METERS: Int = 500
    const val DEFAULT_METERS: Int = 200
    const val STEP_METERS: Int = 50

    /** The number of steps between [MIN_METERS] and [MAX_METERS] (a slider's `steps`, the ends not counted). */
    const val SLIDER_STEPS: Int = (MAX_METERS - MIN_METERS) / STEP_METERS - 1

    /** [meters] on the nearest [STEP_METERS] inside [MIN_METERS]..[MAX_METERS]. */
    fun snap(meters: Float): Int {
        val steps = ((meters - MIN_METERS) / STEP_METERS).roundToInt()
        return (MIN_METERS + steps * STEP_METERS).coerceIn(MIN_METERS, MAX_METERS)
    }

    fun snap(meters: Int): Int = snap(meters.toFloat())
}

/**
 * One geofence: a store's circle.
 *
 * @property requestId the geofence's id at Play services ([StoreFences.requestId]); only a store id, nothing else.
 */
data class StoreFence(val storeId: Long, val point: GeoPoint, val radiusMeters: Int) {
    val requestId: String
        get() = StoreFences.requestId(storeId)
}

/** Which geofences should exist, and how they are told apart. */
object StoreFences {
    /** Play services allows 100 geofences per app; more stores than that is not expected. */
    const val MAX_FENCES: Int = 100

    private const val PREFIX = "tandem.store."

    /** The geofences for [spots] when the reminder is [enabled]: every enabled store, by id, at most [MAX_FENCES]. */
    fun wanted(enabled: Boolean, spots: Collection<StoreSpot>): List<StoreFence> {
        if (!enabled) return emptyList()
        return spots.asSequence()
            .filter { it.enabled }
            .sortedBy { it.storeId }
            .take(MAX_FENCES)
            .map { StoreFence(it.storeId, it.point, StoreReminderRadius.snap(it.radiusMeters)) }
            .toList()
    }

    fun requestId(storeId: Long): String = "$PREFIX$storeId"

    /** The store a geofence's [requestId] stands for, or null for one that is not ours. */
    fun storeIdOf(requestId: String): Long? = requestId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()

    /**
     * A short, stable fingerprint of [fences] (FNV-1a over ids, places and radii): the same set gives
     * the same text, so an unchanged set is not registered again at every start. Not reversible to a
     * readable place, but still kept only on the phone.
     */
    fun fingerprint(fences: List<StoreFence>): String {
        var hash = FNV_OFFSET
        fun mix(text: String) {
            for (char in text) {
                hash = hash xor char.code.toLong()
                hash *= FNV_PRIME
            }
        }
        for (fence in fences) {
            mix(fence.storeId.toString())
            mix("|")
            mix(fence.point.latitude.toString())
            mix(",")
            mix(fence.point.longitude.toString())
            mix("|")
            mix(fence.radiusMeters.toString())
            mix(";")
        }
        return fences.size.toString() + ":" + hash.toULong().toString(HEX)
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val HEX = 16
}

/** What the reminder says. */
object StoreReminderCount {
    /** The entries still to buy at [now]: unchecked and not "köp senare" (the same count as the tile's "7 kvar"). */
    fun open(entries: List<ShoppingEntry>, now: Instant): Int = entries.count { !it.checked && !it.isDelayed(now) }
}
