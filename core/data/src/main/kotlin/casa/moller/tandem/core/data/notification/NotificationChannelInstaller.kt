package casa.moller.tandem.core.data.notification

/**
 * Creates one module's notification channels (plan 8.7, docs/BYGGE.md "Notiskanaler"). Every
 * module that posts notifications binds one into a Hilt set (`@IntoSet`), with the ids from
 * core:model's `TandemNotificationChannel` and its own translated names; the app creates all of
 * them once at startup (Phase 1b integration). The channels are then in the system settings from
 * the first start, so "Behörigheter" can open the alarm channel before any timer rang.
 *
 * Must be idempotent: creating an existing channel only updates its name and description, the
 * user's choices (importance, sound) stay. Modules still call it before they post, so a channel
 * deleted by the user or renamed by a language change comes back right.
 */
fun interface NotificationChannelInstaller {
    fun createChannels()
}
