package casa.moller.tandem.contract

import java.io.File

/**
 * Configuration of a contract run, read from environment variables that contract.yml generates
 * for a throwaway Tandoor. Never point these at a production server or a real account.
 */
internal object ContractEnv {
    /** Base URL of the temporary Tandoor, e.g. `http://127.0.0.1:8080`. Tests skip when unset. */
    val baseUrl: String = System.getenv("TANDOOR_URL").orEmpty().trimEnd('/')

    /** Superuser created by the workflow with `manage.py createsuperuser --noinput`. */
    val username: String = System.getenv("TANDOOR_USERNAME").orEmpty()

    /** Random password generated per workflow run; it is redacted from every recording. */
    val password: String = System.getenv("TANDOOR_PASSWORD").orEmpty()

    /**
     * A second, ordinary user that has no space (no UserSpace), created by the workflow with
     * `create_user` only, like a user an admin added who never opened the web UI.
     */
    val noSpaceUsername: String = System.getenv("TANDOOR_NOSPACE_USERNAME").orEmpty()

    /** Random password of [noSpaceUsername]; redacted from every recording. */
    val noSpacePassword: String = System.getenv("TANDOOR_NOSPACE_PASSWORD").orEmpty()

    /** Exact `version` expected from server-settings (empty for floating tags such as `latest`). */
    val expectedVersion: String = System.getenv("TANDOOR_EXPECTED_VERSION").orEmpty()

    /** Image tag under test; names the recording folder (`build/contract-recordings/<tag>`). */
    val tag: String = System.getenv("TANDOOR_TAG").orEmpty().ifBlank { "local" }

    val recordDir: File = File(
        System.getProperty("tandem.contract.recordDir") ?: "build/contract-recordings",
        tag,
    )

    // --- Fas 1 (Phase1ContractTest) ---------------------------------------------------------

    /**
     * A `read write` access token of [username], created by contract.yml with `manage.py shell`
     * AFTER Phase0ContractTest (which needs a token-free account and uses up the login throttle).
     * Phase1ContractTest is skipped unless it is set.
     */
    val fas1Token: String = System.getenv("TANDOOR_FAS1_TOKEN").orEmpty()

    /**
     * A token of a second, ordinary member ("user" group) of the same space, used to check what
     * other members see of a private recipe.
     */
    val readerToken: String = System.getenv("TANDOOR_READER_TOKEN").orEmpty()

    /**
     * Host name the Fas 1 requests claim to be addressed to (sent as `Host` together with
     * `X-Forwarded-Proto: https`, like behind a TLS reverse proxy). Must be in the server's
     * ALLOWED_HOSTS. A reserved example domain: nothing resolves or contacts it.
     */
    const val PUBLIC_HOST: String = "tandoor.example.com"

    /** Fas 1 recordings: `<recordDir>/fas1/<name>.json` (committed to api-schema/fixtures/<tag>/fas1/). */
    val fas1RecordDir: File = File(recordDir, "fas1")

    // --- Fas 2 (Phase2ContractTest) ---------------------------------------------------------

    /**
     * A token of a third member of the contract space in the group `guest` (read-only, plan #42),
     * created by contract.yml after Phase1ContractTest together with a household that holds the
     * contract user and the [readerToken] member. Phase2ContractTest is skipped unless it is set.
     */
    val guestToken: String = System.getenv("TANDOOR_GUEST_TOKEN").orEmpty()

    /** Fas 2 recordings: `<recordDir>/fas2/<name>.json` (committed to api-schema/fixtures/<tag>/fas2/). */
    val fas2RecordDir: File = File(recordDir, "fas2")
}
