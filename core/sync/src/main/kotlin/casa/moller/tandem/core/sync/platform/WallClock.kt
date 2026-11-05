package casa.moller.tandem.core.sync.platform

import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Fas 4 (skafferinotis lane): the wall clock AND the device's time zone, for rules that depend on the
 * local hour (the morning expiry notification: its day, its quiet hours and its next run). The
 * zone is read on every call, so a phone that travels or changes to summer time follows at once.
 * Tests use fixed instants in chosen zones.
 */
internal interface WallClock {
    fun now(): Instant

    fun zone(): TimeZone
}

internal class SystemWallClock @Inject constructor() : WallClock {
    override fun now(): Instant = Clock.System.now()

    override fun zone(): TimeZone = TimeZone.currentSystemDefault()
}
