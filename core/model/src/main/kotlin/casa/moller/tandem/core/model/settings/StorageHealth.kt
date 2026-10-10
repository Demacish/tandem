package casa.moller.tandem.core.model.settings

/**
 * Why local storage had to be reset or could not be read. The app shows the recovery screen
 * (plan 4.2 step 7, feature #37) instead of crashing when any of these happen.
 *
 * Only failures while READING stored data are reported here. A failed write (for example while
 * saving a new login) reaches its caller as an exception instead, so the screen that started it
 * can say what went wrong (the login step shows "could not be saved") without the app jumping to
 * the recovery screen in the middle of a flow.
 */
enum class RecoveryReason {
    /**
     * The encryption key is permanently unusable (Tink keyset unreadable or the Android Keystore
     * master key missing or invalidated, for example after a restore to a new phone). New keys
     * were created and the saved accounts were discarded. A transient Keystore failure is never
     * reported as this: see [SECURE_STORAGE_UNAVAILABLE].
     */
    KEY_UNUSABLE,

    /** The encrypted account file could not be decrypted or parsed. It was reset to empty. */
    ACCOUNTS_UNREADABLE,

    /** The settings file was corrupt. It was reset to the defaults. */
    SETTINGS_UNREADABLE,

    /**
     * The encrypted accounts could not be read right now (for example Android Keystore failed
     * several times in a row, as it can right after a reboot). Nothing was deleted: the file and
     * the keys are kept, and the store tries to read them again in the background.
     */
    SECURE_STORAGE_UNAVAILABLE,
}

/**
 * Observable state of local storage since the app started.
 *
 * @property recoveryReasons everything that went wrong and was reset, empty when healthy.
 */
data class StorageHealth(val recoveryReasons: Set<RecoveryReason> = emptySet()) {
    /** `true` when the app should show the recovery screen. */
    val recoveryNeeded: Boolean
        get() = recoveryReasons.isNotEmpty()

    companion object {
        val Healthy: StorageHealth = StorageHealth()
    }
}
