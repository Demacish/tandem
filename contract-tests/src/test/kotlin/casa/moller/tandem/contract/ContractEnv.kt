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
}
