package casa.moller.tandem.core.data

import casa.moller.tandem.core.model.error.TandemError

/**
 * Failures that come from the data layer itself rather than from the server. They travel as
 * [TandemError.Unknown] with a fixed [detail] (TandemError lives in core:model and has no type
 * for them yet); use [dataProblem] to recognise them and show a specific message.
 */
enum class DataProblem(val detail: String) {
    /** The login worked, but the device token could not be saved in encrypted storage. */
    SECURE_STORAGE("tandem.data.secure-storage"),

    /** The user has no space on the server (`space/current` returned none). */
    NO_SPACE("tandem.data.no-space"),

    /** Login to a server older than the supported range was refused before any request. */
    SERVER_TOO_OLD("tandem.data.server-too-old"),

    /** The operation needs a logged-in account, but there is none. */
    NO_ACCOUNT("tandem.data.no-account"),

    /** A non-secret setting (e.g. the theme) could not be written to the settings file. */
    SETTINGS_NOT_SAVED("tandem.data.settings-not-saved"),

    /**
     * The server takes no password from the app: allauth (Tandoor 2.7+) wants a step the app
     * cannot do (e-mail verification, a second factor) or the server has no app login. The user
     * pastes a token instead.
     */
    PASSWORD_LOGIN_UNAVAILABLE("tandem.data.password-login-unavailable"),

    /**
     * Phase 2 (#42): the active user is a guest (read-only mode, `SpacePermissions.readOnly`):
     * repositories refuse writes before anything is stored or queued.
     */
    READ_ONLY("tandem.data.read-only"),

    /** Phase 2 (plan 3.7): a write to a recipe book someone else owns (the server would answer 403). */
    NOT_OWNER("tandem.data.not-owner"),
    ;

    fun toError(): TandemError = TandemError.Unknown(detail)
}

/** The [DataProblem] this error stands for, or null for errors from the server or network. */
fun TandemError.dataProblem(): DataProblem? {
    val detail = (this as? TandemError.Unknown)?.detail ?: return null
    return DataProblem.entries.firstOrNull { it.detail == detail }
}
