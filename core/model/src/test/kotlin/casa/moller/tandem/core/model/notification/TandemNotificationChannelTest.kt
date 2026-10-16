package casa.moller.tandem.core.model.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TandemNotificationChannelTest {
    @Test
    fun channelIdsAreFixedForever() {
        // Changing an id resets the user's choices in the system settings (plan 8.7). If this test
        // fails, add a NEW channel instead of renaming one.
        val ids = TandemNotificationChannel.entries.associate { it.name to it.id }
        assertEquals(
            mapOf(
                "TIMER_ALARM" to "timer_alarm",
                "TIMER_ONGOING" to "timer_ongoing",
                "SYNC_PROBLEMS" to "sync_problems",
                "SERVER" to "server",
                "SHOPPING" to "shopping",
                "IMPORT_DONE" to "import_done",
                "MEAL_REMINDERS" to "meal_reminders",
            ),
            ids,
        )
    }

    @Test
    fun timerChannelsFollowThePlan() {
        assertEquals(ChannelImportance.HIGH, TandemNotificationChannel.TIMER_ALARM.importance)
        assertEquals(ChannelSound.ALARM, TandemNotificationChannel.TIMER_ALARM.sound)
        assertEquals(ChannelImportance.LOW, TandemNotificationChannel.TIMER_ONGOING.importance)
        assertEquals(ChannelSound.SILENT, TandemNotificationChannel.TIMER_ONGOING.sound)
        assertEquals(ChannelImportance.LOW, TandemNotificationChannel.SYNC_PROBLEMS.importance)
        assertEquals(TandemNotificationChannel.SERVER, TandemNotificationChannel.fromId("server"))
    }

    @Test
    fun notificationIdsNeverCollide() {
        val fixed = setOf(
            TandemNotificationIds.SERVER_VERSION,
            TandemNotificationIds.SYNC_PROBLEM,
            TandemNotificationIds.TIMER_RINGING_SERVICE,
        )
        val perTimer = (0L..999L).flatMap { listOf(TandemNotificationIds.timerOngoing(it), TandemNotificationIds.timerAlarm(it)) }
        assertEquals(2000, perTimer.toSet().size)
        assertEquals(emptySet<Int>(), fixed intersect perTimer.toSet())
        assertEquals(TandemNotificationIds.timerAlarm(5), TandemNotificationIds.timerAlarm(1005))
        assertNotEquals(TandemNotificationIds.timerAlarm(-1), TandemNotificationIds.timerOngoing(-1))
    }
}
