package casa.moller.tandem.core.model.settings

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
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingCompleted: Boolean = false,
    val demoMode: Boolean = false,
    val activeAccountId: String? = null,
    val versionNotice: PendingVersionNotice? = null,
) {
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
