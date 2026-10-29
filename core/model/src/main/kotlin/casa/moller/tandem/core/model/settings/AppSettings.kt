package casa.moller.tandem.core.model.settings

import casa.moller.tandem.core.model.timer.TimerMode
import kotlin.math.abs

/** Which colour scheme the app uses (plan 6.4). */
enum class ThemeMode {
    /** Follow the system setting (default). */
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Non-secret app settings, stored in a Preferences DataStore by `:core:datastore`.
 *
 * @property themeMode light, dark or follow the system.
 * @property onboardingCompleted the intro/onboarding flow has been finished once (plan 6.2).
 * @property demoMode the app runs on local demo data instead of a server (plan 6.2).
 * @property activeAccountId [casa.moller.tandem.core.model.account.ServerAccount.id] of the
 *   account in use, or `null`. It can point to an account that no longer exists (for example
 *   after the encrypted account store was reset), so callers must check it against the account
 *   list.
 * @property versionNotice a server version change the app still has to tell the user about
 *   inside the app (plan B18), or `null` when there is none or it was shown.
 *
 * Phase 1b additions (plan 6.2 "Inställningar", written through core:data's `SettingsRepository`):
 * @property dynamicColor "Utseende → Dynamisk färg": Material You colours from the wallpaper
 *   instead of Tandem's own palette. Off by default (the brand palette is the design).
 * @property cookTextScale "Utseende → Textstorlek i kokläge" and cook mode's A−/A+: a factor on
 *   top of the system font scale, one of [CookTextScale.STEPS].
 * @property cookModeAlwaysDark cook mode is dark even when the app theme is light (kitchen at night).
 * @property timerMode "Beteende → Timer i appen eller i klockappen".
 * @property syncInterval "Synk → Intervall" of the periodic background sync.
 * @property downloadAllRecipes "Synk → Ladda ner alla recept": keep every recipe's detail (and
 *   image) offline, not only pinned, planned and recently viewed ones (plan feature #5).
 * @property notificationPermissionAsked the system's notification dialog was shown once (at the
 *   first timer, plan 8.7). Afterwards the app links to the system settings instead.
 * @property exactAlarmPromptDismissed play build: the user dismissed "allow exact alarms" (plan
 *   8.1/4.4); the late-alarm warning stays, the prompt is not shown again.
 *
 * Fas S (drycker lane):
 * @property systembolagetLink "Sök på Systembolaget" on a drink (a plain web link, plan §8.8); null
 *   = not chosen, which [systembolagetLinkShown] reads as on for a Swedish UI and off otherwise.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingCompleted: Boolean = false,
    val demoMode: Boolean = false,
    val activeAccountId: String? = null,
    val versionNotice: PendingVersionNotice? = null,
    // --- Phase 1b (grund lane): settings for cook mode, timers and sync (plan 6.2). ---
    val dynamicColor: Boolean = false,
    val cookTextScale: Float = CookTextScale.DEFAULT,
    val cookModeAlwaysDark: Boolean = false,
    val timerMode: TimerMode = TimerMode.IN_APP,
    val syncInterval: SyncInterval = SyncInterval.DEFAULT,
    val downloadAllRecipes: Boolean = false,
    val notificationPermissionAsked: Boolean = false,
    val exactAlarmPromptDismissed: Boolean = false,
    // --- Fas S (drycker lane) ---
    val systembolagetLink: Boolean? = null,
) {
    /** Whether a drink shows "Sök på Systembolaget" for a UI in [languageTag] ("sv", "en-GB"). */
    fun systembolagetLinkShown(languageTag: String): Boolean =
        systembolagetLink ?: languageTag.substringBefore('-').equals("sv", ignoreCase = true)

    companion object {
        /** Settings of a fresh install. */
        val Default: AppSettings = AppSettings()
    }
}

/**
 * "Tandoor was updated to 2.6.15" waiting to be shown inside the app (plan B18). Stored when a
 * check found a new TESTED server version and no system notification could be posted
 * (notifications not allowed, or the "Server" channel switched off); an untested version has the
 * safe mode banner instead. Cleared as soon as the app showed it, so it is shown once, also
 * across process death.
 *
 * @property accountId the account whose server changed; only shown while it is the active one.
 * @property previousVersion the version stored before.
 * @property newVersion the version the server reports now.
 */
data class PendingVersionNotice(val accountId: String, val previousVersion: String, val newVersion: String)

/**
 * How often the periodic background sync runs (plan 6.2 "Synk → intervall"). WorkManager's
 * shortest period is 15 minutes. Opening the app and pull-to-refresh sync regardless.
 *
 * @property minutes the period, or null for [MANUAL].
 */
enum class SyncInterval(val minutes: Int?) {
    FIFTEEN_MINUTES(15),
    ONE_HOUR(60),
    SIX_HOURS(360),

    /** No periodic sync: only on start, on resume and on pull-to-refresh. */
    MANUAL(null),
    ;

    companion object {
        /** The interval of 0.1.0 (`tandem.sync.periodic`, 15 min). */
        val DEFAULT: SyncInterval = FIFTEEN_MINUTES
    }
}

/**
 * Cook mode's text size steps (plan 6.2 "Textstorleken justeras med A−/A+"). The factor applies
 * on top of the system font scale, so TalkBack users with large system text keep it.
 */
object CookTextScale {
    /** The steps of A−/A+, smallest first. */
    val STEPS: List<Float> = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f)

    const val DEFAULT: Float = 1.0f

    val MIN: Float get() = STEPS.first()
    val MAX: Float get() = STEPS.last()

    /** The step closest to [scale] (a stored value from an older version, or garbage → [DEFAULT]). */
    fun coerce(scale: Float): Float = if (scale.isNaN()) DEFAULT else STEPS.minBy { abs(it - scale) }

    /** A+: the next larger step, or [MAX]. */
    fun larger(scale: Float): Float = STEPS.firstOrNull { it > coerce(scale) } ?: MAX

    /** A−: the next smaller step, or [MIN]. */
    fun smaller(scale: Float): Float = STEPS.lastOrNull { it < coerce(scale) } ?: MIN
}
