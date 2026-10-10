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
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingCompleted: Boolean = false,
    val demoMode: Boolean = false,
    val activeAccountId: String? = null,
) {
    companion object {
        /** Settings of a fresh install. */
        val Default: AppSettings = AppSettings()
    }
}
